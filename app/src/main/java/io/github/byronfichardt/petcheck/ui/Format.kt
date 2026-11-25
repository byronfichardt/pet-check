package io.github.byronfichardt.petcheck.ui

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Format {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
    private val shortTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM")
    private val dayWithYear = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    private val month = DateTimeFormatter.ofPattern("MMMM yyyy")

    /** "0.4 s", "12 s", "3 min 20 s", "1 h 12 min". */
    fun duration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            ms < 1000 -> String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> if (seconds % 60 == 0L) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"
            else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        }
    }

    /** A shorter form for tight spaces: "45 s", "12m 25s", "1h 12m". */
    fun compactDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> duration(ms)
            seconds < 3600 -> if (seconds % 60 == 0L) "${seconds / 60}m" else "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        }
    }

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        time.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun shortTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortTime.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun day(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> day.format(date)
        else -> dayWithYear.format(date)
    }

    fun month(value: YearMonth): String = month.format(value)

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val minutes = (nowMs - epochMs) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}
