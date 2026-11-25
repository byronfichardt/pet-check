package io.github.byronfichardt.petcheck.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.byronfichardt.petcheck.data.History
import io.github.byronfichardt.petcheck.data.Settings
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.data.SoundType
import io.github.byronfichardt.petcheck.monitor.MonitorState
import io.github.byronfichardt.petcheck.remote.Account
import io.github.byronfichardt.petcheck.remote.RemoteDevice
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen with sample data and saves it under app/build/outputs/screenshots.
 * It doubles as a smoke test: every screen has to compose and draw without throwing.
 * The images in docs/screenshots are copied from here.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 25)
    private val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = dayStart + 15 * HOUR + 40 * MINUTE

    /** A plausible day: a restless morning, a howling spell after lunch, a few odd noises. */
    private val events: List<SoundEvent> = buildList {
        var id = 0
        fun add(atMs: Long, durationMs: Long, type: SoundType, device: String = "pi", name: String = "Living room Pi") {
            add(SoundEvent("e${id++}", device, name, dayStart + atMs, durationMs, type, -9f - id % 14, 0.82f, synced = true))
        }
        for (i in 0 until 9) add(8 * HOUR + 5 * MINUTE + i * 4 * MINUTE, 6_000L + i * 2_500, SoundType.BARK)
        add(8 * HOUR + 50 * MINUTE, 22_000, SoundType.WHINE)
        add(9 * HOUR + 12 * MINUTE, 3_000, SoundType.OTHER)
        for (i in 0 until 5) add(10 * HOUR + 20 * MINUTE + i * 7 * MINUTE, 14_000L + i * 3_000, SoundType.BARK)
        add(11 * HOUR + 2 * MINUTE, 9_000, SoundType.GROWL)
        for (i in 0 until 6) add(13 * HOUR + 15 * MINUTE + i * 6 * MINUTE, 35_000L + i * 9_000, SoundType.HOWL)
        add(13 * HOUR + 58 * MINUTE, 48_000, SoundType.WHINE)
        for (i in 0 until 4) add(14 * HOUR + 30 * MINUTE + i * 5 * MINUTE, 8_000L + i * 1_000, SoundType.BARK)
        add(14 * HOUR + 52 * MINUTE, 2_000, SoundType.OTHER)
        add(15 * HOUR + 5 * MINUTE, 5_000, SoundType.MEOW, device = "tablet", name = "Kitchen tablet")
        for (i in 0 until 3) add(15 * HOUR + 20 * MINUTE + i * 3 * MINUTE, 11_000, SoundType.BARK)
    }.sortedByDescending { it.startedAt }

    private val devices = listOf(
        RemoteDevice("pi", "Living room Pi", "pi", monitoring = true, monitoringSinceMs = dayStart + 7 * HOUR, lastSeenMs = now - 3 * MINUTE, isThisDevice = false),
        RemoteDevice("tablet", "Kitchen tablet", "android", monitoring = false, monitoringSinceMs = null, lastSeenMs = now - 2 * HOUR, isThisDevice = false),
    )

    private fun capture(name: String, dark: Boolean = false, tab: Tab, content: @Composable () -> Unit) {
        compose.setContent {
            PetCheckTheme(darkTheme = dark) { PetCheckScaffold(tab, onTabChange = {}, content = content) }
        }
        compose.onRoot().captureRoboImage("build/outputs/screenshots/$name.png")
    }

    @Composable
    private fun Monitor(state: MonitorState, batteryRestricted: Boolean = false) = MonitorContent(
        monitor = state,
        sensitivity = 50,
        today = History.summarize(events),
        otherDevices = devices,
        showSignInHint = false,
        batteryRestricted = batteryRestricted,
        micDenied = false,
        onStart = {},
        onStop = {},
        onSensitivityChange = {},
        onAllowBackground = {},
        onOpenAppSettings = {},
        onDismissError = {},
        nowMs = now,
    )

    private val listening = MonitorState(
        running = true,
        startedAtMs = dayStart + 8 * HOUR,
        levelDb = -31f,
        thresholdDb = -44f,
        hearing = false,
        eventsThisSession = 14,
    )

    @Test
    fun monitorListening() = capture("monitor-listening", tab = Tab.MONITOR) { Monitor(listening) }

    @Test
    fun monitorListeningDark() = capture("monitor-listening-dark", dark = true, tab = Tab.MONITOR) { Monitor(listening) }

    @Test
    fun monitorIdleWithNotices() = capture("monitor-idle", tab = Tab.MONITOR) {
        Monitor(MonitorState(error = "Android stopped Pet Check in the background."), batteryRestricted = true)
    }

    @Test
    fun activity() = capture("activity", tab = Tab.ACTIVITY) {
        ActivityContent(events, truncated = false, zone = zone, today = today)
    }

    @Test
    fun activityEmpty() = capture("activity-empty", tab = Tab.ACTIVITY) {
        ActivityContent(emptyList(), truncated = false, zone = zone, today = today)
    }

    @Composable
    private fun HistoryDay(metric: HistoryMetric = HistoryMetric.TIME) = HistoryContent(
        mode = HistoryMode.DAY,
        metric = metric,
        periodLabel = "Today",
        canGoForward = false,
        buckets = History.hourly(events, today, zone),
        summary = History.summarize(events),
        onModeChange = {},
        onMetricChange = {},
        onStep = {},
        zone = zone,
    )

    @Test
    fun historyDay() = capture("history-day", tab = Tab.HISTORY) { HistoryDay() }

    @Test
    fun historyDayDark() = capture("history-day-dark", dark = true, tab = Tab.HISTORY) { HistoryDay(HistoryMetric.COUNT) }

    @Test
    fun historyMonth() = capture("history-month", tab = Tab.HISTORY) {
        // Spread the sample day across the month with varying amounts.
        val month = YearMonth.from(today)
        val monthEvents = (1..today.dayOfMonth).flatMap { dayOfMonth ->
            val shift = (dayOfMonth - today.dayOfMonth) * 24 * HOUR
            events.filterIndexed { index, _ -> (index + dayOfMonth) % (1 + dayOfMonth % 4) == 0 }
                .map { it.copy(id = "${it.id}-$dayOfMonth", startedAt = it.startedAt + shift) }
        }
        HistoryContent(
            mode = HistoryMode.MONTH,
            metric = HistoryMetric.TIME,
            periodLabel = "November 2026",
            canGoForward = false,
            buckets = History.daily(monthEvents, month, zone),
            summary = History.summarize(monthEvents),
            onModeChange = {},
            onMetricChange = {},
            onStep = {},
            zone = zone,
        )
    }

    @Test
    fun historyEmpty() = capture("history-empty", tab = Tab.HISTORY) {
        HistoryContent(
            mode = HistoryMode.DAY,
            metric = HistoryMetric.TIME,
            periodLabel = "Yesterday",
            canGoForward = true,
            buckets = History.hourly(emptyList(), today, zone),
            summary = History.summarize(emptyList()),
            onModeChange = {},
            onMetricChange = {},
            onStep = {},
            zone = zone,
        )
    }

    @Composable
    private fun SettingsScreen(account: Account?, remoteAvailable: Boolean = true, settings: Settings = Settings(deviceName = "Pixel 8")) =
        SettingsContent(
            settings = settings,
            remoteAvailable = remoteAvailable,
            googleSignInAvailable = true,
            account = account,
            signIn = SignInState(),
            onSignInWithEmail = { _, _, _ -> },
            onSignInWithGoogle = {},
            onSignOut = {},
            onDeviceName = {},
            onLogOtherSounds = {},
            onAlertsEnabled = {},
            onAlertNoisySeconds = {},
            onAlertWindowMinutes = {},
            onAlertIncludeOther = {},
            onClearHistory = {},
        )

    @Test
    fun settingsSignedOut() = capture("settings-signed-out", tab = Tab.SETTINGS) { SettingsScreen(account = null) }

    @Test
    fun settingsSignedIn() = capture("settings-signed-in", tab = Tab.SETTINGS) {
        SettingsScreen(Account("uid", "you@example.com"), settings = Settings(deviceName = "Pixel 8", alertsEnabled = true))
    }

    @Test
    fun settingsStandalone() = capture("settings-standalone", dark = true, tab = Tab.SETTINGS) {
        SettingsScreen(account = null, remoteAvailable = false)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
