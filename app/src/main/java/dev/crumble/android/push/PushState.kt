package dev.crumble.android.push

import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.storage.CredentialStore
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class PushConnection(
    val url: String,
    val bearer: String,
    val enabled: Boolean,
    val serverId: String? = null,
    val remoteDelivery: Boolean = false,
) {
    fun sameServer(other: PushConnection) = url == other.url && bearer == other.bearer
    fun identity(): String = MessageDigest.getInstance("SHA-256")
        .digest("$url\n$bearer".toByteArray()).joinToString("") { "%02x".format(it) }
}

@Serializable
data class PushState(
    val current: PushConnection? = null,
    val retired: List<PushConnection> = emptyList(),
    val token: String? = null,
    val revision: Long = 0,
    val seen: List<String> = emptyList(),
)

/** Encrypted registration queue and bounded event journal, shared by SSE and FCM. */
class PushStateStore(private val credentials: CredentialStore) {
    // All instances share a lock: the receiver, activity, and worker use the same credential file.
    companion object {
        private val lock = Any()
        private const val KEY = "push-state-v1"
    }

    fun read(): PushState = synchronized(lock) {
        credentials.read(KEY)?.let {
            runCatching { CrumbleJson.decodeFromString(PushState.serializer(), it) }.getOrNull()
        } ?: PushState()
    }

    private fun write(state: PushState) {
        credentials.write(KEY, CrumbleJson.encodeToString(PushState.serializer(), state))
    }

    fun configure(url: String, bearer: String, enabled: Boolean): Boolean = synchronized(lock) {
        val state = read()
        val desired = PushConnection(url.trimEnd('/'), bearer, enabled)
        val old = state.current
        if (old != null && old.sameServer(desired) && old.enabled == enabled) return false
        val switched = old != null && !old.sameServer(desired)
        write(state.copy(
            current = if (old != null && !switched) old.copy(enabled = enabled, remoteDelivery = false) else desired,
            retired = if (switched) (state.retired + old).filterNotNull().distinct() else state.retired,
            revision = state.revision + 1,
        ))
        true
    }

    fun updateToken(token: String): Boolean = synchronized(lock) {
        val state = read()
        if (token.isBlank() || state.token == token) return false
        write(state.copy(token = token, revision = state.revision + 1))
        true
    }

    fun fetchedToken(token: String, revision: Long): Boolean = synchronized(lock) {
        if (token.isBlank() || read().revision != revision) return false
        updateToken(token)
        true
    }

    fun removed(connection: PushConnection) = synchronized(lock) {
        val state = read()
        write(state.copy(retired = state.retired.filterNot { it == connection }))
    }

    fun clearRemoteDelivery(revision: Long = read().revision) = synchronized(lock) {
        val state = read()
        if (state.revision == revision) {
            write(state.copy(current = state.current?.copy(remoteDelivery = false)))
        }
    }

    /** Late requests cannot bind a new connection to an old server or token. */
    fun registered(revision: Long, serverId: String, remoteDelivery: Boolean): Boolean = synchronized(lock) {
        val state = read()
        if (state.revision != revision || serverId.isBlank() || state.current == null) return false
        write(state.copy(current = state.current.copy(serverId = serverId,
            remoteDelivery = remoteDelivery && state.current.enabled)))
        true
    }

    fun accepts(serverId: String): Boolean = read().current?.let {
        it.enabled && it.serverId == serverId
    } == true

    fun agentForTap(serverId: String?, identity: String?, agentId: String?): String? {
        val connection = read().current ?: return null
        if (identity != connection.identity()) return null
        if (serverId != null && connection.serverId != serverId) return null
        return agentId?.takeIf { it.isNotBlank() }
    }

    /** Returns false for duplicates; marks visible-chat events too, so late push stays quiet. */
    fun claim(event: ActivityEvent, connection: PushConnection): Boolean = synchronized(lock) {
        val eventIdentity = event.eventId?.takeIf { it.isNotBlank() }
            ?: event.entryId?.takeIf { it.isNotBlank() }?.let { "${event.agentId}:$it" }
            ?: return true // Older backends do not provide a stable reply identity.
        val serverIdentity = event.serverId ?: connection.serverId ?: connection.url
        val key = "$serverIdentity\n$eventIdentity"
        val state = read()
        if (key in state.seen) return false
        write(state.copy(seen = (state.seen + key).takeLast(512)))
        true
    }
}
