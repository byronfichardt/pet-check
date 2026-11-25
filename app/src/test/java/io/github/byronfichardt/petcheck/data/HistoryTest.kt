package io.github.byronfichardt.petcheck.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    private val utc = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 11, 25)
    private val dayStart = day.atStartOfDay(utc).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    private fun event(startedAt: Long, durationMs: Long, type: SoundType = SoundType.BARK) =
        SoundEvent("id-$startedAt", "device", "Device", startedAt, durationMs, type, -12f, 0.8f)

    @Test
    fun hourlyBucketsSplitAnEventThatCrossesTheHour() {
        val events = listOf(
            event(dayStart + 9 * hour + 59 * 60_000, 120_000),
            event(dayStart + 9 * hour, 5_000, SoundType.HOWL),
        )
        val buckets = History.hourly(events, day, utc)
        assertEquals(24, buckets.size)
        assertEquals(mapOf(SoundType.BARK to 60_000L, SoundType.HOWL to 5_000L), buckets[9].msByType)
        assertEquals(mapOf(SoundType.BARK to 1, SoundType.HOWL to 1), buckets[9].countByType)
        assertEquals(mapOf(SoundType.BARK to 60_000L), buckets[10].msByType)
        assertEquals(0, buckets[10].totalCount)
        assertEquals(125_000L, buckets.sumOf { it.totalMs })
    }

    @Test
    fun aDayWhenTheClocksGoForwardHasTwentyThreeHours() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(23, History.hourly(emptyList(), LocalDate.of(2026, 3, 29), berlin).size)
        assertEquals(25, History.hourly(emptyList(), LocalDate.of(2026, 10, 25), berlin).size)
    }

    @Test
    fun dailyBucketsCoverTheMonth() {
        val month = YearMonth.of(2026, 11)
        val events = listOf(event(dayStart + hour, 30_000), event(dayStart + 2 * hour, 10_000, SoundType.MEOW))
        val buckets = History.daily(events, month, utc)
        assertEquals(30, buckets.size)
        assertEquals(40_000L, buckets[24].totalMs)
        assertEquals(2, buckets[24].totalCount)
        assertEquals(0L, buckets[23].totalMs)
        assertEquals(History.monthRange(month, utc).first, buckets.first().startMs)
        assertEquals(History.monthRange(month, utc).last + 1, buckets.last().endMs)
    }

    @Test
    fun summaryAddsUpCountsAndDurations() {
        val summary = History.summarize(
            listOf(event(1, 4_000), event(2, 10_000), event(3, 1_000, SoundType.WHINE)),
        )
        assertEquals(3, summary.eventCount)
        assertEquals(15_000L, summary.totalMs)
        assertEquals(10_000L, summary.longestMs)
        assertEquals(mapOf(SoundType.BARK to 2, SoundType.WHINE to 1), summary.countByType)
        assertEquals(mapOf(SoundType.BARK to 14_000L, SoundType.WHINE to 1_000L), summary.msByType)
        assertEquals(HistorySummary(0, 0, 0, emptyMap(), emptyMap()), History.summarize(emptyList()))
    }

    @Test
    fun dayRangeIsHalfOpenInTheLocalZone() {
        val range = History.dayRange(day, utc)
        assertEquals(dayStart, range.first)
        assertEquals(dayStart + 24 * hour - 1, range.last)
        assertEquals(day, History.localDate(range.last, utc))
    }

    @Test
    fun soundTypeKeysRoundTrip() {
        SoundType.entries.forEach { assertEquals(it, SoundType.fromKey(it.key)) }
        assertEquals(SoundType.OTHER, SoundType.fromKey("something new"))
        assertEquals(SoundType.OTHER, SoundType.fromKey(null))
    }
}
