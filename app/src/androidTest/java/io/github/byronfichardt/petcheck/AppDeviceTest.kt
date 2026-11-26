package io.github.byronfichardt.petcheck

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.data.SoundType
import io.github.byronfichardt.petcheck.monitor.MonitorState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real app on a device or emulator: the UI, the database and the listening service. */
@RunWith(AndroidJUnit4::class)
class AppDeviceTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (compose.activity.application as PetCheckApp).container
    private val state: MonitorState get() = container.monitorState.value

    @After
    fun stopListening() {
        if (state.running) io.github.byronfichardt.petcheck.monitor.MonitorService.stop(compose.activity)
    }

    @Test
    fun everyTabOpensAndALoggedSoundShowsUp() {
        compose.onNodeWithText("Not listening").assertExists()
        runBlocking {
            val settings = container.settings.current()
            container.events.upsert(
                listOf(
                    SoundEvent("device-test", settings.deviceId, settings.deviceName, System.currentTimeMillis() - 60_000, 4_200, SoundType.HOWL, -12f, 0.8f),
                ),
            )
        }
        compose.onNodeWithText("Activity").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTextCount("peak -12 dB") > 0 }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Noisy time by hour").assertExists()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Remote viewing").assertExists()
    }

    @Test
    fun listeningStartsWithTheRealMicrophoneAndModelAndStopsCleanly() {
        compose.onNodeWithText("Start listening").performScrollTo().performClick()

        // The service is running, the microphone opened and audio frames are flowing.
        compose.waitUntil(TIMEOUT_MS) { state.running && state.framesHeard > 20 }
        assertNull("monitor error: ${state.error}", state.error)
        assertTrue("the sound model failed to load on this device", state.classifierReady)
        compose.onNodeWithText("Listening").assertExists()

        // Keep listening a little longer: a capture loop that dies shows up as an error here.
        val before = state.framesHeard
        compose.waitUntil(TIMEOUT_MS) { state.framesHeard > before + 40 || !state.running }
        assertTrue("listening stopped by itself: ${state.error}", state.running)

        compose.onNodeWithText("Stop listening").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { !state.running }
        assertNull(state.error)
        compose.onNodeWithText("Not listening").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
