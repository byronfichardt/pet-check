package io.github.byronfichardt.petcheck.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.byronfichardt.petcheck.R
import io.github.byronfichardt.petcheck.data.History
import io.github.byronfichardt.petcheck.data.HistorySummary
import io.github.byronfichardt.petcheck.data.SoundType
import io.github.byronfichardt.petcheck.monitor.MonitorService
import io.github.byronfichardt.petcheck.monitor.MonitorState
import io.github.byronfichardt.petcheck.remote.RemoteDevice
import java.time.LocalDate
import java.time.ZoneId

/** A monitor reports in every 10 minutes: after this long without contact it is treated as lost. */
private const val STALE_AFTER_MS = 25 * 60_000L

@Composable
fun MonitorRoute(viewModel: AppViewModel) {
    val context = LocalContext.current
    val monitor by viewModel.monitor.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()

    val today = LocalDate.now()
    val todayEvents by remember(today) { viewModel.eventsBetween(History.dayRange(today, ZoneId.systemDefault())) }
        .collectAsStateWithLifecycle(emptyList())

    var micDenied by remember { mutableStateOf(false) }
    var batteryRestricted by remember { mutableStateOf(isBatteryRestricted(context)) }
    LifecycleResumeEffect(Unit) {
        batteryRestricted = isBatteryRestricted(context)
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) micDenied = false
        onPauseOrDispose { }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Notifications are optional: monitoring works without them, only less visibly.
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            MonitorService.start(context)
        } else {
            micDenied = true
        }
    }

    MonitorContent(
        monitor = monitor,
        sensitivity = settings.sensitivity,
        today = History.summarize(todayEvents),
        otherDevices = devices.filterNot { it.isThisDevice },
        showSignInHint = viewModel.remoteAvailable && account == null,
        batteryRestricted = batteryRestricted,
        micDenied = micDenied,
        onStart = {
            viewModel.clearMonitorError()
            val missing = buildList {
                if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (missing.isEmpty()) MonitorService.start(context) else permissions.launch(missing.toTypedArray())
        },
        onStop = { MonitorService.stop(context) },
        onSensitivityChange = viewModel::setSensitivity,
        onAllowBackground = { requestBatteryExemption(context) },
        onOpenAppSettings = { openAppSettings(context) },
        onDismissError = viewModel::clearMonitorError,
    )
}

@Composable
fun MonitorContent(
    monitor: MonitorState,
    sensitivity: Int,
    today: HistorySummary,
    otherDevices: List<RemoteDevice>,
    showSignInHint: Boolean,
    batteryRestricted: Boolean,
    micDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSensitivityChange: (Int) -> Unit,
    onAllowBackground: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Monitor")

        monitor.error?.let { error ->
            Notice("Monitoring stopped. $error", serious = true, actionLabel = "Dismiss", onAction = onDismissError)
        }
        if (micDenied) {
            Notice(
                "Pet Check needs the microphone to listen. Allow it in Android settings.",
                serious = true,
                actionLabel = "Open settings",
                onAction = onOpenAppSettings,
            )
        }
        if (monitor.running && monitor.micSilenced) {
            Notice("Another app is using the microphone. Nothing can be heard until it lets go.", serious = true)
        }
        if (monitor.running && !monitor.classifierReady) {
            Notice("Sound recognition could not start, so every sound is being logged as Other.")
        }

        StatusPanel(monitor, onStart, onStop)

        if (batteryRestricted) {
            Notice(
                "Android may stop Pet Check when the screen is off. Allow it to keep running in the background.",
                actionLabel = "Allow",
                onAction = onAllowBackground,
            )
        }

        Panel(title = "Today") {
            StatRow(today.eventCount, today.totalMs, today.longestMs)
            TypeBreakdown(today)
        }

        SensitivityPanel(sensitivity, onSensitivityChange)

        if (otherDevices.isNotEmpty()) {
            Panel(title = "Your other devices") {
                otherDevices.forEach { DeviceRow(it, nowMs) }
            }
        } else if (showSignInHint) {
            Text(
                "Sign in under Settings to check on this monitor from another device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusPanel(monitor: MonitorState, onStart: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Panel(color = if (monitor.running) scheme.primaryContainer else scheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp).background(if (monitor.running) scheme.primary else scheme.outline, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (monitor.running) "Listening" else "Not listening",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (monitor.running) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
        Text(
            when {
                !monitor.running -> "Leave this device near your pet, plugged in, and start listening."
                monitor.hearing -> "Hearing something now…"
                else -> "Since ${Format.shortTime(monitor.startedAtMs)}. ${soundsLogged(monitor.eventsThisSession)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (monitor.running) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
        if (monitor.running) {
            LevelMeter(monitor.levelDb, monitor.thresholdDb)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop listening") }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start listening")
            }
        }
    }
}

private fun soundsLogged(count: Int) = when (count) {
    0 -> "No sounds logged yet."
    1 -> "1 sound logged."
    else -> "$count sounds logged."
}

/** Live loudness with a marker at the level a sound has to reach to be logged. */
@Composable
fun LevelMeter(levelDb: Float, thresholdDb: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val level by animateFloatAsState(meterFraction(levelDb), tween(120), label = "level")
    val threshold = meterFraction(thresholdDb)
    val loud = levelDb >= thresholdDb
    Column(modifier.clearAndSetSemantics { contentDescription = if (loud) "Sound level: loud" else "Sound level: quiet" }) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val barHeight = 10.dp.toPx()
            val top = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(scheme.surface.copy(alpha = 0.7f), Offset(0f, top), Size(size.width, barHeight), radius)
            if (level > 0f) {
                drawRoundRect(scheme.primary, Offset(0f, top), Size(size.width * level, barHeight), radius)
            }
            val x = size.width * threshold
            drawLine(scheme.onPrimaryContainer, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Quiet", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer)
            Text("The line marks what gets logged", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer)
            Text("Loud", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer)
        }
    }
}

/** Maps -80..0 dBFS onto the meter's 0..1. */
private fun meterFraction(db: Float): Float = ((db + 80f) / 80f).coerceIn(0f, 1f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeBreakdown(summary: HistorySummary) {
    if (summary.eventCount == 0) {
        Text(
            "Nothing logged today.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SoundType.entries.forEach { type ->
            val count = summary.countByType[type] ?: 0
            if (count > 0) TypeLegendItem(type, detail = count.toString())
        }
    }
}

@Composable
private fun SensitivityPanel(sensitivity: Int, onChange: (Int) -> Unit) {
    val state = remember { SliderState(sensitivity.toFloat(), 0, 0f..100f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(sensitivity) { if (!dragging) state.value = sensitivity.toFloat() }
    Panel(title = "Sensitivity") {
        Slider(
            state = state,
            onValueChange = {
                dragging = true
                state.value = it
            },
            onValueChangeFinished = {
                dragging = false
                onChange(state.value.toInt())
            },
            modifier = Modifier.semantics { contentDescription = "Sensitivity" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Loud sounds only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Quiet sounds too", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "The level adjusts itself to the room's background noise. Raise the sensitivity if barks are missed, lower it if too much is logged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(device: RemoteDevice, nowMs: Long) {
    val scheme = MaterialTheme.colorScheme
    val lastSeen = device.lastSeenMs
    val stale = lastSeen == null || nowMs - lastSeen > STALE_AFTER_MS
    val (status, live) = when {
        device.monitoring && !stale -> "Listening" to true
        device.monitoring -> "No contact since ${lastSeen?.let { Format.ago(it, nowMs) } ?: "it started"}" to false
        else -> "Not listening" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (live) scheme.primary else scheme.outline, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (live && lastSeen != null) {
            Text("seen ${Format.ago(lastSeen, nowMs)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isBatteryRestricted(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return !power.isIgnoringBatteryOptimizations(context.packageName)
}

// Asking directly is appropriate here: a monitor's whole job is to run for hours with the screen off.
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(direct) }
        .onFailure { runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
}
