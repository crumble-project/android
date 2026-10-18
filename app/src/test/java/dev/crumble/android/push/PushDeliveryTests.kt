package dev.crumble.android.push

import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.NotificationContext
import dev.crumble.android.model.Route
import dev.crumble.android.storage.InMemoryCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushDeliveryTests {
    private fun event(
        id: String = "event-1",
        server: String = "server-one",
        origin: String? = "user",
    ) = ActivityEvent(
        type = "reply",
        agentId = "agent-one",
        agentName = "Atlas",
        eventId = id,
        serverId = server,
        origin = origin,
        preview = "Done",
    )

    private fun context(route: Route? = null, active: Boolean = false) =
        NotificationContext(appActive = active, route = route)

    private fun registeredStore(remote: Boolean = false): Pair<PushStateStore, PushConnection> {
        val store = PushStateStore(InMemoryCredentialStore())
        store.configure("https://one.example", "one", enabled = true)
        val revision = store.read().revision
        assertTrue(store.registered(revision, "server-one", remote))
        return store to store.read().current!!
    }

    @Test
    fun remoteModeLeavesSseQuietUntilFcmClaimsReply() {
        val (store, connection) = registeredStore(remote = true)
        val delivery = PushDelivery(store)
        assertNull(delivery.local(event(), context(), allowed = true))
        assertTrue(store.read().seen.isEmpty())

        val note = delivery.remote(event(), context(), allowed = true)
        assertNotNull(note)
        assertEquals("Atlas", note?.title)
    }

    @Test
    fun visibleSseMarksSeenSoDelayedRemoteReplyStaysQuiet() {
        val (store, connection) = registeredStore(remote = true)
        val delivery = PushDelivery(store)
        val visible = context(route = Route.AgentChat("agent-one"), active = true)
        assertNull(delivery.local(event(), visible, allowed = true))
        assertTrue(store.read().seen.isNotEmpty())
        assertNull(delivery.remote(event(), context(), allowed = true))
    }

    @Test
    fun fallbackDeduplicatesSseThenPushAndPushThenSse() {
        val (store, connection) = registeredStore(remote = false)
        val delivery = PushDelivery(store)
        assertNotNull(delivery.local(event(), context(), allowed = true))
        assertNull(delivery.remote(event(), context(), allowed = true))

        val second = event(id = "event-2")
        assertNotNull(delivery.remote(second, context(), allowed = true))
        assertNull(delivery.local(second, context(), allowed = true))
    }

    @Test
    fun delegatedBotRepliesStayQuiet() {
        val (store, _) = registeredStore(remote = false)
        val delivery = PushDelivery(store)
        assertNull(delivery.local(event(origin = "bot"), context(), allowed = true))
        assertNull(delivery.remote(event(id = "event-2", origin = "bot"), context(), allowed = true))
    }

    @Test
    fun deniedNotificationPermissionDoesNotConsumeRemoteReply() {
        val (store, _) = registeredStore(remote = true)
        val delivery = PushDelivery(store)
        assertNull(delivery.remote(event(), context(), allowed = false))
        assertTrue(store.read().seen.isEmpty())
        assertNotNull(delivery.remote(event(), context(), allowed = true))
    }

    @Test
    fun remoteRejectsWrongServerAndDisabledConnection() {
        val (store, _) = registeredStore(remote = true)
        val delivery = PushDelivery(store)
        assertNull(delivery.remote(event(server = "server-other"), context(), allowed = true))
        assertNotNull(delivery.remote(event(), context(), allowed = true))

        store.configure("https://one.example", "one", enabled = false)
        assertNull(delivery.remote(event(id = "event-2"), context(), allowed = true))
    }

    @Test
    fun remoteDedupPersistsAcrossNewStoreInstance() {
        val credentials = InMemoryCredentialStore()
        val first = PushStateStore(credentials)
        first.configure("https://one.example", "one", enabled = true)
        val revision = first.read().revision
        first.registered(revision, "server-one", remoteDelivery = true)
        assertNotNull(PushDelivery(first).remote(event(), context(), allowed = true))

        val reopened = PushStateStore(credentials)
        assertNull(PushDelivery(reopened).remote(event(), context(), allowed = true))
    }

    @Test
    fun tapRequiresCurrentConnectionIdentityAndServer() {
        val store = PushStateStore(InMemoryCredentialStore())
        store.configure("https://one.example", "one", enabled = true)
        val revision = store.read().revision
        assertTrue(store.registered(revision, "server-one", remoteDelivery = true))
        val connection = store.read().current!!
        val identity = connection.identity()
        assertEquals("agent-one", store.agentForTap("server-one", identity, "agent-one"))
        assertNull(store.agentForTap("server-other", identity, "agent-one"))
        assertNull(store.agentForTap("server-one", "wrong-identity", "agent-one"))

        store.configure("https://two.example", "two", enabled = true)
        assertNull(store.agentForTap("server-one", identity, "agent-one"))
    }

    @Test
    fun legacyTapWithoutServerIdWorksOnlyForMatchingIdentity() {
        val store = PushStateStore(InMemoryCredentialStore())
        store.configure("https://one.example", "one", enabled = true)
        val identity = store.read().current!!.identity()
        assertEquals("agent-one", store.agentForTap(null, identity, "agent-one"))
        assertNull(store.agentForTap(null, "wrong-identity", "agent-one"))
        assertNull(store.agentForTap(null, identity, ""))
    }
}
