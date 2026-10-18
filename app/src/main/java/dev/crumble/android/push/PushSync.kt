package dev.crumble.android.push

import dev.crumble.android.network.APIClient
import dev.crumble.android.network.APIError
import kotlinx.coroutines.CancellationException

enum class PushSyncResult { SUCCESS, RETRY, FAILURE }

/** Registration reconciliation, independent of Android's scheduler and Firebase SDK. */
class PushSync(
    private val store: PushStateStore,
    private val installationId: String,
    private val firebaseConfigured: Boolean,
    private val fetchToken: suspend () -> String,
    private val client: (PushConnection) -> APIClient = { APIClient(it.url, it.bearer) },
    private val onRegistered: suspend () -> Unit = {},
) {
    suspend fun run(): PushSyncResult {
        var retryRemoval = false
        for (old in store.read().retired) {
            try {
                client(old).unregisterPushDevice(installationId)
                store.removed(old)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is APIError && e.statusCode == 404) store.removed(old)
                else retryRemoval = true
            }
        }
        var revision = store.read().revision
        var deleting = false
        try {
            val current = store.read().current ?: return if (retryRemoval) PushSyncResult.RETRY else PushSyncResult.SUCCESS
            val api = client(current)
            if (!current.enabled || !firebaseConfigured) {
                deleting = true
                store.clearRemoteDelivery(revision)
                // PUT may have reached the server before process death, even if its
                // response/serverId was never saved locally.
                if (current.serverId != null || store.read().token != null) api.unregisterPushDevice(installationId)
                return if (retryRemoval) PushSyncResult.RETRY else PushSyncResult.SUCCESS
            }
            val tokenRevision = store.read().revision
            if (!store.fetchedToken(fetchToken(), tokenRevision)) return PushSyncResult.RETRY
            val snapshot = store.read()
            revision = snapshot.revision
            val desired = snapshot.current ?: return PushSyncResult.SUCCESS
            if (!desired.sameServer(current) || !desired.enabled) return PushSyncResult.RETRY
            val info = api.pushInfo()
            if (info.serverId.isBlank()) return PushSyncResult.FAILURE
            val result = api.registerPushDevice(installationId, PushRegistration(token = snapshot.token!!))
            if (result.serverId != info.serverId) return PushSyncResult.RETRY
            if (!store.registered(snapshot.revision, result.serverId, result.remoteDelivery)) return PushSyncResult.RETRY
            onRegistered()
            return if (retryRemoval) PushSyncResult.RETRY else PushSyncResult.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: APIError) {
            return when {
                e.statusCode == 404 -> {
                    store.clearRemoteDelivery(revision)
                    if (retryRemoval) PushSyncResult.RETRY else PushSyncResult.SUCCESS
                }
                deleting || retryRemoval -> PushSyncResult.RETRY
                e.statusCode in listOf(400, 401, 403) -> PushSyncResult.FAILURE
                else -> PushSyncResult.RETRY
            }
        } catch (_: Exception) {
            return PushSyncResult.RETRY
        }
    }
}
