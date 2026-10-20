package dev.crumble.android

import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.network.APIClient
import dev.crumble.android.push.PushPresence
import dev.crumble.android.push.PushRegistration
import dev.crumble.android.push.parsePushReplyData
import dev.crumble.android.push.parsePushReplyPayload
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushContractTests {
    @Test
    fun parsesValidReplyAndNormalizesEmptyEntryId() {
        val event = parsePushReplyPayload(
            """{"type":"reply","eventId":"e1","serverId":"s1","agentId":"a1","agentName":"Atlas","entryId":"","origin":"user","createdAt":"2026-10-18T10:00:00Z"}""",
        )
        assertEquals(ActivityEvent(type = "reply", agentId = "a1", agentName = "Atlas", origin = "user", preview = "", eventId = "e1", serverId = "s1", createdAt = "2026-10-18T10:00:00Z"), event)
    }

    @Test
    fun rejectsInvalidReplyAndPreservesBotOriginForPolicy() {
        assertNull(parsePushReplyPayload("""{"type":"reply","eventId":"","serverId":"s1","agentId":"a1"}"""))
        assertEquals("bot", parsePushReplyData(mapOf(
            "type" to "reply", "eventId" to "e1", "serverId" to "s1", "agentId" to "a1", "origin" to "bot",
        ))?.origin)
    }

    @Test
    fun pushApiUsesEscapedInstallationIdAndExplicitPresenceNull() = runTest {
        val (client, stub) = StubTransport.client(token = "token") { request ->
            when (request.method) {
                "GET" -> 200 to """{"serverId":"s1","transports":{"apns":false,"fcm":true}}"""
                "PUT" -> if (request.pathWithQuery.endsWith("/presence")) 204 to "" else 200 to """{"serverId":"s1","remoteDelivery":true}"""
                "DELETE" -> 204 to ""
                else -> 500 to ""
            }
        }
        assertTrue(client.pushInfo().transports["fcm"] == true)
        client.registerPushDevice("installation/id", PushRegistration(token = "fcm-token"))
        val registration = stub.requests[1]
        assertEquals("PUT", registration.method)
        assertEquals("/v1/push/devices/installation%2Fid", registration.pathWithQuery)
        assertEquals("Bearer token", registration.headers["Authorization"])
        assertTrue(String(registration.body!!, StandardCharsets.UTF_8).contains("\"platform\":\"android\""))
        client.updatePushPresence("installation/id", PushPresence(active = false, agentId = null))
        val presenceBody = String(stub.requests[2].body!!, StandardCharsets.UTF_8)
        assertTrue(presenceBody.contains("\"agentId\":null"))
        client.unregisterPushDevice("installation/id")
        assertEquals("DELETE", stub.requests[3].method)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankInstallationId() = runTest {
        val (client, _) = StubTransport.client { 204 to "" }
        client.unregisterPushDevice(" ")
    }
}
