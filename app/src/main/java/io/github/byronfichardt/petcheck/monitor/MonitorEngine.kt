package io.github.byronfichardt.petcheck.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import io.github.byronfichardt.petcheck.audio.DetectorConfig
import io.github.byronfichardt.petcheck.audio.FinishedEvent
import io.github.byronfichardt.petcheck.audio.MonitorPipeline
import io.github.byronfichardt.petcheck.audio.SILENCE_DB
import io.github.byronfichardt.petcheck.audio.SoundClassifier
import io.github.byronfichardt.petcheck.audio.SoundTypeMapper
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import org.tensorflow.lite.Interpreter

/** YAMNet on LiteRT: 15600 samples at 16 kHz in, 521 scores out. */
class YamnetClassifier(context: Context) : SoundClassifier, AutoCloseable {
    private val interpreter: Interpreter
    private val output = Array(1) { FloatArray(LABEL_COUNT) }

    init {
        val model = context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    override fun classify(window: FloatArray): FloatArray {
        interpreter.run(window, output)
        return output[0]
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET = "yamnet.tflite"
        const val LABELS_ASSET = "yamnet_label_list.txt"
        const val LABEL_COUNT = 521

        fun loadLabels(context: Context): List<String> =
            context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.toList()
            }
    }
}

/**
 * Owns the microphone and the capture thread. Audio only ever lives in memory here:
 * each frame is measured, kept for under a second for classification, then overwritten.
 */
class MonitorEngine(private val context: Context, private val listener: Listener) {
    interface Listener {
        /** Called for every 50 ms frame, on the capture thread. */
        fun onLevel(levelDb: Double, thresholdDb: Double, active: Boolean, micSilenced: Boolean)

        fun onEvent(event: FinishedEvent)

        /** The capture thread has exited. [error] is null after a normal [stop]. */
        fun onStopped(error: String?)
    }

    @Volatile private var running = false
    @Volatile private var pendingConfig: DetectorConfig? = null
    private var worker: Thread? = null

    /** False when the on-device model could not be loaded: events are then all stored as "other". */
    @Volatile var classifierReady = false
        private set

    fun updateConfig(config: DetectorConfig) {
        pendingConfig = config
    }

    fun start(config: DetectorConfig) {
        if (running) return
        running = true
        pendingConfig = config
        worker = thread(name = "petcheck-audio") { run() }
    }

    /** Stops capture and waits briefly for the thread to flush the event in progress. */
    fun stop() {
        running = false
        worker?.takeIf { it !== Thread.currentThread() }?.join(STOP_TIMEOUT_MS)
        worker = null
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var classifier: YamnetClassifier? = null
        var record: AudioRecord? = null
        var error: String? = null
        try {
            val mapper = SoundTypeMapper(YamnetClassifier.loadLabels(context))
            classifier = try {
                YamnetClassifier(context)
            } catch (e: Exception) {
                Log.e(TAG, "Sound classifier unavailable", e)
                null
            }
            classifierReady = classifier != null
            val pipeline = MonitorPipeline(classifier, mapper, pendingConfig ?: DetectorConfig())
            pendingConfig = null

            record = openMicrophone()
            if (record == null) {
                error = "Could not open the microphone."
                return
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error = "The microphone is in use by another app."
                return
            }
            error = capture(record, pipeline)
            pipeline.flush()?.let(listener::onEvent)
        } catch (e: Exception) {
            Log.e(TAG, "Monitoring failed", e)
            error = e.message ?: "Monitoring failed."
        } finally {
            runCatching { record?.stop() }
            record?.release()
            classifier?.close()
            val stoppedByRequest = !running
            running = false
            listener.onStopped(if (stoppedByRequest && error == null) null else error ?: "Monitoring stopped.")
        }
    }

    /** Reads frames until stopped. Returns an error message, or null after a normal stop. */
    private fun capture(record: AudioRecord, pipeline: MonitorPipeline): String? {
        val frame = ShortArray(MonitorPipeline.FRAME_SAMPLES)
        val frameMs = MonitorPipeline.FRAME_SAMPLES * 1000L / MonitorPipeline.SAMPLE_RATE
        var anchorMs = System.currentTimeMillis()
        var samplesSinceAnchor = 0L
        var silentFrames = 0

        while (running) {
            pendingConfig?.let {
                pipeline.config = it
                pendingConfig = null
            }
            val read = record.read(frame, 0, frame.size)
            if (read <= 0) return if (running) "The microphone stopped responding." else null

            // Time comes from the sample count so that a slow classification does not distort
            // durations. Re-anchor to the wall clock if the two drift apart.
            var frameStart = anchorMs + samplesSinceAnchor * 1000L / MonitorPipeline.SAMPLE_RATE
            val wallStart = System.currentTimeMillis() - frameMs
            if (abs(wallStart - frameStart) > MAX_CLOCK_DRIFT_MS) {
                anchorMs = wallStart
                samplesSinceAnchor = 0
                frameStart = wallStart
            }
            samplesSinceAnchor += read

            val result = pipeline.onFrame(frame, read, frameStart)
            // Android feeds pure silence to a background recorder when another app takes the microphone.
            silentFrames = if (result.levelDb <= SILENCE_DB + 1) silentFrames + 1 else 0
            listener.onLevel(result.levelDb, result.thresholdDb, result.active, silentFrames >= SILENCED_FRAMES)
            result.finished?.let(listener::onEvent)
        }
        return null
    }

    @SuppressLint("MissingPermission") // The service checks RECORD_AUDIO before starting the engine.
    private fun openMicrophone(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            MonitorPipeline.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        // Two seconds of headroom, so a slow classification never drops audio.
        val bufferBytes = max(minBuffer, MonitorPipeline.SAMPLE_RATE * 2 * 2)

        // Prefer sources without automatic gain control, which would flatten the loudness readings.
        val audioManager = context.getSystemService(AudioManager::class.java)
        val sources = buildList {
            if (audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        for (source in sources) {
            val record = try {
                AudioRecord(
                    source,
                    MonitorPipeline.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $source unavailable", e)
                null
            }
            if (record?.state == AudioRecord.STATE_INITIALIZED) return record
            record?.release()
        }
        return null
    }

    private companion object {
        const val TAG = "MonitorEngine"
        const val STOP_TIMEOUT_MS = 1_500L
        const val MAX_CLOCK_DRIFT_MS = 1_000L
        const val SILENCED_FRAMES = 100 // 5 s of digital silence
    }
}
