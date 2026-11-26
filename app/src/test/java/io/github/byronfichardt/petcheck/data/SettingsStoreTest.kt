package io.github.byronfichardt.petcheck.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings on their own, with a plain application so nothing else is reading the store. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SettingsStoreTest {
    private val store = SettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun theDeviceIdIsCreatedOnceAndKept() = runBlocking {
        val first = store.current().deviceId
        assertTrue(first.isNotEmpty())
        assertEquals(first, store.current().deviceId)
        assertEquals(first, store.settings.first().deviceId)
    }

    @Test
    fun changesPersistAndAreClamped() = runBlocking {
        store.setSensitivity(80)
        store.setDeviceName("  Kitchen tablet  ")
        store.setLogOtherSounds(false)
        store.setAlertsEnabled(true)
        store.setAlertNoisySeconds(1)
        store.setAlertWindowMinutes(100_000)

        val settings = store.settings.first()
        assertEquals(80, settings.sensitivity)
        assertEquals("Kitchen tablet", settings.deviceName)
        assertFalse(settings.logOtherSounds)
        assertEquals(12.4, settings.detectorConfig.marginDb, 1e-9)
        assertTrue(settings.alertRule.enabled)
        assertEquals(5, settings.alertRule.noisySeconds)
        assertEquals(240, settings.alertRule.windowMinutes)

        store.setSensitivity(500)
        assertEquals(100, store.settings.first().sensitivity)
    }
}
