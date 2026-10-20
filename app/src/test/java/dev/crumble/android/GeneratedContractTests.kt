package dev.crumble.android

import dev.crumble.android.generated.OptionalField
import dev.crumble.android.generated.UpdateScheduleRequest
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumblePatchJson
import dev.crumble.android.model.OutgoingAttachment
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.APIError
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.SSEClosedError
import dev.crumble.android.network.friendlyError
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class GeneratedContractTests {
    @Test
    fun patchRoundTripDistinguishesAbsentFromExplicitNull() {
        val absent = CrumblePatchJson.decodeFromString<UpdateScheduleRequest>("{}")
        assertEquals(OptionalField.Absent, absent.cron)
        assertEquals("{}", CrumblePatchJson.encodeToString(absent))
        val clear = CrumblePatchJson.decodeFromString<UpdateScheduleRequest>("""{"cron":null,"enabled":false}""")
        assertEquals(OptionalField.Present<String?>(null), clear.cron)
        val body = CrumblePatchJson.parseToJsonElement(CrumblePatchJson.encodeToString(clear)).jsonObject
        assertEquals(JsonNull, body["cron"])
        assertEquals(JsonPrimitive(false), body["enabled"])
        assertFalse(body.containsKey("runAt"))
    }

    @Test(expected = MissingFieldException::class)
    fun generatedResponsesRequireContractFields() {
        CrumblePatchJson.decodeFromString<dev.crumble.android.generated.Agent>("""{"id":"a1"}""")
    }

    @Test
    fun successDecodesGeneratedWireTypesAndIgnoresFutureResponseFields() = runTest {
        val response = agentFixture("a1").dropLast(1) + ""","future":{"nested":[true,null,7]}}"""
        val (client, transport) = StubTransport.client(token = "owner") { 200 to "[$response]" }
        assertEquals("Atlas", client.agents().single().name)
        assertEquals("Bearer owner", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun authenticationFailureAndStructuredApiErrorsKeepBoundaryMapping() = runTest {
        for (status in listOf(401, 409)) {
            val (client, transport) = StubTransport.client(token = "owner") {
                status to if (status == 401) "bad token" else """{"error":"Invalid request","details":[{"message":"Choose a bot"}]}"""
            }
            try {
                client.deleteAgent("a1")
                fail("expected APIError")
            } catch (error: APIError) {
                assertEquals(status, error.statusCode)
                assertEquals("DELETE", error.method)
                assertEquals("/v1/agents/a1", error.path)
                assertEquals(if (status == 401) "Authentication failed. Check the server token in Settings." else "Invalid request: Choose a bot", friendlyError(error))
                assertEquals("Bearer owner", transport.requests.single().headers["Authorization"])
            }
        }
        val (client, _) = StubTransport.client { 500 to "x".repeat(20_000) }
        try {
            client.agents()
            fail("expected APIError")
        } catch (error: APIError) {
            assertEquals(8192, error.body.length)
        }
    }

    @Test
    fun attachmentOperationKeepsBinaryBytesAndEscapesEveryPathParameter() = runTest {
        val bytes = byteArrayOf(0, -1, 10, 127)
        val requests = mutableListOf<HttpRequestData>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequestData): HttpResponseData {
                requests.add(request)
                return HttpResponseData(200, bytes)
            }
            override suspend fun openStream(request: HttpRequestData): HttpStreamResponse = error("not streaming")
        }
        val client = APIClient("http://crumble.test/", "", transport)
        assertArrayEquals(bytes, client.attachment("bot/id", "entry?#", 2))
        assertEquals("/v1/agents/bot%2Fid/attachments/entry%3F%23/2", requests.single().pathWithQuery)
        assertNull(requests.single().headers["Authorization"])
        assertNull(requests.single().headers["Content-Type"])
    }

    @Test
    fun generatedMessageOperationPreservesAttachmentTimeoutAndOptionalOmission() = runTest {
        val (client, transport) = StubTransport.client { 202 to executionFixture }
        client.sendChatMessage("a1", "text", "request", "")
        client.sendChatMessage("a1", "", "request", "", listOf(OutgoingAttachment("image", "image/png", "AAAA")))
        assertEquals(listOf(20_000, 120_000), transport.requests.map { it.timeoutMs })
        val body = Json.parseToJsonElement(transport.requests.first().body!!.toString(Charsets.UTF_8)).jsonObject
        assertFalse(body.containsKey("replyTo"))
        assertFalse(body.containsKey("attachments"))
    }

    @Test
    fun streamDecodesProjectedSnapshotAndAlwaysClosesOnCancellation() = runTest {
        var closed = false
        val snapshot = snapshotFixture(ChatSnapshot(agentId = "a1", status = "running"))
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequestData): HttpResponseData = error("not HTTP")
            override suspend fun openStream(request: HttpRequestData): HttpStreamResponse {
                assertEquals("Bearer owner", request.headers["Authorization"])
                assertEquals(86_400_000, request.timeoutMs)
                val bytes = "event: heartbeat\ndata: {}\n\nevent: unknown\ndata: {}\n\nevent: snapshot\ndata: $snapshot\n\n".toByteArray()
                return HttpStreamResponse(200, ByteArrayInputStream(bytes)) { closed = true }
            }
        }
        try {
            APIClient("http://crumble.test", "owner", transport).chatEvents("a1") {
                assertEquals("a1", it.agentId)
                assertTrue(it.isRunning)
                throw CancellationException("cancel")
            }
        } catch (_: CancellationException) {
        }
        assertTrue(closed)
    }

    @Test
    fun activityStreamUsesGeneratedUnionAndRetainsOpenProviderJson() = runTest {
        val (client, _) = StubTransport.client {
            200 to "event: activity\ndata: {\"type\":\"chat-preview\",\"agentId\":\"a1\",\"agentName\":\"Atlas\",\"lastMessage\":\"Hello\"}\n\n"
        }
        var preview: String? = null
        try {
            client.activityEvents { preview = it.lastMessage }
        } catch (_: SSEClosedError) {
        }
        assertEquals("Hello", preview)
        val message = CrumblePatchJson.decodeFromString<dev.crumble.android.generated.TranscriptMessage>("""{"id":"m1","entryId":"e1","role":"assistant","content":"","blocks":{"provider":[null,true,1,"text"]}}""")
        assertEquals(JsonPrimitive(true), message.blocks!!.jsonObject["provider"]!!.jsonArray[1])
    }
}
