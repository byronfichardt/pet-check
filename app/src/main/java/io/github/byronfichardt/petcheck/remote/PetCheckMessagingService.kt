package io.github.byronfichardt.petcheck.remote

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.byronfichardt.petcheck.PetCheckApp
import io.github.byronfichardt.petcheck.data.SoundType

/** Receives the data-only pushes sent by the sendAlert Cloud Function (firebase/functions). */
class PetCheckMessagingService : FirebaseMessagingService() {
    private val container get() = (application as PetCheckApp).container

    override fun onRegistered(installationId: String) {
        container.remote?.onPushRegistered(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["kind"] != "alert") return
        container.notifications.showAlert(
            alertId = data["alertId"] ?: return,
            deviceName = data["deviceName"] ?: "A monitor",
            noisySeconds = data["noisySeconds"]?.toIntOrNull() ?: 0,
            windowMinutes = data["windowMinutes"]?.toIntOrNull() ?: 0,
            type = SoundType.fromKey(data["type"]),
        )
    }
}
