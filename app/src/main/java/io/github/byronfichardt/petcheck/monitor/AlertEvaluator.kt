package io.github.byronfichardt.petcheck.monitor

import io.github.byronfichardt.petcheck.data.SoundType
import kotlin.math.max
import kotlin.math.min

data class AlertRule(
    val enabled: Boolean = false,
    /** Alert once this much noise... */
    val noisySeconds: Int = 60,
    /** ...has been heard within this many minutes. */
    val windowMinutes: Int = 10,
    /** Stay quiet this long after an alert. */
    val cooldownMinutes: Int = 30,
    /** Count sounds that were not recognised as a pet. */
    val includeOther: Boolean = false,
)

data class AlertTrigger(
    val atMs: Long,
    val noisySeconds: Int,
    val windowMinutes: Int,
    val type: SoundType,
    val eventCount: Int,
)

/**
 * Decides when enough noise has built up to alert the owner.
 * pi/petcheck_pi/alerts.py mirrors this class.
 */
class AlertEvaluator {
    private data class Entry(val startMs: Long, val endMs: Long, val type: SoundType)

    private val recent = ArrayDeque<Entry>()
    private var lastAlertMs: Long? = null

    /** Call for each finished event, in order. Returns a trigger when the rule is met. */
    fun onEvent(rule: AlertRule, startMs: Long, durationMs: Long, type: SoundType): AlertTrigger? {
        if (!rule.enabled) {
            recent.clear()
            return null
        }
        if (type == SoundType.OTHER && !rule.includeOther) return null

        val endMs = startMs + durationMs
        recent.addLast(Entry(startMs, endMs, type))
        val windowStart = endMs - rule.windowMinutes * 60_000L
        while (recent.isNotEmpty() && recent.first().endMs <= windowStart) recent.removeFirst()

        val last = lastAlertMs
        if (last != null && endMs - last < rule.cooldownMinutes * 60_000L) return null

        val msByType = LongArray(SoundType.entries.size)
        var total = 0L
        for (entry in recent) {
            val overlap = min(entry.endMs, endMs) - max(entry.startMs, windowStart)
            if (overlap > 0) {
                msByType[entry.type.ordinal] += overlap
                total += overlap
            }
        }
        if (total < rule.noisySeconds * 1000L) return null

        lastAlertMs = endMs
        val dominant = SoundType.entries.maxBy { msByType[it.ordinal] }
        return AlertTrigger(endMs, (total / 1000).toInt(), rule.windowMinutes, dominant, recent.size)
    }
}
