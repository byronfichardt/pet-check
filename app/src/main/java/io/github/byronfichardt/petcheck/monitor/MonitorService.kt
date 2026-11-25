package io.github.byronfichardt.petcheck.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.byronfichardt.petcheck.AppContainer
import io.github.byronfichardt.petcheck.PetCheckApp
import io.github.byronfichardt.petcheck.audio.FinishedEvent
import io.github.byronfichardt.petcheck.audio.SILENCE_DB
import io.github.byronfichardt.petcheck.data.Settings
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.data.SoundType
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the monitor is doing right now, for the UI. */
data class MonitorState(
    val running: Boolean = false,
    val startedAtMs: Long = 0,
    val levelDb: Float = SILENCE_DB.toFloat(),
    val thresholdDb: Float = -50f,
    /** A sound event is in progress. */
    val hearing: Boolean = false,
    /** Another app has taken the microphone, so only silence is arriving. */
    val micSilenced: Boolean = false,
    /** False if the sound classifier failed to load: events are then all stored as "other". */
    val classifierReady: Boolean = true,
    val eventsThisSession: Int = 0,
    val error: String? = null,
)

/**
 * Foreground service that listens through the microphone and logs sound events.
 * It must be started while the app is visible: Android does not allow a microphone
 * service to start from the background.
 */
class MonitorService : Service(), MonitorEngine.Listener {
    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val alertEvaluator = AlertEvaluator()
    @Volatile private var engine: MonitorEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var frameCount = 0

    @Volatile private var settings = Settings()

    override fun onCreate() {
        super.onCreate()
        container = (application as PetCheckApp).container
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(null)
            return START_NOT_STICKY
        }
        if (engine != null) return START_STICKY

        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, Notifications.MONITOR_ID, container.notifications.monitoring(0), type)
        } catch (e: Exception) {
            // The system restarted the service while the app was in the background, where
            // microphone access is not allowed. Ask the user to bring the app back.
            Log.w(TAG, "Could not enter the foreground", e)
            fail("Android stopped Pet Check in the background.")
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is missing.")
            return START_NOT_STICKY
        }

        val newEngine = MonitorEngine(applicationContext, this)
        engine = newEngine
        acquireWakeLock()
        container.notifications.clearStopped()
        container.monitorState.value = MonitorState(running = true, startedAtMs = System.currentTimeMillis())

        scope.launch {
            settings = container.settings.current()
            newEngine.start(settings.detectorConfig)
            launch {
                container.settings.settings.collect { latest ->
                    if (latest.deviceId.isEmpty()) return@collect
                    if (latest.sensitivity != settings.sensitivity) newEngine.updateConfig(latest.detectorConfig)
                    settings = latest
                }
            }
            // Heartbeat, so a viewer can tell a working monitor from one that has died.
            while (isActive) {
                container.remote?.reportMonitoring(true)
                delay(HEARTBEAT_MS)
            }
        }
        return START_STICKY
    }

    override fun onLevel(levelDb: Double, thresholdDb: Double, active: Boolean, micSilenced: Boolean) {
        // Called 20 times a second on the capture thread: publish every other frame.
        if (++frameCount % 2 != 0) return
        container.monitorState.update {
            it.copy(
                levelDb = levelDb.toFloat(),
                thresholdDb = thresholdDb.toFloat(),
                hearing = active,
                micSilenced = micSilenced,
                classifierReady = engine?.classifierReady ?: it.classifierReady,
            )
        }
    }

    override fun onEvent(event: FinishedEvent) {
        val current = settings
        if (current.deviceId.isEmpty()) return
        if (event.type == SoundType.OTHER && !current.logOtherSounds) return
        val row = SoundEvent(
            id = UUID.randomUUID().toString(),
            deviceId = current.deviceId,
            deviceName = current.deviceName,
            startedAt = event.startMs,
            durationMs = event.durationMs,
            type = event.type,
            peakDb = event.peakDb.toFloat(),
            confidence = event.confidence,
        )
        val trigger = synchronized(alertEvaluator) {
            alertEvaluator.onEvent(current.alertRule, event.startMs, event.durationMs, event.type)
        }
        // Written on the app scope so the last event survives the service being destroyed.
        container.appScope.launch {
            container.events.upsert(listOf(row))
            if (trigger != null) container.remote?.sendAlert(trigger)
        }
        val count = container.monitorState.updateAndGet { it.copy(eventsThisSession = it.eventsThisSession + 1) }.eventsThisSession
        container.notifications.updateMonitoring(count)
    }

    override fun onStopped(error: String?) {
        // A normal stop is already being handled by shutdown().
        if (error != null) scope.launch { shutdown(error) }
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.stop()
        engine = null
        releaseWakeLock()
        container.monitorState.update { it.copy(running = false, hearing = false) }
        super.onDestroy()
    }

    private fun shutdown(error: String?) {
        val wasRunning = engine != null
        engine?.stop()
        engine = null
        releaseWakeLock()
        container.monitorState.update {
            it.copy(running = false, hearing = false, levelDb = SILENCE_DB.toFloat(), error = error)
        }
        if (wasRunning) container.appScope.launch { container.remote?.reportMonitoring(false) }
        if (error != null) container.notifications.showStopped(error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(reason: String) {
        container.monitorState.update { it.copy(running = false, error = reason) }
        container.notifications.showStopped(reason)
        stopSelf()
    }

    // No timeout: a monitor is meant to listen for as long as it is left on, and the lock is
    // released whenever monitoring stops.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "petcheck:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "io.github.byronfichardt.petcheck.action.STOP"
        private const val TAG = "MonitorService"
        private const val HEARTBEAT_MS = 10 * 60_000L

        /** Call only while the app is visible. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
