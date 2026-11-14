package dev.crumble.android

import dev.crumble.android.generated.CallRequest
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CallLine
import dev.crumble.android.model.CallRequestRef
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageCall
import dev.crumble.android.model.MessageOrigin
import dev.crumble.android.model.callLines
import dev.crumble.android.model.callNoticeDetail
import dev.crumble.android.model.callRequestForNotice
import dev.crumble.android.model.callStateLabel
import dev.crumble.android.model.noticeTitle
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CallTransport(private val handler: (HttpRequestData) -> Pair<Int, String>) : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        held["${request.method} ${request.pathWithQuery}"]?.await()
        val (status, body) = handler(request)
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun count(key: String) = requests.count { "${it.method} ${it.pathWithQuery}" == key }
}

private fun callRequestFixture(
    status: String = "pending",
    outcome: String? = null,
    id: String = "c1",
    callId: String? = null,
) = """{"id":"$id","agentId":"a1","number":"+4930123","callee":"Dr. Weber","goal":"Book a visit","opening":"Hello, I am an AI assistant","status":"$status","callId":${callId?.let { "\"$it\"" }},"conversationId":null,"outcome":${outcome?.let { "\"$it\"" }},"error":null,"requestedAt":"2026-11-05T10:00:00Z","decidedAt":null,"endedAt":null}"""

private fun decoded(status: String = "pending", outcome: String? = null, id: String = "c1", callId: String? = null) =
    CrumbleJson.decodeFromString<CallRequest>(callRequestFixture(status, outcome, id, callId))

private val callRequestMessage = Message(id = "t1", entryId = "t", role = "toolResult", callRequest = CallRequestRef("c1", "Dr. Weber"))

private fun notice(kind: String, requestId: String?, text: String) = Message(
    id = "n1", role = "user", content = kotlinx.serialization.json.JsonPrimitive(text),
    origin = MessageOrigin(kind = kind), requestId = requestId,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CallRequestTests {
    private val calls = "/v1/agents/a1/call-requests"

    private fun TestScope.open(transport: CallTransport): AppModel {
        val client = APIClient(baseURL = "http://crumble.test/", token = "", transport = transport)
        val model = AppModel(client = client, scope = TestScope(StandardTestDispatcher(testScheduler)))
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(callRequestMessage)))
        testScheduler.runCurrent()
        return model
    }

    @Test
    fun requestCallResultBecomesARef() = runTest {
        val transport = CallTransport {
            200 to """{"messages":[{"id":"t1","entryId":"t","role":"toolResult","content":"","replyReferences":[],"toolName":"request_call","toolDetails":${callRequestFixture()}},{"id":"t2","entryId":"t","role":"toolResult","content":"","replyReferences":[],"toolName":"request_call","isError":true}],"hasMore":false,"oldestEntryId":null}"""
        }
        val client = APIClient(baseURL = "http://crumble.test/", token = "", transport = transport)
        val messages = client.chatHistory("a1", before = "t", limit = 10).messages.orEmpty()
        assertEquals(CallRequestRef("c1", "Dr. Weber"), messages[0].callRequest)
        assertNull(messages[1].callRequest)
        assertEquals(listOf(true, false), messages.map { ChatRow.shownInChat(it) })
    }

    @Test
    fun calleeCarriesFromTheCallEvent() = runTest {
        val transport = CallTransport {
            200 to """{"messages":[{"id":"e1","entryId":"e","role":"event","content":"started","replyReferences":[],"kind":"call-started","call":{"callId":"v1","direction":"outbound","callee":"Dr. Weber"}}],"hasMore":false,"oldestEntryId":null}"""
        }
        val client = APIClient(baseURL = "http://crumble.test/", token = "", transport = transport)
        val event = client.chatHistory("a1", before = "e", limit = 10).messages.orEmpty().single()
        assertEquals("Dr. Weber", event.call?.callee)
    }

    @Test
    fun anOutsideCallStartedEventHasNoRow() {
        val ownerCall = Message(id = "e1", role = "event", kind = "call-started", content = kotlinx.serialization.json.JsonPrimitive("Hi"), call = MessageCall("v1", "outbound"))
        val outside = ownerCall.copy(call = MessageCall("v1", "outbound", "Dr. Weber"))
        assertTrue(ChatRow.shownInChat(ownerCall))
        assertEquals(false, ChatRow.shownInChat(outside))
    }

    @Test
    fun loadsRequestsWithTheChatAndApprovesWithAPendingState() = runTest {
        var status = "pending"
        val transport = CallTransport { req ->
            when (req.pathWithQuery) {
                calls -> 200 to "[${callRequestFixture(status)}]"
                "$calls/c1/approve" -> { status = "calling"; 200 to callRequestFixture("calling") }
                else -> 503 to ""
            }
        }
        val gate = CompletableDeferred<Unit>().also { transport.held["POST $calls/c1/approve"] = it }
        val model = open(transport)
        assertEquals("pending", model.callRequests.single().status)

        model.decideCallRequest("c1", approve = true)
        testScheduler.runCurrent()
        assertTrue("c1" in model.decidingIDs)
        assertEquals("pending", model.callRequests.single().status)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(model.decidingIDs.isEmpty())
        assertEquals("calling", model.callRequests.single().status)
        model.cancelStream()
    }

    @Test
    fun aRefusedApprovalKeepsTheRequestPendingWithTheServersError() = runTest {
        val transport = CallTransport { req ->
            when (req.pathWithQuery) {
                calls -> 200 to "[${callRequestFixture()}]"
                "$calls/c1/approve" -> 409 to """{"error":"The voice line is busy."}"""
                else -> 503 to ""
            }
        }
        val model = open(transport)
        model.decideCallRequest("c1", approve = true)
        testScheduler.runCurrent()
        assertEquals("pending", model.callRequests.single().status)
        assertEquals("The voice line is busy.", model.decisionErrors["c1"])
        assertTrue(model.decidingIDs.isEmpty())
        model.cancelStream()
    }

    @Test
    fun decliningReplacesTheRequestWithTheServersAnswer() = runTest {
        var status = "pending"
        val transport = CallTransport { req ->
            when (req.pathWithQuery) {
                calls -> 200 to "[${callRequestFixture(status)}]"
                "$calls/c1/decline" -> { status = "declined"; 200 to callRequestFixture("declined") }
                else -> 503 to ""
            }
        }
        val model = open(transport)
        model.decideCallRequest("c1", approve = false)
        testScheduler.runCurrent()
        assertEquals("declined", model.callRequests.single().status)
        model.cancelStream()
    }

    @Test
    fun aLateAnswerForAnotherBotDoesNotLand() = runTest {
        val transport = CallTransport { req ->
            when (req.pathWithQuery) {
                calls -> 200 to "[${callRequestFixture()}]"
                "$calls/c1/approve" -> 200 to callRequestFixture("calling")
                else -> 503 to ""
            }
        }
        val gate = CompletableDeferred<Unit>().also { transport.held["POST $calls/c1/approve"] = it }
        val model = open(transport)
        model.decideCallRequest("c1", approve = true)
        testScheduler.runCurrent()
        model.selectAgent("a2")
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(model.callRequests.isEmpty())
        model.cancelStream()
    }

    @Test
    fun pendingAndCallingRequestsRefreshEveryFiveSeconds() = runTest {
        var status = "pending"
        val transport = CallTransport { req -> if (req.pathWithQuery == calls) 200 to "[${callRequestFixture(status)}]" else 503 to "" }
        val model = open(transport)
        assertEquals(1, transport.count("GET $calls"))

        status = "calling"
        testScheduler.advanceTimeBy(5_100)
        testScheduler.runCurrent()
        assertEquals("calling", model.callRequests.single().status)

        status = "ended"
        testScheduler.advanceTimeBy(5_100)
        testScheduler.runCurrent()
        assertEquals("ended", model.callRequests.single().status)
        val reads = transport.count("GET $calls")
        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()
        assertEquals(reads, transport.count("GET $calls"))
        model.cancelStream()
    }

    @Test
    fun theTranscriptRefreshesEveryTwoSecondsWhileTheCallGoesOn() = runTest {
        var status = "calling"
        var lines = 1
        val transport = CallTransport { req ->
            when (req.pathWithQuery) {
                calls -> 200 to "[${callRequestFixture(status)}]"
                "$calls/c1/transcript?limit=200" -> 200 to """{"messages":[${(1..lines).joinToString(",") { """{"id":"m$it","entryId":"m$it","role":"user","content":"line $it","replyReferences":[]}""" }}],"hasMore":false,"oldestEntryId":null}"""
                else -> 503 to ""
            }
        }
        val model = open(transport)
        model.openCallTranscript("c1")
        testScheduler.runCurrent()
        assertEquals(listOf("line 1"), model.callTranscript!!.lines.map { it.text })

        lines = 2
        testScheduler.advanceTimeBy(2_100)
        testScheduler.runCurrent()
        assertEquals(2, model.callTranscript!!.lines.size)

        status = "ended"
        testScheduler.advanceTimeBy(5_100)
        testScheduler.runCurrent()
        val reads = transport.count("GET $calls/c1/transcript?limit=200")
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(reads, transport.count("GET $calls/c1/transcript?limit=200"))
        model.cancelStream()
    }

    @Test
    fun stateLabels() {
        assertEquals("Declined", callStateLabel(decoded("declined")))
        assertEquals("Calling", callStateLabel(decoded("calling")))
        assertEquals("Answered", callStateLabel(decoded("ended", "answered")))
        assertEquals("Not answered", callStateLabel(decoded("ended", "unanswered")))
        assertEquals("Busy", callStateLabel(decoded("ended", "busy")))
        assertEquals("Failed", callStateLabel(decoded("ended", "failed")))
        assertEquals("Ended", callStateLabel(decoded("ended")))
        assertEquals("Ended", callStateLabel(decoded("ended", "somethingNew")))
    }

    @Test
    fun noticesBelongToTheirRequest() {
        val requests = listOf(decoded("ended", id = "c1", callId = "v1"), decoded("declined", id = "c2"))
        assertEquals("c1", callRequestForNotice(notice("call", "voice-call:v1:ended", "x"), requests)?.id)
        assertEquals("c2", callRequestForNotice(notice("call_request", "call-request:c2", "x"), requests)?.id)
        assertNull(callRequestForNotice(notice("call", "voice-call:v9:ended", "x"), requests))
        assertNull(callRequestForNotice(notice("call", null, "x"), requests))
        assertNull(callRequestForNotice(notice("task", "voice-call:v1:ended", "x"), requests))
    }

    @Test
    fun noticeDetailDropsTheLeadingCallTo() {
        val request = decoded()
        assertEquals("ended after 2 minutes", callNoticeDetail(notice("call", null, "Call to Dr. Weber ended after 2 minutes"), request))
        assertEquals("Nobody picked up", callNoticeDetail(notice("call", null, "Nobody picked up"), request))
    }

    @Test
    fun callRequestNoticesHaveATitle() {
        assertEquals("Call request", noticeTitle(notice("call_request", "call-request:c1", "x")))
        assertEquals("Bot request", noticeTitle(notice("bot_request", null, "x")))
    }

    @Test
    fun transcriptLinesKeepTheCalleeAndTheBotsText() {
        fun msg(id: String, role: String, text: String) = Message(id = id, role = role, content = kotlinx.serialization.json.JsonPrimitive(text))
        val lines = callLines(
            listOf(
                msg("1", "user", "Hello"),
                msg("2", "assistant", "Good day"),
                msg("3", "assistant", "  "),
                msg("4", "toolResult", "ignored"),
                msg("5", "event", "ignored"),
            ),
        )
        assertEquals(listOf(CallLine("1", true, "Hello"), CallLine("2", false, "Good day")), lines)
    }
}
