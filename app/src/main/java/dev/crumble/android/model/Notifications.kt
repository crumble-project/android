package dev.crumble.android.model

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.crumble.android.MainActivity
import dev.crumble.android.R
import dev.crumble.android.storage.CredentialStore

/** This device's choices, like the website's per-browser ones. Unset means on. */
class NotificationSettings(private val credentials: CredentialStore) {
    var replies: Boolean
        get() = credentials.read(REPLIES_KEY) != "off"
        set(on) = credentials.write(REPLIES_KEY, if (on) "on" else "off")

    var sound: Boolean
        get() = credentials.read(SOUND_KEY) != "off"
        set(on) = credentials.write(SOUND_KEY, if (on) "on" else "off")

    private companion object {
        const val REPLIES_KEY = "notifications"
        const val SOUND_KEY = "notification-sound"
    }
}

/** What the owner is looking at, which decides whether an event is worth a notification. */
data class NotificationContext(
    val appActive: Boolean,
    val route: Route?,
)

data class NotificationNote(
    val title: String,
    val body: String,
)

/**
 * A finished reply notifies unless its chat is on screen, and delegated work stays quiet because
 * the delegating bot reports back itself.
 */
fun notification(forEvent: ActivityEvent, inContext: NotificationContext): NotificationNote? {
    return when (forEvent.type) {
        "reply" -> {
            if (forEvent.origin == "bot") return null
            val chat = forEvent.groupId?.let { Route.GroupChat(it) } ?: Route.AgentChat(forEvent.agentId)
            if (inContext.appActive && inContext.route == chat) return null
            val text = preview(forEvent.preview ?: "")
            NotificationNote(
                title = if (forEvent.groupId != null) "${forEvent.agentName} in ${forEvent.groupName?.nilIfEmpty ?: "a group"}" else forEvent.agentName,
                body = text.ifEmpty { "Replied" },
            )
        }
        else -> null
    }
}

interface ReplyNotifier {
    fun post(event: ActivityEvent, title: String, body: String)

    fun handle(event: ActivityEvent, context: NotificationContext) {
        val note = notification(event, context) ?: return
        post(event, note.title, note.body)
    }
}

object NoOpNotifier : ReplyNotifier {
    override fun post(event: ActivityEvent, title: String, body: String) = Unit
}

class AndroidReplyNotifier(
    private val context: Context,
    private val connectionIdentity: () -> String? = { null },
    private val sound: () -> Boolean = { true },
) : ReplyNotifier {
    init {
        createChannel()
    }

    private fun createChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bot Replies",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications when a bot finishes a reply"
        }
        manager.createNotificationChannel(channel)
    }

    override fun post(event: ActivityEvent, title: String, body: String) {
        val chat = event.groupId ?: event.agentId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AGENT_ID, event.agentId)
            putExtra(EXTRA_GROUP_ID, event.groupId)
            putExtra(EXTRA_SERVER_ID, event.serverId)
            putExtra(EXTRA_CONNECTION_ID, connectionIdentity())
            data = Uri.Builder().scheme("crumble-notification").authority("reply")
                .appendPath(event.serverId ?: connectionIdentity().orEmpty())
                .appendPath(chat).build()
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            chat.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_crumble)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSilent(!sound())
            .build()
        try {
            NotificationManagerCompat.from(context).notify("reply-$chat", chat.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL_ID = "crumble_replies"
        const val EXTRA_AGENT_ID = "dev.crumble.android.AGENT_ID"
        const val EXTRA_GROUP_ID = "dev.crumble.android.GROUP_ID"
        const val EXTRA_SERVER_ID = "dev.crumble.android.SERVER_ID"
        const val EXTRA_CONNECTION_ID = "dev.crumble.android.CONNECTION_ID"
    }
}
