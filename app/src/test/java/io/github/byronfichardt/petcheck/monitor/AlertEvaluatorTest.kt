package io.github.byronfichardt.petcheck.monitor

import io.github.byronfichardt.petcheck.data.SoundType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlertEvaluatorTest {
    private val rule = AlertRule(enabled = true, noisySeconds = 60, windowMinutes = 10, cooldownMinutes = 30)
    private val minute = 60_000L

    @Test
    fun firesOnceEnoughNoiseBuildsUpInsideTheWindow() {
        val evaluator = AlertEvaluator()
        assertNull(evaluator.onEvent(rule, 0, 25_000, SoundType.BARK))
        assertNull(evaluator.onEvent(rule, 2 * minute, 25_000, SoundType.HOWL))
        val trigger = evaluator.onEvent(rule, 4 * minute, 15_000, SoundType.BARK)
        assertEquals(AlertTrigger(4 * minute + 15_000, 65, 10, SoundType.BARK, 3), trigger)
    }

    @Test
    fun noiseOlderThanTheWindowDoesNotCount() {
        val evaluator = AlertEvaluator()
        assertNull(evaluator.onEvent(rule, 0, 40_000, SoundType.BARK))
        assertNull(evaluator.onEvent(rule, 11 * minute, 40_000, SoundType.BARK))
        assertNotNull(evaluator.onEvent(rule, 13 * minute, 40_000, SoundType.BARK))
    }

    @Test
    fun staysQuietDuringTheCooldown() {
        val evaluator = AlertEvaluator()
        assertNotNull(evaluator.onEvent(rule, 0, 90_000, SoundType.BARK))
        assertNull(evaluator.onEvent(rule, 5 * minute, 90_000, SoundType.BARK))
        assertNull(evaluator.onEvent(rule, 20 * minute, 90_000, SoundType.BARK))
        assertNotNull(evaluator.onEvent(rule, 31 * minute, 90_000, SoundType.BARK))
    }

    @Test
    fun otherSoundsOnlyCountWhenAskedTo() {
        assertNull(AlertEvaluator().onEvent(rule, 0, 90_000, SoundType.OTHER))
        val trigger = AlertEvaluator().onEvent(rule.copy(includeOther = true), 0, 90_000, SoundType.OTHER)
        assertEquals(SoundType.OTHER, trigger?.type)
    }

    @Test
    fun disabledRuleNeverFiresAndForgetsWhatItHeard() {
        val evaluator = AlertEvaluator()
        assertNull(evaluator.onEvent(rule.copy(enabled = false), 0, 90_000, SoundType.BARK))
        assertNull(evaluator.onEvent(rule, minute * 2, 10_000, SoundType.BARK))
    }
}
