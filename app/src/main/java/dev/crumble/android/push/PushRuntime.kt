package dev.crumble.android.push

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.annotation.SuppressLint
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.AndroidReplyNotifier
import dev.crumble.android.model.NotificationContext
import dev.crumble.android.model.ReplyNotifier
import dev.crumble.android.model.Route
import dev.crumble.android.network.APIClient
import dev.crumble.android.storage.AndroidKeyStoreCredentials
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide receiver state; registration and duplicate tracking survive process death. */
class PushRuntime private constructor(private val context: Context) : ReplyNotifier {
    val store = PushStateStore(AndroidKeyStoreCredentials(context, synchronousWrites = true))
    private val notifier = AndroidReplyNotifier(context) { store.read().current?.identity() }
    private val delivery = PushDelivery(store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val presenceMutex = Mutex()
    @Volatile private var visible = NotificationContext(false, null)

    // This ID must not be restored onto a second device from Android Auto Backup.
    val installationId: String by lazy {
        val file = File(context.noBackupFilesDir, "push-installation-id")
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf {
            runCatching { UUID.fromString(it) }.isSuccess
        } ?: UUID.randomUUID().toString().also { file.writeText(it) }
    }

    fun configure(api: APIClient) {
        val previous = store.read().current
        if (previous != null && (previous.url != api.baseURL || previous.bearer != api.token)) {
            NotificationManagerCompat.from(context).cancelAll()
        }
        store.configure(api.baseURL, api.token, notificationsEnabled())
        scheduleSync()
        if (FirebaseApp.getApps(context).isEmpty()) store.clearRemoteDelivery()
    }

    fun notificationsEnabled(): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(AndroidReplyNotifier.CHANNEL_ID)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun tokenChanged(token: String) {
        if (store.updateToken(token)) scheduleSync()
    }

    fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "crumble-push-registration", ExistingWorkPolicy.REPLACE, request,
        )
    }

    fun updatePresence(active: Boolean, route: Route?) {
        visible = NotificationContext(active, route)
        refreshPresence()
    }

    fun refreshPresence() {
        scope.launch { sendPresence() }
    }

    suspend fun sendPresence() = presenceMutex.withLock {
        val connection = store.read().current ?: return@withLock
        if (connection.serverId == null || !connection.enabled) return@withLock
        val state = visible
        val agentId = (state.route as? Route.AgentChat)?.id
        // Read the latest presence after acquiring the lock so a queued old resume cannot
        // overwrite a more recent pause. The server expires this lease after 120 seconds.
        runCatching {
            APIClient(connection.url, connection.bearer).updatePushPresence(
                installationId, PushPresence(state.appActive, if (state.appActive) agentId else null),
            )
        }
    }

    override fun handle(event: ActivityEvent, context: NotificationContext) {
        val note = delivery.local(event, context, notificationsEnabled()) ?: return
        val serverId = store.read().current?.serverId
        notifier.post(event.copy(serverId = event.serverId ?: serverId), note.title, note.body)
    }

    override fun post(event: ActivityEvent, title: String, body: String) = notifier.post(event, title, body)

    fun receive(data: Map<String, String>) {
        val event = parsePushReplyData(data) ?: return
        val note = delivery.remote(event, visible, notificationsEnabled()) ?: return
        notifier.post(event, note.title, note.body)
    }

    /** Bind a notification tap to the saved server, including a fresh process launch. */
    fun agentFromIntent(intent: Intent): String? {
        return store.agentForTap(
            intent.getStringExtra(AndroidReplyNotifier.EXTRA_SERVER_ID),
            intent.getStringExtra(AndroidReplyNotifier.EXTRA_CONNECTION_ID),
            intent.getStringExtra(AndroidReplyNotifier.EXTRA_AGENT_ID),
        )
    }

    companion object {
        // get() stores applicationContext only, whose lifetime matches this singleton.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: PushRuntime? = null
        fun get(context: Context): PushRuntime = instance ?: synchronized(this) {
            instance ?: PushRuntime(context.applicationContext).also { instance = it }
        }
    }
}
