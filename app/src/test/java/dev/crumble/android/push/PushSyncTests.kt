package dev.crumble.android.push

import dev.crumble.android.StubTransport
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.APIError
import dev.crumble.android.storage.InMemoryCredentialStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSyncTests {
    private fun configuredStore(
        url: String = "http://one.example",
        bearer: String = "one",
        enabled: Boolean = true,
    ): PushStateStore {
        val store = PushStateStore(InMemoryCredentialStore())
        store.configure(url, bearer, enabled)
        return store
    }

    private fun client(
        url: String,
        handler: (dev.crumble.android.network.HttpRequestData) -> Pair<Int, String>,
    ): APIClient = APIClient(url, "token", StubTransport(handler))

    @Test
    fun authFailureWhileRemovingRetiredConnectionRetries() = runTest {
        val store = configuredStore()
        val old = store.read().current!!
        store.configure("http://two.example", "two", enabled = true)
        val result = PushSync(store, "install", true, { "token" }, client = { connection ->
            client(connection.url) { 401 to "" }
        }).run()
        assertEquals(PushSyncResult.RETRY, result)
        assertEquals(listOf(old), store.read().retired)
    }

    @Test
    fun disabledCurrentUnregistersWhenTokenExistsEvenWithoutServerId() = runTest {
        val store = configuredStore(enabled = false)
        store.updateToken("known-token")
        var deletes = 0
        val result = PushSync(store, "install", true, { "unused" }, client = { connection ->
            client(connection.url) { request ->
                if (request.method == "DELETE") deletes++
                204 to ""
            }
        }).run()
        assertEquals(PushSyncResult.SUCCESS, result)
        assertEquals(1, deletes)
        assertFalse(store.read().current!!.remoteDelivery)
    }

    @Test
    fun disabledCurrentDelete401RetriesAndCanSucceedLater() = runTest {
        val store = configuredStore(enabled = false)
        store.updateToken("known-token")
        var attempts = 0
        fun api(connection: PushConnection) = client(connection.url) {
            attempts++
            if (attempts == 1) 401 to "" else 204 to ""
        }
        val sync = PushSync(store, "install", true, { "unused" }, client = ::api)
        assertEquals(PushSyncResult.RETRY, sync.run())
        assertEquals(PushSyncResult.SUCCESS, sync.run())
        assertEquals(2, attempts)
    }

    @Test
    fun missingFirebaseFallsBackToLocalDelivery() = runTest {
        val store = configuredStore()
        val result = PushSync(store, "install", firebaseConfigured = false, fetchToken = {
            error("must not fetch token")
        }, client = { connection -> client(connection.url) { 500 to "" } }).run()
        assertEquals(PushSyncResult.SUCCESS, result)
        assertFalse(store.read().current!!.remoteDelivery)
    }

    @Test
    fun registersAndRotatesToken() = runTest {
        val store = configuredStore()
        val requests = mutableListOf<dev.crumble.android.network.HttpRequestData>()
        val result = PushSync(store, "install", true, { "rotated-token" }, client = { connection ->
            client(connection.url) { request ->
                requests += request
                when (request.pathWithQuery) {
                    "/v1/push" -> 200 to """{"serverId":"server-one","transports":{"apns":false,"fcm":true}}"""
                    "/v1/push/devices/install" -> 200 to """{"serverId":"server-one","remoteDelivery":true}"""
                    else -> 500 to ""
                }
            }
        }).run()
        assertEquals(PushSyncResult.SUCCESS, result)
        assertEquals("rotated-token", store.read().token)
        assertTrue(store.read().current!!.remoteDelivery)
        assertEquals("server-one", store.read().current!!.serverId)
        assertTrue(String(requests.last().body!!).contains("\"token\":\"rotated-token\""))
    }

    @Test
    fun tokenFetchThatRacesWithNewerTokenDoesNotTouchNetwork() = runTest {
        val store = configuredStore()
        var requests = 0
        val result = PushSync(store, "install", true, {
            store.updateToken("newer-token")
            "old-token"
        }, client = { connection ->
            client(connection.url) {
                requests++
                500 to ""
            }
        }).run()
        assertEquals(PushSyncResult.RETRY, result)
        assertEquals("newer-token", store.read().token)
        assertEquals(0, requests)
    }

    @Test
    fun unreachableOldServerDoesNotBlockCurrentButRequestsRetry() = runTest {
        val store = configuredStore()
        val old = store.read().current!!
        store.configure("http://two.example", "two", enabled = true)
        val result = PushSync(store, "install", true, { "token" }, client = { connection ->
            client(connection.url) { request ->
                if (connection == old) throw java.io.IOException("offline")
                when (request.pathWithQuery) {
                    "/v1/push" -> 200 to """{"serverId":"server-two","transports":{"apns":false,"fcm":true}}"""
                    "/v1/push/devices/install" -> 200 to """{"serverId":"server-two","remoteDelivery":true}"""
                    else -> 500 to ""
                }
            }
        }).run()
        assertEquals(PushSyncResult.RETRY, result)
        assertEquals(listOf(old), store.read().retired)
        assertEquals("server-two", store.read().current!!.serverId)
    }

    @Test
    fun staleRegistrationResponseIsRejectedAfterConnectionSwitch() = runTest {
        val store = configuredStore()
        var switched = false
        val result = PushSync(store, "install", true, { "token" }, client = { connection ->
            client(connection.url) { request ->
                if (request.pathWithQuery == "/v1/push/devices/install" && !switched) {
                    switched = true
                    store.configure("http://two.example", "two", enabled = true)
                }
                when (request.pathWithQuery) {
                    "/v1/push" -> 200 to """{"serverId":"server-one","transports":{"apns":false,"fcm":true}}"""
                    "/v1/push/devices/install" -> 200 to """{"serverId":"server-one","remoteDelivery":true}"""
                    else -> 500 to ""
                }
            }
        }).run()
        assertEquals(PushSyncResult.RETRY, result)
        assertEquals("http://two.example", store.read().current!!.url)
        assertNull(store.read().current!!.serverId)
    }

    @Test
    fun staleRegistrationResponseIsRejectedAfterTokenRotation() = runTest {
        val store = configuredStore()
        var rotated = false
        val result = PushSync(store, "install", true, { "first-token" }, client = { connection ->
            client(connection.url) { request ->
                if (request.pathWithQuery == "/v1/push/devices/install" && !rotated) {
                    rotated = true
                    store.updateToken("newer-token")
                }
                when (request.pathWithQuery) {
                    "/v1/push" -> 200 to """{"serverId":"server-one","transports":{"apns":false,"fcm":true}}"""
                    "/v1/push/devices/install" -> 200 to """{"serverId":"server-one","remoteDelivery":true}"""
                    else -> 500 to ""
                }
            }
        }).run()
        assertEquals(PushSyncResult.RETRY, result)
        assertEquals("newer-token", store.read().token)
        assertNull(store.read().current!!.serverId)
    }

    @Test
    fun notFoundFallsBackLocallyAndCleansPendingRemoval() = runTest {
        val store = configuredStore()
        val old = store.read().current!!
        store.configure("http://two.example", "two", enabled = true)
        val result = PushSync(store, "install", true, { "token" }, client = { connection ->
            client(connection.url) { request ->
                if (connection == old && request.method == "DELETE") 404 to "" else 404 to ""
            }
        }).run()
        assertEquals(PushSyncResult.SUCCESS, result)
        assertTrue(store.read().retired.isEmpty())
        assertFalse(store.read().current!!.remoteDelivery)
    }

    @Test
    fun permanentRegistrationAuthFailureIsFailure() = runTest {
        val store = configuredStore()
        val result = PushSync(store, "install", true, { "token" }, client = { connection ->
            client(connection.url) { request ->
                when (request.pathWithQuery) {
                    "/v1/push" -> 200 to """{"serverId":"server-one","transports":{"apns":false,"fcm":true}}"""
                    else -> 401 to ""
                }
            }
        }).run()
        assertEquals(PushSyncResult.FAILURE, result)
    }

    @Test(expected = CancellationException::class)
    fun cancellationDuringTokenFetchRethrows() = runTest {
        val store = configuredStore()
        PushSync(store, "install", true, { throw CancellationException("cancelled") }).run()
    }
}
