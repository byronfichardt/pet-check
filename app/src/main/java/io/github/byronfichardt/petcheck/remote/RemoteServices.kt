package io.github.byronfichardt.petcheck.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import io.github.byronfichardt.petcheck.BuildConfig
import io.github.byronfichardt.petcheck.data.EventDao
import io.github.byronfichardt.petcheck.data.SettingsStore
import io.github.byronfichardt.petcheck.data.SoundEvent
import io.github.byronfichardt.petcheck.data.SoundType
import io.github.byronfichardt.petcheck.monitor.AlertTrigger
import io.github.byronfichardt.petcheck.monitor.Notifications
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class Account(val uid: String, val label: String)

/** A device signed in to the same account, as seen in Firestore. */
data class RemoteDevice(
    val id: String,
    val name: String,
    val platform: String,
    val monitoring: Boolean,
    val monitoringSinceMs: Long?,
    val lastSeenMs: Long?,
    val isThisDevice: Boolean,
)

/**
 * Everything that talks to Firebase. Only created when the build has a google-services.json,
 * so the rest of the app treats it as optional. Firestore layout, per signed-in user:
 *
 *   users/{uid}/devices/{deviceId}  name, platform, monitoring, lastSeen, fid
 *   users/{uid}/events/{eventId}    deviceId, deviceName, startedAt, durationMs, type, peakDb, confidence, syncedAt
 *   users/{uid}/alerts/{alertId}    deviceId, deviceName, createdAt, noisySeconds, windowMinutes, type
 *
 * The Room database stays the source of truth for the UI: local events are uploaded from it and
 * events from other devices are copied into it.
 */
class RemoteServices(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: EventDao,
    private val notifications: Notifications,
    private val scope: CoroutineScope,
) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val _account = MutableStateFlow(auth.currentUser?.toAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private var syncJob: Job? = null
    private var syncUid: String? = null

    @Volatile private var monitoring = false

    /** True when the Firebase project has Google sign-in configured for this build. */
    val googleSignInAvailable: Boolean get() = webClientId() != null

    fun start() {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _account.value = user?.toAccount()
            restartSync(user?.uid)
        }
    }

    // --- Sign-in ---

    suspend fun signInWithEmail(email: String, password: String, createAccount: Boolean): Result<Unit> = attempt {
        if (createAccount) {
            auth.createUserWithEmailAndPassword(email.trim(), password).await()
        } else {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
        }
    }

    /** Returns success without signing in if the user dismisses the account picker. */
    suspend fun signInWithGoogle(activity: Activity): Result<Unit> = attempt {
        val clientId = webClientId() ?: error("Google sign-in is not set up for this build.")
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return@attempt
        } catch (e: NoCredentialException) {
            error("No Google account is available on this device.")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            error("Unexpected sign-in response.")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    /** Signs out and removes other devices' events from this device. Local events are kept. */
    suspend fun signOut() {
        val uid = auth.currentUser?.uid
        val local = settings.current()
        syncJob?.cancelAndJoin()
        syncJob = null
        syncUid = null
        if (uid != null) {
            // Best effort: stop this device receiving pushes for the account it is leaving.
            withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                runCatching {
                    deviceDoc(uid, local.deviceId)
                        .set(mapOf("fid" to FieldValue.delete(), "monitoring" to false), SetOptions.merge())
                        .await()
                }
            }
        }
        auth.signOut()
        dao.deleteOtherDevices(local.deviceId)
        dao.markAllUnsynced(local.deviceId)
        settings.setRemoteCursor(0)
        _devices.value = emptyList()
    }

    // --- Called by the monitor ---

    /** Records whether this device is listening. Also serves as its heartbeat. */
    suspend fun reportMonitoring(running: Boolean) {
        val changed = monitoring != running
        monitoring = running
        val uid = auth.currentUser?.uid ?: return
        val local = settings.current()
        val data = HashMap<String, Any?>()
        data["monitoring"] = running
        data["lastSeen"] = FieldValue.serverTimestamp()
        if (changed) data["monitoringSince"] = if (running) FieldValue.serverTimestamp() else FieldValue.delete()
        write("device status") { deviceDoc(uid, local.deviceId).set(data, SetOptions.merge()).await() }
    }

    /** Publishes an alert for the account's other devices. A Cloud Function turns it into a push. */
    suspend fun sendAlert(trigger: AlertTrigger) {
        val uid = auth.currentUser?.uid ?: return
        val local = settings.current()
        val data = mapOf(
            "deviceId" to local.deviceId,
            "deviceName" to local.deviceName,
            "createdAt" to FieldValue.serverTimestamp(),
            "noisySeconds" to trigger.noisySeconds,
            "windowMinutes" to trigger.windowMinutes,
            "type" to trigger.type.key,
        )
        write("alert") { userDoc(uid).collection("alerts").document().set(data).await() }
    }

    /**
     * Stores this install's Firebase installation ID, which is the address pushes are sent to.
     * Called by [PetCheckMessagingService] after every registration.
     */
    fun onPushRegistered(installationId: String) {
        val uid = auth.currentUser?.uid ?: return
        scope.launch {
            val local = settings.current()
            write("push registration") {
                deviceDoc(uid, local.deviceId).set(mapOf("fid" to installationId), SetOptions.merge()).await()
            }
        }
    }

    // --- Sync ---

    private fun restartSync(uid: String?) {
        if (uid == syncUid && syncJob?.isActive == true) return
        syncJob?.cancel()
        syncUid = uid
        syncJob = if (uid == null) {
            null
        } else {
            scope.launch {
                val local = settings.current()
                launch { registerDevice(uid) }
                launch { retrying("upload") { uploadEvents(uid, local.deviceId) } }
                launch { retrying("download") { downloadEvents(uid, local.deviceId) } }
                launch { retrying("devices") { watchDevices(uid, local.deviceId) } }
                launch { retrying("alerts") { watchAlerts(uid, local.deviceId) } }
            }
        }
    }

    private suspend fun registerDevice(uid: String) {
        val local = settings.current()
        val data = HashMap<String, Any?>()
        data["name"] = local.deviceName
        data["platform"] = "android"
        data["model"] = Build.MODEL
        data["appVersion"] = BuildConfig.VERSION_NAME
        data["monitoring"] = monitoring
        data["lastSeen"] = FieldValue.serverTimestamp()
        write("device registration") { deviceDoc(uid, local.deviceId).set(data, SetOptions.merge()).await() }
        // Registration reports the installation ID through PetCheckMessagingService.onRegistered,
        // every time it is called, so a device that signs in again is stored again.
        runCatching { FirebaseMessaging.getInstance().register().await() }
            .onFailure { Log.w(TAG, "Push registration failed", it) }
    }

    /** Keeps the device's display name in Firestore in line with the local setting. */
    suspend fun renameDevice(name: String) {
        val uid = auth.currentUser?.uid ?: return
        val local = settings.current()
        write("device name") { deviceDoc(uid, local.deviceId).set(mapOf("name" to name), SetOptions.merge()).await() }
    }

    /**
     * Uploads local events that have not been sent yet. Each batch is awaited before the next is
     * built, so being offline never queues the same event twice. Document ids are the event ids,
     * which makes a repeated upload harmless.
     */
    private suspend fun uploadEvents(uid: String, deviceId: String) {
        val collection = userDoc(uid).collection("events")
        dao.observeUnsynced(deviceId, UPLOAD_BATCH).conflate().collect { pending ->
            if (pending.isEmpty()) return@collect
            val batch = db.batch()
            for (event in pending) {
                batch.set(
                    collection.document(event.id),
                    mapOf(
                        "deviceId" to event.deviceId,
                        "deviceName" to event.deviceName,
                        "startedAt" to Timestamp(Date(event.startedAt)),
                        "durationMs" to event.durationMs,
                        "type" to event.type.key,
                        "peakDb" to event.peakDb.toDouble(),
                        "confidence" to event.confidence.toDouble(),
                        "syncedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            batch.commit().await()
            dao.markSynced(pending.map { it.id })
        }
    }

    /**
     * Copies other devices' events into the local database. The cursor is the server time of the
     * newest event already seen, so each event is read from Firestore once per device.
     */
    private suspend fun downloadEvents(uid: String, deviceId: String) {
        val saved = settings.current().remoteCursorMs
        var cursor = if (saved > 0) saved else System.currentTimeMillis() - FIRST_SYNC_WINDOW_MS
        userDoc(uid).collection("events")
            .whereGreaterThan("syncedAt", Timestamp(Date(cursor)))
            .orderBy("syncedAt")
            .asFlow()
            .collect { snapshot ->
                val incoming = ArrayList<SoundEvent>()
                var newest = cursor
                for (change in snapshot.documentChanges) {
                    if (change.type == DocumentChange.Type.REMOVED) continue
                    val doc = change.document
                    // Null while one of this device's own writes is still waiting for the server.
                    val syncedAt = doc.getTimestamp("syncedAt")?.toDate()?.time ?: continue
                    if (syncedAt > newest) newest = syncedAt
                    if (doc.getString("deviceId") == deviceId) continue
                    doc.toSoundEvent()?.let(incoming::add)
                }
                if (incoming.isNotEmpty()) dao.upsert(incoming)
                if (newest > cursor) {
                    cursor = newest
                    settings.setRemoteCursor(newest)
                }
            }
    }

    private suspend fun watchDevices(uid: String, deviceId: String) {
        userDoc(uid).collection("devices").asFlow().collect { snapshot ->
            _devices.value = snapshot.documents.map { doc ->
                RemoteDevice(
                    id = doc.id,
                    name = doc.getString("name") ?: "Unnamed device",
                    platform = doc.getString("platform") ?: "",
                    monitoring = doc.getBoolean("monitoring") ?: false,
                    monitoringSinceMs = doc.getTimestamp("monitoringSince")?.toDate()?.time,
                    lastSeenMs = doc.getTimestamp("lastSeen")?.toDate()?.time,
                    isThisDevice = doc.id == deviceId,
                )
            }.sortedWith(compareByDescending<RemoteDevice> { it.monitoring }.thenBy { it.name.lowercase() })
        }
    }

    /**
     * Shows alerts raised by other devices while this app is alive. This works without the
     * Cloud Function; with it, the push covers the times when the app is not running.
     */
    private suspend fun watchAlerts(uid: String, deviceId: String) {
        userDoc(uid).collection("alerts")
            .whereGreaterThan("createdAt", Timestamp.now())
            .asFlow()
            .collect { snapshot ->
                for (change in snapshot.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    if (doc.getString("deviceId") == deviceId) continue
                    notifications.showAlert(
                        alertId = doc.id,
                        deviceName = doc.getString("deviceName") ?: "A monitor",
                        noisySeconds = doc.getLong("noisySeconds")?.toInt() ?: 0,
                        windowMinutes = doc.getLong("windowMinutes")?.toInt() ?: 0,
                        type = SoundType.fromKey(doc.getString("type")),
                    )
                }
            }
    }

    // --- Helpers ---

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    private fun deviceDoc(uid: String, deviceId: String) = userDoc(uid).collection("devices").document(deviceId)

    private fun DocumentSnapshot.toSoundEvent(): SoundEvent? {
        val startedAt = getTimestamp("startedAt")?.toDate()?.time ?: return null
        return SoundEvent(
            id = id,
            deviceId = getString("deviceId") ?: return null,
            deviceName = getString("deviceName") ?: "Unnamed device",
            startedAt = startedAt,
            durationMs = getLong("durationMs") ?: return null,
            type = SoundType.fromKey(getString("type")),
            peakDb = getDouble("peakDb")?.toFloat() ?: 0f,
            confidence = getDouble("confidence")?.toFloat() ?: 0f,
            synced = true,
        )
    }

    private fun Query.asFlow(): Flow<QuerySnapshot> = callbackFlow {
        val registration = addSnapshotListener { snapshot, error ->
            if (error != null) close(error) else if (snapshot != null) trySend(snapshot)
        }
        awaitClose { registration.remove() }
    }

    /** Keeps a sync loop alive across errors such as a dropped connection or a rules rejection. */
    private suspend fun retrying(what: String, block: suspend () -> Unit) {
        var backoff = 5_000L
        while (scope.isActive) {
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync ($what) failed, retrying in ${backoff / 1000}s", e)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
            }
        }
    }

    /** A one-off write that must not hang the caller while offline: Firestore keeps it queued. */
    private suspend fun write(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $what", e)
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Sign-in failed", e)
        Result.failure(e)
    }

    // The id comes from google-services.json, which is absent from builds without Firebase,
    // so it cannot be referenced as R.string.
    @SuppressLint("DiscouragedApi")
    private fun webClientId(): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    private fun FirebaseUser.toAccount() = Account(uid, email ?: displayName ?: "Signed in")

    companion object {
        private const val TAG = "RemoteServices"
        private const val UPLOAD_BATCH = 200
        private const val WRITE_TIMEOUT_MS = 8_000L
        private const val FIRST_SYNC_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

        /** Null when this build has no Firebase configuration. */
        fun createIfConfigured(
            context: Context,
            settings: SettingsStore,
            dao: EventDao,
            notifications: Notifications,
            scope: CoroutineScope,
        ): RemoteServices? =
            if (FirebaseApp.getApps(context).isEmpty()) null else RemoteServices(context, settings, dao, notifications, scope)
    }
}
