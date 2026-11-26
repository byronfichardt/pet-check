package io.github.byronfichardt.petcheck

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.data.SoundType
import io.github.byronfichardt.petcheck.monitor.MonitorService
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Boots the real application class, activity and monitor service on the JVM. This catches
 * wiring mistakes (the container, the database schema, the view model, the service lifecycle)
 * that the unit tests of individual pieces cannot.
 */
@RunWith(AndroidJUnit4::class)
// The oldest Android the app supports, a middle one and the newest.
@Config(sdk = [26, 30, 36])
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PetCheckApp>()

    /**
     * Runs suspending work off the main thread and keeps the main thread turning while it waits.
     * Blocking the main thread here (with runBlocking) can deadlock: the app's own start-up
     * coroutines may be part-way through reading settings and need that thread to finish.
     */
    private fun <T> await(block: suspend () -> T): T {
        val result = app.container.appScope.async { block() }
        compose.waitUntil(20_000) { result.isCompleted }
        return runBlocking { result.await() }
    }

    @Test
    fun everyTabOpensAndALoggedSoundShowsUp() {
        // Existence, not visibility: the simulated screen is small and can start scrolled.
        compose.onNodeWithText("Not listening").assertExists()
        compose.onNodeWithText("Start listening").assertExists()

        await {
            val settings = app.container.settings.current()
            assertTrue(settings.deviceId.isNotEmpty())
            app.container.events.upsert(
                listOf(
                    SoundEvent("e1", settings.deviceId, settings.deviceName, System.currentTimeMillis() - 60_000, 4_200, SoundType.HOWL, -12f, 0.8f),
                ),
            )
        }

        compose.onNodeWithText("Activity").performClick()
        // The row arrives from the database on a background thread.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("peak -12 dB")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Noisy time by hour").assertExists()
        compose.onNodeWithText("Month").performClick()
        compose.onNodeWithText("Noisy time by day").assertExists()

        compose.onNodeWithText("Settings").performClick()
        // This build has no google-services.json, so the app must run as a standalone monitor.
        assertNull(app.container.remote)
        compose.onNodeWithText("Remote viewing").assertExists()
        compose.onNodeWithText("Log other loud sounds").assertExists()
    }

    @Test
    fun settingsPersist() {
        val settings = await {
            val store = app.container.settings
            val id = store.current().deviceId
            store.setSensitivity(80)
            store.setDeviceName("  Kitchen tablet  ")
            store.setAlertsEnabled(true)
            store.settings.first().also { assertEquals(id, it.deviceId) }
        }
        assertEquals(80, settings.sensitivity)
        assertEquals("Kitchen tablet", settings.deviceName)
        assertEquals(12.4, settings.detectorConfig.marginDb, 1e-9)
        assertTrue(settings.alertRule.enabled)
    }

    @Test
    fun monitorServiceRefusesToRunWithoutTheMicrophonePermission() {
        val controller = Robolectric.buildService(MonitorService::class.java, Intent(app, MonitorService::class.java))
        controller.create().startCommand(0, 1)
        val state = app.container.monitorState.value
        assertFalse(state.running)
        assertEquals("Microphone permission is missing.", state.error)
        controller.destroy()
    }

    @Test
    fun monitorServiceStartsListeningAndStopsCleanly() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(MonitorService::class.java, Intent(app, MonitorService::class.java))
        controller.create().startCommand(0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(app.container.monitorState.value.running)
        assertEquals(io.github.byronfichardt.petcheck.monitor.Notifications.MONITOR_ID, shadowOf(controller.get()).lastForegroundNotificationId)

        // Let the capture thread read some (silent) audio from the simulated microphone.
        Thread.sleep(600)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(app.container.monitorState.value.running)
        assertNull(app.container.monitorState.value.error)

        controller.get().onStartCommand(Intent(app, MonitorService::class.java).setAction(MonitorService.ACTION_STOP), 0, 2)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val stopped = app.container.monitorState.value
        assertFalse(stopped.running)
        assertNull(stopped.error)
        controller.destroy()
    }
}
