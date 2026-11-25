package io.github.byronfichardt.petcheck

import android.app.Application
import android.content.Context
import io.github.byronfichardt.petcheck.data.EventDao
import io.github.byronfichardt.petcheck.data.EventDatabase
import io.github.byronfichardt.petcheck.data.SettingsStore
import io.github.byronfichardt.petcheck.monitor.MonitorState
import io.github.byronfichardt.petcheck.monitor.Notifications
import io.github.byronfichardt.petcheck.remote.RemoteServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** The app's few long-lived objects, shared by the UI and the monitor service. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val notifications = Notifications(context)
    val monitorState = MutableStateFlow(MonitorState())

    private val database by lazy { EventDatabase.create(context) }
    val events: EventDao get() = database.events()

    /** Null in builds without a Firebase configuration: the app is then a standalone monitor. */
    val remote: RemoteServices? = RemoteServices.createIfConfigured(context, settings, events, notifications, appScope)
}

class PetCheckApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.remote?.start()
    }
}
