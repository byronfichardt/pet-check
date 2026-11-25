package io.github.byronfichardt.petcheck.audio

import io.github.byronfichardt.petcheck.data.SoundType

/** Scores one window of audio. Implementations return one score per model label. */
fun interface SoundClassifier {
    fun classify(window: FloatArray): FloatArray
}

data class FinishedEvent(
    val startMs: Long,
    val durationMs: Long,
    val peakDb: Double,
    val type: SoundType,
    val confidence: Float,
)

data class FrameResult(
    val levelDb: Double,
    val thresholdDb: Double,
    val active: Boolean,
    val finished: FinishedEvent?,
)

/**
 * Turns a stream of 16 kHz PCM frames into finished, classified sound events.
 * Not thread-safe: call from the capture thread only. pi/petcheck_pi/pipeline.py mirrors this class.
 */
class MonitorPipeline(
    private val classifier: SoundClassifier?,
    private val mapper: SoundTypeMapper,
    config: DetectorConfig = DetectorConfig(),
    private val sampleRate: Int = SAMPLE_RATE,
) {
    private val detector = EventDetector(config)
    private val ring = FloatArray(WINDOW_SAMPLES)
    private val window = FloatArray(WINDOW_SAMPLES)
    private var ringPos = 0
    private var accumulator = mapper.accumulator()
    private var untilInference = 0

    var config: DetectorConfig
        get() = detector.config
        set(value) {
            detector.config = value
        }

    fun onFrame(samples: ShortArray, length: Int, frameStartMs: Long): FrameResult {
        val frameMs = length * 1000L / sampleRate
        val level = rmsDb(samples, length)
        writeRing(samples, length)

        var finished: FinishedEvent? = null
        when (val signal = detector.process(frameStartMs, frameMs, level)) {
            is DetectorSignal.Started -> {
                accumulator = mapper.accumulator()
                untilInference = FIRST_INFERENCE_SAMPLES
            }
            is DetectorSignal.Ended -> finished = finish(signal.event)
            null -> Unit
        }

        if (detector.active && classifier != null) {
            untilInference -= length
            if (untilInference <= 0) {
                untilInference = HOP_SAMPLES
                if (detector.msSinceLoud(frameStartMs + frameMs) <= INFERENCE_QUIET_LIMIT_MS) {
                    accumulator.add(classifier.classify(snapshot()))
                }
            }
        }
        return FrameResult(level, detector.thresholdDb, detector.active, finished)
    }

    fun flush(): FinishedEvent? = detector.flush()?.let(::finish)

    private fun finish(event: DetectedEvent): FinishedEvent {
        val result = accumulator.result()
        return FinishedEvent(event.startMs, event.durationMs, event.peakDb, result.type, result.confidence)
    }

    private fun writeRing(samples: ShortArray, length: Int) {
        var pos = ringPos
        for (i in 0 until length) {
            ring[pos] = samples[i] / 32768f
            if (++pos == WINDOW_SAMPLES) pos = 0
        }
        ringPos = pos
    }

    /** The last [WINDOW_SAMPLES] samples, oldest first. */
    private fun snapshot(): FloatArray {
        val tail = WINDOW_SAMPLES - ringPos
        ring.copyInto(window, 0, ringPos, WINDOW_SAMPLES)
        ring.copyInto(window, tail, 0, ringPos)
        return window
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 800 // 50 ms
        const val WINDOW_SAMPLES = 15_600 // one YAMNet window, 0.975 s
        const val FIRST_INFERENCE_SAMPLES = 4_800 // classify 0.3 s into an event, so short barks are caught
        const val HOP_SAMPLES = 7_800 // then every half window
        const val INFERENCE_QUIET_LIMIT_MS = 1_000L // stop classifying once the event has gone quiet
    }
}
