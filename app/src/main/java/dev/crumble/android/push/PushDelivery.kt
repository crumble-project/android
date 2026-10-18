package dev.crumble.android.push

import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.NotificationContext
import dev.crumble.android.model.NotificationNote
import dev.crumble.android.model.notification

/** Both transports use one persisted journal and the same visible-chat policy. */
class PushDelivery(private val store: PushStateStore) {
    fun local(event: ActivityEvent, context: NotificationContext, allowed: Boolean): NotificationNote? {
        val connection = store.read().current ?: return if (allowed) notification(event, context) else null
        if (event.type != "reply") return null
        if (event.serverId != null && connection.serverId != null && event.serverId != connection.serverId) return null
        val note = notification(event, context)
        if (connection.remoteDelivery) {
            // A reply seen in the visible chat stays quiet if FCM arrives after navigation.
            if (note == null) store.claim(event, connection)
            return null
        }
        if (!allowed || !store.claim(event, connection)) return null
        return note
    }

    fun remote(event: ActivityEvent, context: NotificationContext, allowed: Boolean): NotificationNote? {
        val connection = store.read().current ?: return null
        if (!allowed || event.serverId == null || !store.accepts(event.serverId)) return null
        val note = notification(event, context)
        if (!store.claim(event, connection)) return null
        return note
    }
}
