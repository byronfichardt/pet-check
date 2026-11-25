package io.github.byronfichardt.petcheck.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** Noise in one slice of a chart: an hour of a day, or a day of a month. */
data class HistoryBucket(
    val startMs: Long,
    val endMs: Long,
    /** Noisy time per type. An event that crosses a boundary is split between buckets. */
    val msByType: Map<SoundType, Long>,
    /** Events per type, counted in the bucket where they started. */
    val countByType: Map<SoundType, Int>,
) {
    val totalMs: Long get() = msByType.values.sum()
    val totalCount: Int get() = countByType.values.sum()
}

data class HistorySummary(
    val eventCount: Int,
    val totalMs: Long,
    val longestMs: Long,
    val countByType: Map<SoundType, Int>,
    val msByType: Map<SoundType, Long>,
)

object History {
    /** Start and end (exclusive) of [day] in epoch milliseconds. */
    fun dayRange(day: LocalDate, zone: ZoneId): LongRange =
        day.atStartOfDay(zone).toInstant().toEpochMilli() until
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun monthRange(month: YearMonth, zone: ZoneId): LongRange =
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() until
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** One bucket per hour of [day]. Usually 24, but 23 or 25 when the clocks change. */
    fun hourly(events: List<SoundEvent>, day: LocalDate, zone: ZoneId): List<HistoryBucket> {
        val edges = ArrayList<Long>()
        var cursor = day.atStartOfDay(zone)
        val end = day.plusDays(1).atStartOfDay(zone)
        while (cursor.isBefore(end)) {
            edges += cursor.toInstant().toEpochMilli()
            cursor = cursor.plusHours(1)
        }
        edges += end.toInstant().toEpochMilli()
        return bucket(events, edges)
    }

    /** One bucket per day of [month]. */
    fun daily(events: List<SoundEvent>, month: YearMonth, zone: ZoneId): List<HistoryBucket> {
        val edges = (1..month.lengthOfMonth()).map { month.atDay(it).atStartOfDay(zone).toInstant().toEpochMilli() } +
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return bucket(events, edges)
    }

    fun summarize(events: List<SoundEvent>): HistorySummary = HistorySummary(
        eventCount = events.size,
        totalMs = events.sumOf { it.durationMs },
        longestMs = events.maxOfOrNull { it.durationMs } ?: 0,
        countByType = events.groupingBy { it.type }.eachCount(),
        msByType = events.groupBy { it.type }.mapValues { (_, list) -> list.sumOf { it.durationMs } },
    )

    fun localDate(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun bucket(events: List<SoundEvent>, edges: List<Long>): List<HistoryBucket> {
        val size = edges.size - 1
        val ms = List(size) { HashMap<SoundType, Long>() }
        val counts = List(size) { HashMap<SoundType, Int>() }
        for (event in events) {
            val eventEnd = event.startedAt + event.durationMs
            for (i in 0 until size) {
                val start = edges[i]
                val end = edges[i + 1]
                if (event.startedAt in start until end) counts[i].merge(event.type, 1, Int::plus)
                val overlap = min(eventEnd, end) - max(event.startedAt, start)
                if (overlap > 0) ms[i].merge(event.type, overlap, Long::plus)
            }
        }
        return List(size) { i -> HistoryBucket(edges[i], edges[i + 1], ms[i], counts[i]) }
    }
}
