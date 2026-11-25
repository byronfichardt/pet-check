package io.github.byronfichardt.petcheck.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.byronfichardt.petcheck.AppContainer
import io.github.byronfichardt.petcheck.data.Settings
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.monitor.MonitorState
import io.github.byronfichardt.petcheck.remote.Account
import io.github.byronfichardt.petcheck.remote.RemoteDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInState(val busy: Boolean = false, val error: String? = null)

class AppViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val monitor: StateFlow<MonitorState> = container.monitorState.asStateFlow()

    /** False in builds without a Firebase configuration. */
    val remoteAvailable: Boolean = container.remote != null
    val googleSignInAvailable: Boolean = container.remote?.googleSignInAvailable == true
    val account: StateFlow<Account?> = container.remote?.account ?: MutableStateFlow(null)
    val devices: StateFlow<List<RemoteDevice>> = container.remote?.devices ?: MutableStateFlow(emptyList())

    private val _signIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    val recentEvents: StateFlow<List<SoundEvent>> =
        container.events.observeRecent(RECENT_LIMIT).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { container.settings.current() } // Creates the device id on first launch.
    }

    fun eventsBetween(range: LongRange): Flow<List<SoundEvent>> =
        container.events.observeBetween(range.first, range.last + 1)

    fun clearMonitorError() = container.monitorState.update { it.copy(error = null) }

    fun setSensitivity(value: Int) = edit { setSensitivity(value) }

    fun setLogOtherSounds(value: Boolean) = edit { setLogOtherSounds(value) }

    fun setAlertsEnabled(value: Boolean) = edit { setAlertsEnabled(value) }

    fun setAlertNoisySeconds(value: Int) = edit { setAlertNoisySeconds(value) }

    fun setAlertWindowMinutes(value: Int) = edit { setAlertWindowMinutes(value) }

    fun setAlertIncludeOther(value: Boolean) = edit { setAlertIncludeOther(value) }

    fun setDeviceName(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            container.settings.setDeviceName(name)
            container.remote?.renameDevice(container.settings.current().deviceName)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { container.events.deleteAll() }
    }

    fun signInWithEmail(email: String, password: String, createAccount: Boolean) {
        val remote = container.remote ?: return
        if (email.isBlank() || password.isEmpty()) {
            _signIn.value = SignInState(error = "Enter an email address and a password.")
            return
        }
        signIn { remote.signInWithEmail(email, password, createAccount) }
    }

    fun signInWithGoogle(activity: Activity) {
        val remote = container.remote ?: return
        signIn { remote.signInWithGoogle(activity) }
    }

    fun signOut() {
        val remote = container.remote ?: return
        viewModelScope.launch { remote.signOut() }
    }

    private fun signIn(block: suspend () -> Result<Unit>) {
        if (_signIn.value.busy) return
        _signIn.value = SignInState(busy = true)
        viewModelScope.launch {
            val result = block()
            _signIn.value = SignInState(error = result.exceptionOrNull()?.let { it.localizedMessage ?: "Sign-in failed." })
        }
    }

    private fun edit(block: suspend io.github.byronfichardt.petcheck.data.SettingsStore.() -> Unit) {
        viewModelScope.launch { container.settings.block() }
    }

    companion object {
        const val RECENT_LIMIT = 500

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(container) }
        }
    }
}
