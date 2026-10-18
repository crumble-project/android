package dev.crumble.android.push

import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.storage.InMemoryCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushStateTests {
    @Test
    fun statePersistsAcrossStoreReopen() {
        val credentials = InMemoryCredentialStore()
        val first = PushStateStore(credentials)
        assertTrue(first.configure("https://one.example/", "bearer", enabled = true))
        val revision = first.read().revision
        assertTrue(first.updateToken("fcm-token"))
        assertTrue(first.registered(revision = revision + 1, serverId = "server-one", remoteDelivery = true))

        val reopened = PushStateStore(credentials)
        assertEquals("https://one.example", reopened.read().current?.url)
        assertEquals("fcm-token", reopened.read().token)
        assertTrue(reopened.accepts("server-one"))
    }

    @Test
    fun staleRegistrationIsRejectedAfterTokenChangeOrServerSwitch() {
        val store = PushStateStore(InMemoryCredentialStore())
        assertTrue(store.configure("https://one.example", "one", enabled = true))
        val firstRevision = store.read().revision
        assertTrue(store.updateToken("token-one"))
        assertFalse(store.registered(firstRevision, "server-one", remoteDelivery = true))
        assertTrue(store.configure("https://two.example", "two", enabled = true))
        assertFalse(store.registered(firstRevision + 1, "server-two", remoteDelivery = true))
        assertNull(store.read().current?.serverId)
    }

    @Test
    fun switchingConnectionsQueuesOldUnregisterWithoutDroppingIt() {
        val store = PushStateStore(InMemoryCredentialStore())
        assertTrue(store.configure("https://one.example", "one", enabled = true))
        val old = store.read().current!!
        assertTrue(store.configure("https://two.example", "two", enabled = true))
        val switched = store.read()
        assertEquals("https://two.example", switched.current?.url)
        assertEquals(listOf(old), switched.retired)

        store.removed(old)
        assertTrue(store.read().retired.isEmpty())
        assertEquals("https://two.example", store.read().current?.url)
    }

    @Test
    fun serverRegistrationControlsAcceptanceAndEnabledState() {
        val store = PushStateStore(InMemoryCredentialStore())
        store.configure("https://one.example", "one", enabled = true)
        val revision = store.read().revision
        assertTrue(store.registered(revision, "server-one", remoteDelivery = true))
        assertTrue(store.accepts("server-one"))
        assertFalse(store.accepts("server-other"))

        store.configure("https://one.example", "one", enabled = false)
        assertFalse(store.accepts("server-one"))
        store.configure("https://one.example", "one", enabled = true)
        assertTrue(store.accepts("server-one"))
    }

    @Test
    fun eventJournalIsScopedByServerAndBoundedTo512Entries() {
        val store = PushStateStore(InMemoryCredentialStore())
        store.configure("https://one.example", "one", enabled = true)
        val connection = store.read().current!!
        val first = ActivityEvent(type = "reply", agentId = "a", eventId = "event-0", serverId = "server-one")
        assertTrue(store.claim(first, connection))
        assertFalse(store.claim(first, connection))
        val otherServer = first.copy(serverId = "server-two")
        assertTrue(store.claim(otherServer, connection))
        assertFalse(store.claim(otherServer, connection))

        for (i in 1..510) {
            assertTrue(store.claim(first.copy(eventId = "event-$i"), connection))
        }
        assertEquals(512, store.read().seen.size)
        assertTrue(store.claim(first.copy(eventId = "event-511"), connection))
        assertEquals(512, store.read().seen.size)
        assertTrue(store.claim(first, connection))
        assertFalse(store.claim(first, connection))
    }

    @Test
    fun legacyEntryIdIsUsedWhenEventIdIsMissing() {
        val store = PushStateStore(InMemoryCredentialStore())
        store.configure("https://one.example", "one", enabled = true)
        val connection = store.read().current!!
        val legacy = ActivityEvent(type = "reply", agentId = "a", entryId = "entry-1", serverId = "server-one")
        assertTrue(store.claim(legacy, connection))
        assertFalse(store.claim(legacy, connection))

        val noIdentity = legacy.copy(entryId = null)
        assertTrue(store.claim(noIdentity, connection))
        assertTrue(store.claim(noIdentity, connection))
    }
}
