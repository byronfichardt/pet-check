package io.github.byronfichardt.petcheck.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {
    private data class Case(
        val name: String,
        var config: DetectorConfig = DetectorConfig(),
        val frames: MutableList<Double> = mutableListOf(),
        val expect: MutableList<DetectedEvent> = mutableListOf(),
    )

    /** testdata/detector_cases.txt is shared with the Raspberry Pi monitor's tests. */
    private fun loadCases(): List<Case> {
        val cases = mutableListOf<Case>()
        File("../testdata/detector_cases.txt").forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val keyword = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            when (keyword) {
                "case" -> cases += Case(rest)
                "config" -> rest.split(' ').forEach { pair ->
                    val (key, value) = pair.split('=')
                    val case = cases.last()
                    case.config = when (key) {
                        "margin_db" -> case.config.copy(marginDb = value.toDouble())
                        "hold_ms" -> case.config.copy(holdMs = value.toLong())
                        "max_event_ms" -> case.config.copy(maxEventMs = value.toLong())
                        "attack_ms" -> case.config.copy(attackMs = value.toLong())
                        else -> error("unknown config key: $key")
                    }
                }
                "frames" -> rest.split(' ').forEach { chunk ->
                    val (level, count) = chunk.split('x')
                    repeat(count.toInt()) { cases.last().frames += level.toDouble() }
                }
                "expect" -> {
                    val (start, duration, peak) = rest.split(' ')
                    cases.last().expect += DetectedEvent(start.toLong(), duration.toLong(), peak.toDouble())
                }
                else -> error("unknown line: $line")
            }
        }
        return cases
    }

    @Test
    fun sharedCases() {
        val cases = loadCases()
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val detector = EventDetector(case.config)
            val events = mutableListOf<DetectedEvent>()
            case.frames.forEachIndexed { index, level ->
                val signal = detector.process(index * FRAME_MS, FRAME_MS, level)
                if (signal is DetectorSignal.Ended) events += signal.event
            }
            detector.flush()?.let { events += it }
            assertEquals(case.name, case.expect, events)
        }
    }

    @Test
    fun startSignalCarriesTheFirstLoudFrame() {
        val detector = EventDetector()
        repeat(20) { assertEquals(null, detector.process(it * FRAME_MS, FRAME_MS, -70.0)) }
        assertEquals(null, detector.process(1000, FRAME_MS, -20.0))
        assertEquals(DetectorSignal.Started(1000), detector.process(1050, FRAME_MS, -20.0))
        assertTrue(detector.active)
    }

    @Test
    fun levelMeterMatchesKnownSignals() {
        assertEquals(0.0, rmsDb(ShortArray(200) { if (it % 2 == 0) 32767 else -32768 }), 0.01)
        assertEquals(-6.02, rmsDb(ShortArray(200) { if (it % 2 == 0) 16384 else -16384 }), 0.01)
        assertEquals(SILENCE_DB, rmsDb(ShortArray(200)), 0.0)
        assertEquals(SILENCE_DB, rmsDb(ShortArray(0)), 0.0)
    }

    @Test
    fun sensitivityMapsToMargin() {
        assertEquals(30.0, DetectorConfig.marginForSensitivity(0), 1e-9)
        assertEquals(19.0, DetectorConfig.marginForSensitivity(50), 1e-9)
        assertEquals(8.0, DetectorConfig.marginForSensitivity(100), 1e-9)
        assertEquals(8.0, DetectorConfig.marginForSensitivity(500), 1e-9)
    }

    private companion object {
        const val FRAME_MS = 50L
    }
}
