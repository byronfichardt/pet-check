package io.github.byronfichardt.petcheck.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.byronfichardt.petcheck.BuildConfig
import io.github.byronfichardt.petcheck.data.Settings
import io.github.byronfichardt.petcheck.data.SettingsStore
import io.github.byronfichardt.petcheck.remote.Account

@Composable
fun SettingsRoute(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    val activity: Activity? = LocalActivity.current

    SettingsContent(
        settings = settings,
        remoteAvailable = viewModel.remoteAvailable,
        googleSignInAvailable = viewModel.googleSignInAvailable,
        account = account,
        signIn = signIn,
        onSignInWithEmail = viewModel::signInWithEmail,
        onSignInWithGoogle = { activity?.let(viewModel::signInWithGoogle) },
        onSignOut = viewModel::signOut,
        onDeviceName = viewModel::setDeviceName,
        onLogOtherSounds = viewModel::setLogOtherSounds,
        onAlertsEnabled = viewModel::setAlertsEnabled,
        onAlertNoisySeconds = viewModel::setAlertNoisySeconds,
        onAlertWindowMinutes = viewModel::setAlertWindowMinutes,
        onAlertIncludeOther = viewModel::setAlertIncludeOther,
        onClearHistory = viewModel::clearHistory,
    )
}

@Composable
fun SettingsContent(
    settings: Settings,
    remoteAvailable: Boolean,
    googleSignInAvailable: Boolean,
    account: Account?,
    signIn: SignInState,
    onSignInWithEmail: (email: String, password: String, createAccount: Boolean) -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onDeviceName: (String) -> Unit,
    onLogOtherSounds: (Boolean) -> Unit,
    onAlertsEnabled: (Boolean) -> Unit,
    onAlertNoisySeconds: (Int) -> Unit,
    onAlertWindowMinutes: (Int) -> Unit,
    onAlertIncludeOther: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Settings")

        Panel(title = "Remote viewing") {
            when {
                !remoteAvailable -> Text(
                    "This build has no Firebase project, so it works on this device only. " +
                        "See docs/firebase-setup.md in the project to turn on remote viewing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                account != null -> SignedIn(account, onSignOut)
                else -> SignInForm(signIn, googleSignInAvailable, onSignInWithEmail, onSignInWithGoogle)
            }
        }

        Panel(title = "This device") {
            DeviceNameField(settings.deviceName, onDeviceName)
            SwitchRow(
                title = "Log other loud sounds",
                description = "Keep sounds that were not recognised as a pet, such as a door slamming.",
                checked = settings.logOtherSounds,
                onChange = onLogOtherSounds,
            )
        }

        Panel(title = "Noise alerts") {
            val signedIn = account != null
            SwitchRow(
                title = "Alert my other devices",
                description = if (signedIn) {
                    "When this device hears too much noise, notify the other devices signed in to your account."
                } else {
                    "Sign in first: alerts go to the other devices on your account."
                },
                checked = settings.alertsEnabled && signedIn,
                enabled = signedIn,
                onChange = onAlertsEnabled,
            )
            if (settings.alertsEnabled && signedIn) {
                OptionRow("After this much noise", NOISE_OPTIONS, settings.alertNoisySeconds, { Format.duration(it * 1000L) }, onAlertNoisySeconds)
                OptionRow("Within", WINDOW_OPTIONS, settings.alertWindowMinutes, { "$it min" }, onAlertWindowMinutes)
                SwitchRow(
                    title = "Count other loud sounds",
                    description = "Otherwise only pet sounds count towards an alert.",
                    checked = settings.alertIncludeOther,
                    onChange = onAlertIncludeOther,
                )
                Text(
                    "Alerts reach a device while Pet Check is running on it. Reaching it when the app is closed " +
                        "needs the Cloud Function from docs/firebase-setup.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Panel(title = "History") {
            var confirm by remember { mutableStateOf(false) }
            Text(
                "Removes every logged sound from this device. Copies already synced to your account stay there.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirm = true }) { Text("Clear history on this device") }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Clear history?") },
                    text = { Text("Every logged sound will be removed from this device. This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirm = false
                            onClearHistory()
                        }) { Text("Clear") }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
            }
        }

        Panel(title = "About") {
            Text(
                "Audio is analysed on this device and never stored or sent anywhere. " +
                    "Only the type, time, length and loudness of each sound are kept.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Pet Check ${BuildConfig.VERSION_NAME} · MIT licence · Sound recognition by YAMNet (Apache 2.0)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SignedIn(account: Account, onSignOut: () -> Unit) {
    Text("Signed in as ${account.label}", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Sounds logged here appear on your other signed-in devices, and theirs appear here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onSignOut) { Text("Sign out") }
}

@Composable
private fun SignInForm(
    state: SignInState,
    googleAvailable: Boolean,
    onEmail: (String, String, Boolean) -> Unit,
    onGoogle: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    val password = rememberTextFieldState()
    Text(
        "Sign in on each device with the same account to see your monitor from anywhere.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedSecureTextField(
        state = password,
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onEmail(email, password.text.toString(), false) }, enabled = !state.busy) { Text("Sign in") }
        OutlinedButton(onClick = { onEmail(email, password.text.toString(), true) }, enabled = !state.busy) { Text("Create account") }
    }
    if (googleAvailable) {
        TextButton(onClick = onGoogle, enabled = !state.busy) { Text("Continue with Google") }
    }
}

@Composable
private fun DeviceNameField(name: String, onSave: (String) -> Unit) {
    var draft by rememberSaveable(name) { mutableStateOf(name) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(SettingsStore.MAX_NAME_LENGTH) },
            label = { Text("Device name") },
            supportingText = { Text("Shown on your other devices, for example “Kitchen tablet”.") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (draft.trim() != name && draft.isNotBlank()) {
            TextButton(onClick = { onSave(draft) }) { Text("Save") }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun OptionRow(title: String, options: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}

private val NOISE_OPTIONS = listOf(15, 30, 60, 120, 300, 600)
private val WINDOW_OPTIONS = listOf(5, 10, 15, 30, 60)
