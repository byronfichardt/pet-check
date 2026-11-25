package io.github.byronfichardt.petcheck.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.byronfichardt.petcheck.audio.DetectorConfig
import io.github.byronfichardt.petcheck.monitor.AlertRule
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val deviceId: String = "",
    val deviceName: String = "",
    /** 0 (only very loud sounds) to 100 (anything above the room's background noise). */
    val sensitivity: Int = 50,
    /** Keep loud sounds that were not recognised as a pet. */
    val logOtherSounds: Boolean = true,
    val alertsEnabled: Boolean = false,
    val alertNoisySeconds: Int = 60,
    val alertWindowMinutes: Int = 10,
    val alertIncludeOther: Boolean = false,
    /** Server time of the newest remote event already copied to this device. */
    val remoteCursorMs: Long = 0,
) {
    val detectorConfig: DetectorConfig
        get() = DetectorConfig(marginDb = DetectorConfig.marginForSensitivity(sensitivity))

    val alertRule: AlertRule
        get() = AlertRule(
            enabled = alertsEnabled,
            noisySeconds = alertNoisySeconds,
            windowMinutes = alertWindowMinutes,
            includeOther = alertIncludeOther,
        )
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val defaultName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"

    val settings: Flow<Settings> = store.data.map { prefs ->
        Settings(
            deviceId = prefs[DEVICE_ID].orEmpty(),
            deviceName = prefs[DEVICE_NAME] ?: defaultName,
            sensitivity = prefs[SENSITIVITY] ?: 50,
            logOtherSounds = prefs[LOG_OTHER] ?: true,
            alertsEnabled = prefs[ALERTS_ENABLED] ?: false,
            alertNoisySeconds = prefs[ALERT_SECONDS] ?: 60,
            alertWindowMinutes = prefs[ALERT_WINDOW] ?: 10,
            alertIncludeOther = prefs[ALERT_INCLUDE_OTHER] ?: false,
            remoteCursorMs = prefs[REMOTE_CURSOR] ?: 0,
        )
    }

    /** Current settings, creating this device's id on first use. */
    suspend fun current(): Settings {
        val now = settings.first()
        if (now.deviceId.isNotEmpty()) return now
        store.edit { prefs -> if (prefs[DEVICE_ID].isNullOrEmpty()) prefs[DEVICE_ID] = UUID.randomUUID().toString() }
        return settings.first()
    }

    suspend fun setDeviceName(name: String) = store.edit { it[DEVICE_NAME] = name.trim().take(MAX_NAME_LENGTH) }

    suspend fun setSensitivity(value: Int) = store.edit { it[SENSITIVITY] = value.coerceIn(0, 100) }

    suspend fun setLogOtherSounds(value: Boolean) = store.edit { it[LOG_OTHER] = value }

    suspend fun setAlertsEnabled(value: Boolean) = store.edit { it[ALERTS_ENABLED] = value }

    suspend fun setAlertNoisySeconds(value: Int) = store.edit { it[ALERT_SECONDS] = value.coerceIn(5, 3600) }

    suspend fun setAlertWindowMinutes(value: Int) = store.edit { it[ALERT_WINDOW] = value.coerceIn(1, 240) }

    suspend fun setAlertIncludeOther(value: Boolean) = store.edit { it[ALERT_INCLUDE_OTHER] = value }

    suspend fun setRemoteCursor(valueMs: Long) = store.edit { it[REMOTE_CURSOR] = valueMs }

    companion object {
        const val MAX_NAME_LENGTH = 40

        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val SENSITIVITY = intPreferencesKey("sensitivity")
        private val LOG_OTHER = booleanPreferencesKey("log_other_sounds")
        private val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        private val ALERT_SECONDS = intPreferencesKey("alert_noisy_seconds")
        private val ALERT_WINDOW = intPreferencesKey("alert_window_minutes")
        private val ALERT_INCLUDE_OTHER = booleanPreferencesKey("alert_include_other")
        private val REMOTE_CURSOR = longPreferencesKey("remote_cursor_ms")
    }
}
