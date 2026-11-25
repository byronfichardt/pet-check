package io.github.byronfichardt.petcheck

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.data.SoundType
import io.github.byronfichardt.petcheck.monitor.MonitorService
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
@Config(sdk = [36])
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PetCheckApp>()

    @Test
    fun everyTabOpensAndALoggedSoundShowsUp() {
        compose.onNodeWithText("Not listening").assertIsDisplayed()
        compose.onNodeWithText("Start listening").assertIsDisplayed()

        runBlocking {
            val settings = app.container.settings.current()
            assertTrue(settings.deviceId.isNotEmpty())
            app.container.events.upsert(
                listOf(
                    SoundEvent("e1", settings.deviceId, settings.deviceName, System.currentTimeMillis() - 60_000, 4_200, SoundType.HOWL, -12f, 0.8f),
                ),
            )
        }

        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("peak -12 dB").assertIsDisplayed()

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Noisy time by hour").assertIsDisplayed()
        compose.onNodeWithText("Month").performClick()
        compose.onNodeWithText("Noisy time by day").assertIsDisplayed()

        compose.onNodeWithText("Settings").performClick()
        // This build has no google-services.json, so the app must run as a standalone monitor.
        assertNull(app.container.remote)
        compose.onNodeWithText("Remote viewing").assertIsDisplayed()
        compose.onNodeWithText("Log other loud sounds").assertIsDisplayed()
    }

    @Test
    fun settingsPersist() = runBlocking {
        val store = app.container.settings
        val id = store.current().deviceId
        store.setSensitivity(80)
        store.setDeviceName("  Kitchen tablet  ")
        store.setAlertsEnabled(true)
        val settings = store.settings.first()
        assertEquals(id, settings.deviceId)
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
