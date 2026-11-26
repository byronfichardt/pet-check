package io.github.byronfichardt.petcheck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.byronfichardt.petcheck.audio.FinishedEvent
import io.github.byronfichardt.petcheck.audio.MonitorPipeline
import io.github.byronfichardt.petcheck.audio.SoundTypeMapper
import io.github.byronfichardt.petcheck.data.SoundType
import io.github.byronfichardt.petcheck.monitor.YamnetClassifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real sound model on a device or emulator. The JVM tests use a fake classifier,
 * so this is the only place the native library, the bundled model and the label mapping
 * are exercised together.
 */
@RunWith(AndroidJUnit4::class)
class ClassifierDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** testdata/dog_bark.wav: 16 kHz mono, a single bark 2.25 s in. */
    private fun bark(): ShortArray {
        val bytes = instrumentation.context.assets.open("dog_bark.wav").use { it.readBytes() }
        val samples = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, bytes.size - WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        return ShortArray(samples.remaining()).also(samples::get)
    }

    @Test
    fun modelLoadsAndScoresEveryLabel() {
        val labels = YamnetClassifier.loadLabels(context)
        assertEquals(YamnetClassifier.LABEL_COUNT, labels.size)
        YamnetClassifier(context).use { classifier ->
            val scores = classifier.classify(FloatArray(MonitorPipeline.WINDOW_SAMPLES))
            assertEquals(YamnetClassifier.LABEL_COUNT, scores.size)
            assertTrue(scores.all { it in 0f..1f })
            assertTrue("silence should be recognised as silence", scores[labels.indexOf("Silence")] > 0.5f)
        }
    }

    @Test
    fun aRealBarkIsHeardAsABark() {
        val recording = bark()
        val labels = YamnetClassifier.loadLabels(context)
        YamnetClassifier(context).use { classifier ->
            val start = (1.6 * MonitorPipeline.SAMPLE_RATE).toInt()
            val window = FloatArray(MonitorPipeline.WINDOW_SAMPLES) { recording[start + it] / 32768f }
            val scores = classifier.classify(window)
            assertTrue("Bark score was ${scores[labels.indexOf("Bark")]}", scores[labels.indexOf("Bark")] > 0.5f)
            assertTrue("Dog score was ${scores[labels.indexOf("Dog")]}", scores[labels.indexOf("Dog")] > 0.5f)
        }
    }

    @Test
    fun theWholePipelineLogsTheBarkWithItsTimeAndLength() {
        val recording = bark()
        val events = mutableListOf<FinishedEvent>()
        YamnetClassifier(context).use { classifier ->
            val pipeline = MonitorPipeline(classifier, SoundTypeMapper(YamnetClassifier.loadLabels(context)))
            val frame = ShortArray(MonitorPipeline.FRAME_SAMPLES)
            var offset = 0
            while (offset + frame.size <= recording.size) {
                recording.copyInto(frame, 0, offset, offset + frame.size)
                pipeline.onFrame(frame, frame.size, offset * 1000L / MonitorPipeline.SAMPLE_RATE).finished?.let(events::add)
                offset += frame.size
            }
            pipeline.flush()?.let(events::add)
        }

        assertEquals("events: $events", 1, events.size)
        val event = events.single()
        assertEquals(SoundType.BARK, event.type)
        assertEquals(2250L, event.startMs)
        assertEquals(300L, event.durationMs)
        assertEquals(-10.7, event.peakDb, 0.2)
        assertTrue("confidence was ${event.confidence}", event.confidence > 0.5f)
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
