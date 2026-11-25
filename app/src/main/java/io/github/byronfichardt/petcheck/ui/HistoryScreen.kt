package io.github.byronfichardt.petcheck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.byronfichardt.petcheck.data.History
import io.github.byronfichardt.petcheck.data.HistoryBucket
import io.github.byronfichardt.petcheck.data.HistorySummary
import io.github.byronfichardt.petcheck.data.SoundType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

enum class HistoryMode { DAY, MONTH }

enum class HistoryMetric { TIME, COUNT }

@Composable
fun HistoryRoute(viewModel: AppViewModel) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    var mode by rememberSaveable { mutableStateOf(HistoryMode.DAY) }
    var metric by rememberSaveable { mutableStateOf(HistoryMetric.TIME) }
    var epochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(epochDay)
    val month = YearMonth.from(day)

    val range = if (mode == HistoryMode.DAY) History.dayRange(day, zone) else History.monthRange(month, zone)
    val events by remember(range) { viewModel.eventsBetween(range) }.collectAsStateWithLifecycle(emptyList())
    val buckets = remember(events, mode, day) {
        if (mode == HistoryMode.DAY) History.hourly(events, day, zone) else History.daily(events, month, zone)
    }

    HistoryContent(
        mode = mode,
        metric = metric,
        periodLabel = if (mode == HistoryMode.DAY) Format.day(day, today) else Format.month(month),
        canGoForward = if (mode == HistoryMode.DAY) day < today else month < YearMonth.from(today),
        buckets = buckets,
        summary = History.summarize(events),
        onModeChange = { mode = it },
        onMetricChange = { metric = it },
        onStep = { step ->
            val next = if (mode == HistoryMode.DAY) day.plusDays(step.toLong()) else day.withDayOfMonth(1).plusMonths(step.toLong())
            epochDay = min(next.toEpochDay(), today.toEpochDay())
        },
        zone = zone,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryContent(
    mode: HistoryMode,
    metric: HistoryMetric,
    periodLabel: String,
    canGoForward: Boolean,
    buckets: List<HistoryBucket>,
    summary: HistorySummary,
    onModeChange: (HistoryMode) -> Unit,
    onMetricChange: (HistoryMetric) -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var selected by remember(buckets.firstOrNull()?.startMs, mode) { mutableStateOf<Int?>(null) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("History")

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HistoryMode.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { onModeChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(index, HistoryMode.entries.size),
                ) { Text(if (item == HistoryMode.DAY) "Day" else "Month") }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier")
            }
            Text(
                periodLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onStep(1) }, enabled = canGoForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later")
            }
        }

        Panel { StatRow(summary.eventCount, summary.totalMs, summary.longestMs) }

        Panel(
            title = when (metric) {
                HistoryMetric.TIME -> if (mode == HistoryMode.DAY) "Noisy time by hour" else "Noisy time by day"
                HistoryMetric.COUNT -> if (mode == HistoryMode.DAY) "Sounds by hour" else "Sounds by day"
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(metric == HistoryMetric.TIME, { onMetricChange(HistoryMetric.TIME) }, label = { Text("Noisy time") })
                FilterChip(metric == HistoryMetric.COUNT, { onMetricChange(HistoryMetric.COUNT) }, label = { Text("Sounds") })
            }

            if (summary.eventCount == 0) {
                EmptyState("Nothing logged", "No sounds were heard in this period.")
            } else {
                Text(
                    selected?.let { buckets.getOrNull(it) }?.let { bucketReadout(it, mode, metric, zone) }
                        ?: "Tap a bar for details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                )
                StackedBarChart(buckets, mode, metric, selected, onSelect = { selected = if (selected == it) null else it }, zone = zone)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoundType.entries.forEach { type ->
                        if ((summary.countByType[type] ?: 0) > 0) TypeLegendItem(type)
                    }
                }
            }
        }
    }
}

private fun bucketValue(bucket: HistoryBucket, type: SoundType, metric: HistoryMetric): Double = when (metric) {
    HistoryMetric.TIME -> (bucket.msByType[type] ?: 0L) / 1000.0
    HistoryMetric.COUNT -> (bucket.countByType[type] ?: 0).toDouble()
}

private fun bucketLabel(bucket: HistoryBucket, mode: HistoryMode, zone: ZoneId): String = when (mode) {
    HistoryMode.DAY -> "${Format.shortTime(bucket.startMs, zone)} to ${Format.shortTime(bucket.endMs, zone)}"
    HistoryMode.MONTH -> Format.day(Instant.ofEpochMilli(bucket.startMs).atZone(zone).toLocalDate())
}

private fun bucketReadout(bucket: HistoryBucket, mode: HistoryMode, metric: HistoryMetric, zone: ZoneId): String {
    val label = bucketLabel(bucket, mode, zone)
    val parts = SoundType.entries.mapNotNull { type ->
        when (metric) {
            HistoryMetric.TIME -> bucket.msByType[type]?.takeIf { it > 0 }?.let { "${type.label} ${Format.duration(it)}" }
            HistoryMetric.COUNT -> bucket.countByType[type]?.takeIf { it > 0 }?.let { "${type.label} $it" }
        }
    }
    val total = when (metric) {
        HistoryMetric.TIME -> Format.duration(bucket.totalMs)
        HistoryMetric.COUNT -> "${bucket.totalCount} ${if (bucket.totalCount == 1) "sound" else "sounds"}"
    }
    return if (parts.isEmpty()) "$label: nothing logged." else "$label: $total.\n${parts.joinToString(", ")}"
}

/** Round axis maximum at or above [value], and how to label a tick on that axis. */
internal fun axisMax(value: Double, metric: HistoryMetric): Double {
    val steps = when (metric) {
        HistoryMetric.TIME -> listOf(10, 20, 30, 60, 120, 300, 600, 1200, 1800, 3600, 7200, 14400, 28800, 43200, 86400)
        HistoryMetric.COUNT -> listOf(4, 10, 20, 40, 100, 200, 400, 1000, 2000, 4000, 10000)
    }
    return (steps.firstOrNull { it >= value } ?: steps.last()).toDouble()
}

internal fun axisLabel(value: Double, axisMax: Double, metric: HistoryMetric): String = when {
    metric == HistoryMetric.COUNT -> value.toInt().toString()
    value == 0.0 -> "0"
    axisMax >= 7200 -> "${trimNumber(value / 3600)} h"
    axisMax >= 120 -> "${trimNumber(value / 60)} min"
    else -> "${value.toInt()} s"
}

private fun trimNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

/**
 * Stacked bars, one per bucket. Segments are separated by a small gap in the surface colour and
 * only the top of each bar is rounded. Tapping a bar selects it; the readout above carries the numbers.
 */
@Composable
private fun StackedBarChart(
    buckets: List<HistoryBucket>,
    mode: HistoryMode,
    metric: HistoryMetric,
    selected: Int?,
    onSelect: (Int) -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSoundColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val totals = buckets.map { bucket -> SoundType.entries.sumOf { bucketValue(bucket, it, metric) } }
    val top = axisMax(totals.maxOrNull() ?: 0.0, metric)
    val busiest = totals.indices.maxByOrNull { totals[it] }
    val description = buildString {
        append(if (metric == HistoryMetric.TIME) "Chart of noisy time" else "Chart of sounds")
        append(if (mode == HistoryMode.DAY) " by hour." else " by day.")
        if (busiest != null && totals[busiest] > 0) append(" Busiest: ${bucketLabel(buckets[busiest], mode, zone)}.")
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(buckets.size) {
                detectTapGestures { offset ->
                    val gutter = 44.dp.toPx()
                    val slot = (size.width - gutter) / buckets.size
                    val index = ((offset.x - gutter) / slot).toInt()
                    if (offset.x >= gutter && index in buckets.indices) onSelect(index)
                }
            },
    ) {
        val gutter = 44.dp.toPx()
        val bottomGutter = 20.dp.toPx()
        val topPad = 8.dp.toPx()
        val plotHeight = size.height - bottomGutter - topPad
        val plotWidth = size.width - gutter
        val baseline = topPad + plotHeight
        val slot = plotWidth / buckets.size
        val barWidth = min(24.dp.toPx(), max(3.dp.toPx(), slot * 0.68f))
        val gap = 2.dp.toPx()
        val corner = min(4.dp.toPx(), barWidth / 2)

        // Recessive grid: three hairlines with their values in the gutter.
        for (i in 0..2) {
            val value = top * i / 2
            val y = baseline - plotHeight * i / 2
            drawLine(scheme.outlineVariant, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1f)
            val text = measurer.measure(axisLabel(value, top, metric), labelStyle)
            drawText(text, topLeft = Offset(gutter - text.size.width - 6.dp.toPx(), y - text.size.height / 2))
        }

        buckets.forEachIndexed { index, bucket ->
            val centre = gutter + slot * index + slot / 2
            val dim = selected != null && selected != index
            val segments = SoundColors.STACK_ORDER.mapNotNull { type ->
                val value = bucketValue(bucket, type, metric)
                if (value > 0) type to max(2.dp.toPx(), (value / top * plotHeight).toFloat()) else null
            }
            var y = baseline
            segments.forEachIndexed { position, (type, rawHeight) ->
                val isTop = position == segments.lastIndex
                val height = if (isTop) rawHeight else max(1f, rawHeight - gap)
                val rect = Rect(centre - barWidth / 2, y - height, centre + barWidth / 2, y)
                val radius = if (isTop) CornerRadius(min(corner, height)) else CornerRadius.Zero
                val path = Path().apply { addRoundRect(RoundRect(rect, topLeft = radius, topRight = radius)) }
                drawPath(path, colors[type].copy(alpha = if (dim) 0.35f else 1f))
                y -= rawHeight
            }
            if (selected == index) {
                drawRect(
                    scheme.onSurface.copy(alpha = 0.06f),
                    topLeft = Offset(centre - slot / 2, topPad),
                    size = Size(slot, plotHeight),
                )
            }
        }

        // A few evenly spaced labels: every sixth hour, or every seventh day.
        val every = if (mode == HistoryMode.DAY) 6 else 7
        for (index in buckets.indices step every) {
            val label = if (mode == HistoryMode.DAY) {
                "%02d".format(Instant.ofEpochMilli(buckets[index].startMs).atZone(zone).hour)
            } else {
                (index + 1).toString()
            }
            val text = measurer.measure(label, labelStyle)
            val centre = gutter + slot * index + slot / 2
            drawText(text, topLeft = Offset(centre - text.size.width / 2, baseline + 4.dp.toPx()))
        }
    }
}
