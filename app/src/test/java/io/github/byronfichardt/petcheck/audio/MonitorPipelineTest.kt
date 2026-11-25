package io.github.byronfichardt.petcheck.audio

import io.github.byronfichardt.petcheck.data.SoundType
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorPipelineTest {
    private val labels = listOf("Speech", "Dog", "Bark", "Yip", "Howl", "Bow-wow", "Growling", "Whimper (dog)", "Cat", "Meow", "Caterwaul", "Silence")
    private val mapper = SoundTypeMapper(labels)

    private fun scores(vararg values: Pair<String, Float>) = FloatArray(labels.size).also { array ->
        values.forEach { (label, score) -> array[labels.indexOf(label)] = score }
    }

    private val quiet = ShortArray(MonitorPipeline.FRAME_SAMPLES) { if (it % 2 == 0) 8 else -8 }
    private val loud = ShortArray(MonitorPipeline.FRAME_SAMPLES) { (sin(2 * PI * 800 * it / 16_000) * 12_000).toInt().toShort() }

    /** Feeds frames and returns the finished events. */
    private fun run(pipeline: MonitorPipeline, vararg plan: Pair<ShortArray, Int>): List<FinishedEvent> {
        val events = mutableListOf<FinishedEvent>()
        var time = 0L
        for ((frame, count) in plan) {
            repeat(count) {
                pipeline.onFrame(frame, frame.size, time).finished?.let(events::add)
                time += 50
            }
        }
        pipeline.flush()?.let(events::add)
        return events
    }

    @Test
    fun aBarkIsDetectedMeasuredAndClassified() {
        var calls = 0
        val classifier = SoundClassifier { window ->
            assertEquals(MonitorPipeline.WINDOW_SAMPLES, window.size)
            // On the first pass the sound is still going: the newest audio, at the end of the
            // window, must be the loud tone and the oldest must be the quiet room.
            if (calls++ == 0) {
                assertTrue(window.takeLast(400).any { it > 0.2f })
                assertTrue(window.take(400).all { it < 0.01f })
            }
            scores("Dog" to 0.8f, "Bark" to 0.7f)
        }
        val events = run(MonitorPipeline(classifier, mapper), quiet to 40, loud to 20, quiet to 60)

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(2000, event.startMs)
        assertEquals(1000, event.durationMs)
        assertEquals(SoundType.BARK, event.type)
        assertEquals(0.7f, event.confidence, 1e-6f)
        assertEquals(-11.5, event.peakDb, 0.5)
        // First pass 0.3 s in, then every half window while the sound lasts or has only just stopped.
        assertTrue("classifier ran $calls times", calls in 2..5)
    }

    @Test
    fun aVeryShortSoundIsStillClassified() {
        var calls = 0
        val classifier = SoundClassifier {
            calls++
            scores("Meow" to 0.9f)
        }
        val events = run(MonitorPipeline(classifier, mapper), quiet to 40, loud to 3, quiet to 60)
        assertEquals(1, events.size)
        assertEquals(150, events.single().durationMs)
        assertEquals(SoundType.MEOW, events.single().type)
        // Once 0.3 s in, and once more just after it stops.
        assertEquals(2, calls)
    }

    @Test
    fun theClassifierIsNotRunWhileTheRoomIsQuiet() {
        var calls = 0
        val classifier = SoundClassifier {
            calls++
            scores()
        }
        val pipeline = MonitorPipeline(classifier, mapper)
        assertTrue(run(pipeline, quiet to 400).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun unrecognisedAndUnclassifiedSoundsAreOther() {
        val unsure = SoundClassifier { scores("Speech" to 0.9f, "Bark" to 0.1f) }
        assertEquals(SoundType.OTHER, run(MonitorPipeline(unsure, mapper), quiet to 40, loud to 10, quiet to 60).single().type)

        val noModel = run(MonitorPipeline(null, mapper), quiet to 40, loud to 10, quiet to 60).single()
        assertEquals(SoundType.OTHER, noModel.type)
        assertEquals(0f, noModel.confidence, 0f)
    }

    @Test
    fun eachEventIsClassifiedOnItsOwn() {
        var call = 0
        val classifier = SoundClassifier { if (call++ < 2) scores("Howl" to 0.9f) else scores("Bark" to 0.6f) }
        val events = run(MonitorPipeline(classifier, mapper), quiet to 40, loud to 10, quiet to 80, loud to 10, quiet to 60)
        assertEquals(listOf(SoundType.HOWL, SoundType.BARK), events.map { it.type })
    }

    @Test
    fun mapperPrefersSpecificLabelsAndFallsBackToGenericOnes() {
        val specific = mapper.accumulator().apply { add(scores("Dog" to 0.9f, "Howl" to 0.5f, "Bark" to 0.4f)) }
        assertEquals(Classification(SoundType.HOWL, 0.5f), specific.result())

        val generic = mapper.accumulator().apply { add(scores("Cat" to 0.6f, "Meow" to 0.1f)) }
        assertEquals(Classification(SoundType.MEOW, 0.6f), generic.result())

        val peak = mapper.accumulator().apply {
            add(scores("Growling" to 0.2f))
            add(scores("Growling" to 0.8f))
            add(scores("Whimper (dog)" to 0.5f))
        }
        assertEquals(Classification(SoundType.GROWL, 0.8f), peak.result())
        assertEquals(3, peak.windows)

        assertEquals(SoundType.OTHER, mapper.accumulator().result().type)
        assertFalse(SoundType.OTHER.isPet)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mapperRejectsALabelListWithoutThePetLabels() {
        SoundTypeMapper(listOf("Speech", "Music"))
    }
}
