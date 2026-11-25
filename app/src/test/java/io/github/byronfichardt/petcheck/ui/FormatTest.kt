package io.github.byronfichardt.petcheck.ui

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FormatTest {
    @Before
    fun setLocale() = Locale.setDefault(Locale.UK)

    @Test
    fun durations() {
        assertEquals("0.4 s", Format.duration(400))
        assertEquals("12 s", Format.duration(12_900))
        assertEquals("3 min 20 s", Format.duration(200_000))
        assertEquals("2 min", Format.duration(120_000))
        assertEquals("1 h 12 min", Format.duration(4_320_000))
    }

    @Test
    fun relativeDaysAndTimes() {
        val today = LocalDate.of(2026, 11, 25)
        assertEquals("Today", Format.day(today, today))
        assertEquals("Yesterday", Format.day(today.minusDays(1), today))
        assertEquals("Mon 23 Nov", Format.day(today.minusDays(2), today))
        assertEquals("Thu 25 Dec 2025", Format.day(LocalDate.of(2025, 12, 25), today))
        assertEquals("just now", Format.ago(1_000, 30_000))
        assertEquals("5 min ago", Format.ago(0, 5 * 60_000))
        assertEquals("3 h ago", Format.ago(0, 3 * 3_600_000))
        assertEquals("2 days ago", Format.ago(0, 50 * 3_600_000L))
    }

    @Test
    fun chartAxisUsesRoundNumbers() {
        assertEquals(60.0, axisMax(41.0, HistoryMetric.TIME), 0.0)
        assertEquals(300.0, axisMax(121.0, HistoryMetric.TIME), 0.0)
        assertEquals(10.0, axisMax(0.0, HistoryMetric.TIME), 0.0)
        assertEquals(10.0, axisMax(7.0, HistoryMetric.COUNT), 0.0)
        assertEquals("30 s", axisLabel(30.0, 60.0, HistoryMetric.TIME))
        assertEquals("2.5 min", axisLabel(150.0, 300.0, HistoryMetric.TIME))
        assertEquals("1 h", axisLabel(3600.0, 7200.0, HistoryMetric.TIME))
        assertEquals("0", axisLabel(0.0, 300.0, HistoryMetric.TIME))
        assertEquals("5", axisLabel(5.0, 10.0, HistoryMetric.COUNT))
    }
}
