package io.github.byronfichardt.petcheck.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val SILENCE_DB = -96.0

/** Level of 16-bit PCM samples in dBFS (0 is full scale). */
fun rmsDb(samples: ShortArray, length: Int = samples.size): Double {
    if (length <= 0) return SILENCE_DB
    var total = 0.0
    for (i in 0 until length) {
        val v = samples[i] / 32768.0
        total += v * v
    }
    val rms = sqrt(total / length)
    return if (rms <= 0.0) SILENCE_DB else max(SILENCE_DB, 20.0 * log10(rms))
}

data class DetectorConfig(
    /** How far above the noise floor counts as loud. */
    val marginDb: Double = 19.0,
    /** Never trigger below this, however quiet the room. */
    val minTriggerDb: Double = -50.0,
    /** Never require more than this, however noisy the room. */
    val maxTriggerDb: Double = -6.0,
    /** Must stay loud this long before an event starts. */
    val attackMs: Long = 100,
    /** Quiet gap that ends an event. */
    val holdMs: Long = 2_000,
    /** Longer noise is split into several events. */
    val maxEventMs: Long = 120_000,
    /** The noise floor adapts slowly to louder surroundings and quickly to quieter ones. */
    val floorRiseMs: Long = 10_000,
    val floorFallMs: Long = 1_000,
    val minFloorDb: Double = -80.0,
    val maxFloorDb: Double = -30.0,
) {
    companion object {
        /** Maps the 0-100 sensitivity setting to dB above the noise floor (30 dB down to 8 dB). */
        fun marginForSensitivity(sensitivity: Int): Double = 30.0 - sensitivity.coerceIn(0, 100) * 0.22
    }
}

data class DetectedEvent(val startMs: Long, val durationMs: Long, val peakDb: Double)

sealed interface DetectorSignal {
    data class Started(val startMs: Long) : DetectorSignal
    data class Ended(val event: DetectedEvent) : DetectorSignal
}

/**
 * Loudness-gated sound event detection.
 *
 * pi/petcheck_pi/detector.py mirrors this class. Both are checked against
 * testdata/detector_cases.txt, so change them together.
 */
class EventDetector(var config: DetectorConfig = DetectorConfig()) {
    private var floor: Double? = null
    private var startMs = 0L
    private var lastLoudEndMs = 0L
    private var peakDb = SILENCE_DB
    private var attackStartMs = -1L
    private var attackPeakDb = SILENCE_DB

    var active = false
        private set

    val noiseFloorDb: Double get() = floor ?: config.minFloorDb

    val thresholdDb: Double
        get() = min(config.maxTriggerDb, max(config.minTriggerDb, noiseFloorDb + config.marginDb))

    fun msSinceLoud(nowMs: Long): Long = nowMs - lastLoudEndMs

    /** Feed one frame. Returns a signal when an event starts or ends, otherwise null. */
    fun process(frameStartMs: Long, frameMs: Long, levelDb: Double): DetectorSignal? {
        val frameEnd = frameStartMs + frameMs
        if (floor == null) floor = clampFloor(levelDb)
        val loud = levelDb >= thresholdDb

        if (!active) {
            if (!loud) {
                attackStartMs = -1
                updateFloor(levelDb, frameMs)
                return null
            }
            if (attackStartMs < 0) {
                attackStartMs = frameStartMs
                attackPeakDb = levelDb
            } else {
                attackPeakDb = max(attackPeakDb, levelDb)
            }
            if (frameEnd - attackStartMs < config.attackMs) return null
            active = true
            startMs = attackStartMs
            peakDb = attackPeakDb
            lastLoudEndMs = frameEnd
            attackStartMs = -1
            return DetectorSignal.Started(startMs)
        }

        if (loud) {
            peakDb = max(peakDb, levelDb)
            lastLoudEndMs = frameEnd
        }
        if (frameEnd - lastLoudEndMs >= config.holdMs || frameEnd - startMs >= config.maxEventMs) {
            return DetectorSignal.Ended(finish())
        }
        return null
    }

    /** Ends the event in progress, if any. Call when monitoring stops. */
    fun flush(): DetectedEvent? {
        attackStartMs = -1
        return if (active) finish() else null
    }

    private fun finish(): DetectedEvent {
        active = false
        return DetectedEvent(startMs, lastLoudEndMs - startMs, peakDb)
    }

    private fun updateFloor(levelDb: Double, frameMs: Long) {
        val current = floor ?: return
        val tau = if (levelDb > current) config.floorRiseMs else config.floorFallMs
        val alpha = min(1.0, frameMs.toDouble() / tau)
        floor = clampFloor(current + alpha * (levelDb - current))
    }

    private fun clampFloor(value: Double): Double = min(config.maxFloorDb, max(config.minFloorDb, value))
}
