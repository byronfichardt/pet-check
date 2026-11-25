package io.github.byronfichardt.petcheck.monitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.byronfichardt.petcheck.MainActivity
import io.github.byronfichardt.petcheck.R
import io.github.byronfichardt.petcheck.data.SoundType

class Notifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MONITORING, "Monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while this device is listening"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ALERTS, "Noise alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a monitor hears more noise than you allow"
                },
                NotificationChannel(CHANNEL_STATUS, "Monitor status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When monitoring stops unexpectedly"
                },
            ),
        )
    }

    /** The ongoing notification that keeps the monitor service in the foreground. */
    fun monitoring(eventCount: Int): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (eventCount) {
            0 -> "No sounds logged yet"
            1 -> "1 sound logged"
            else -> "$eventCount sounds logged"
        }
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("Pet Check is listening")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, "Stop", stop)
            .build()
    }

    fun updateMonitoring(eventCount: Int) = post(MONITOR_ID, monitoring(eventCount))

    fun showAlert(alertId: String, deviceName: String, noisySeconds: Int, windowMinutes: Int, type: SoundType) {
        val what = when (type) {
            SoundType.BARK -> "barking"
            SoundType.HOWL -> "howling"
            SoundType.WHINE -> "whining"
            SoundType.GROWL -> "growling"
            SoundType.MEOW -> "meowing"
            SoundType.OTHER -> "noise"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("$deviceName heard a lot of $what")
            .setContentText("${formatSeconds(noisySeconds)} in the last $windowMinutes min")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        // The same alert can arrive by push and by the live listener: one id keeps it to one notification.
        post(alertId.hashCode(), notification)
    }

    fun showStopped(reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("Monitoring stopped")
            .setContentText("$reason Tap to open Pet Check and start again.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason Tap to open Pet Check and start again."))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        post(STOPPED_ID, notification)
    }

    fun clearStopped() = manager.cancel(STOPPED_ID)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    private fun post(id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    companion object {
        const val MONITOR_ID = 1
        const val STOPPED_ID = 2
        private const val CHANNEL_MONITORING = "monitoring"
        private const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_STATUS = "status"

        fun formatSeconds(seconds: Int): String =
            if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
    }
}
