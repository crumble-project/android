package dev.crumble.android.push

import dev.crumble.android.BuildConfig
import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.CrumbleJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** The transports enabled by a Crumble server for remote delivery. */
@Serializable
data class PushInfo(
    val serverId: String = "",
    val transports: Map<String, Boolean> = emptyMap(),
)

/** App registration preferences, mapped to the generated request at the API boundary. */
data class PushRegistration(
    val platform: String = "android",
    val transport: String = "fcm",
    val token: String,
    val environment: String = "production",
    val bundleId: String? = BuildConfig.APPLICATION_ID,
    val enabled: Boolean = true,
)

@Serializable
data class PushRegistrationResult(
    val serverId: String = "",
    val remoteDelivery: Boolean = false,
)

/** Presence used to suppress a notification for the chat currently on screen. */
data class PushPresence(
    val active: Boolean,
    val agentId: String? = null,
)

/** The common reply payload sent by FCM data messages and APNs adapters. */
@Serializable
data class PushReply(
    val type: String = "",
    val eventId: String = "",
    val serverId: String = "",
    val agentId: String = "",
    val agentName: String = "",
    val entryId: String? = null,
    val origin: String? = null,
    val preview: String = "",
    val createdAt: String? = null,
) {
    fun toActivityEvent(): ActivityEvent = ActivityEvent(
        type = type,
        agentId = agentId,
        agentName = agentName,
        entryId = entryId?.takeIf { it.isNotEmpty() },
        origin = origin,
        preview = preview,
        eventId = eventId,
        serverId = serverId,
        createdAt = createdAt,
    )
}

/**
 * Parses an FCM/APNs reply data payload. Invalid or non-reply payloads are ignored so a
 * malformed provider message cannot interrupt the message stream.
 */
fun parsePushReplyPayload(payload: String): ActivityEvent? {
    val reply = try {
        CrumbleJson.decodeFromString(PushReply.serializer(), payload)
    } catch (_: Exception) {
        return null
    }
    if (reply.type != "reply" || reply.eventId.isBlank() || reply.serverId.isBlank() || reply.agentId.isBlank()) {
        return null
    }
    return reply.toActivityEvent()
}

/** Parses the string-valued FCM data map without requiring Android Firebase classes. */
fun parsePushReplyData(data: Map<String, String>): ActivityEvent? =
    parsePushReplyPayload(CrumbleJson.encodeToString(MapSerializerHolder.serializer, data))

private object MapSerializerHolder {
    val serializer = MapSerializer(String.serializer(), String.serializer())
}
