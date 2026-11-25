package io.github.byronfichardt.petcheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.byronfichardt.petcheck.data.History
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.data.SoundType
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun ActivityRoute(viewModel: AppViewModel) {
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()
    ActivityContent(events = events, truncated = events.size >= AppViewModel.RECENT_LIMIT)
}

@Composable
fun ActivityContent(
    events: List<SoundEvent>,
    truncated: Boolean,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(),
) {
    var filterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = filterKey?.let(SoundType::fromKey)
    val presentTypes = remember(events) { SoundType.entries.filter { type -> events.any { it.type == type } } }
    val showDevice = remember(events) { events.map { it.deviceId }.distinct().size > 1 }
    val days = remember(events, filter, zone) {
        events.filter { filter == null || it.type == filter }.groupBy { History.localDate(it.startedAt, zone) }.toList()
    }

    Column(modifier.fillMaxSize()) {
        ScreenTitle("Activity", Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))

        if (events.isEmpty()) {
            EmptyState("Nothing logged yet", "Sounds appear here while a monitor is listening.")
            return@Column
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { filterKey = null }, label = { Text("All") })
            presentTypes.forEach { type ->
                FilterChip(
                    selected = filter == type,
                    onClick = { filterKey = if (filter == type) null else type.key },
                    label = { Text(type.label) },
                    leadingIcon = { TypeDot(type) },
                )
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            days.forEach { (day, dayEvents) ->
                stickyHeader(key = day.toEpochDay()) { DayHeader(day, dayEvents, today) }
                items(dayEvents, key = { it.id }) { EventRow(it, showDevice, zone) }
            }
            if (truncated) {
                item {
                    Text(
                        "Showing the latest ${events.size} sounds. Older ones are counted in History.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, events: List<SoundEvent>, today: LocalDate) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Format.day(day, today),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            "${events.size} ${if (events.size == 1) "sound" else "sounds"} · ${Format.duration(events.sumOf { it.durationMs })}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(event: SoundEvent, showDevice: Boolean, zone: ZoneId) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(event.type)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(event.type.label, style = MaterialTheme.typography.bodyLarge)
            val details = buildList {
                if (showDevice) add(event.deviceName)
                add("peak ${event.peakDb.roundToInt()} dB")
            }
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.duration(event.durationMs), style = MaterialTheme.typography.bodyLarge)
            Text(
                Format.time(event.startedAt, zone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
