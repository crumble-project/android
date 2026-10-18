package dev.crumble.android

import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AgentDraft
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.AttachmentError
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.displayLabel
import dev.crumble.android.model.Memory
import dev.crumble.android.model.MentionCompletion
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageDeliveryState
import dev.crumble.android.model.NotificationContext
import dev.crumble.android.model.OutgoingAttachment
import dev.crumble.android.model.PendingAttachment
import dev.crumble.android.model.Route
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.Skill
import dev.crumble.android.model.insertingNewline
import dev.crumble.android.model.loadAttachment
import dev.crumble.android.model.mentionQuery
import dev.crumble.android.model.mergeMessages
import dev.crumble.android.model.newRequestID
import dev.crumble.android.model.notification
import dev.crumble.android.model.taskRunDate
import dev.crumble.android.model.validateTask
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.APIError
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.SSEClosedError
import dev.crumble.android.network.SSEParser
import dev.crumble.android.network.SignInError
import dev.crumble.android.network.friendlyError
import dev.crumble.android.network.oauthResult
import dev.crumble.android.ui.BotLook
import dev.crumble.android.ui.MarkdownBlock
import dev.crumble.android.ui.agentAvatarSeed
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StubTransport(
    private val handler: (HttpRequestData) -> Pair<Int, String>,
) : HttpTransport {
    val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        val (status, body) = handler(request)
        return HttpResponseData(statusCode = status, body = body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse {
        requests.add(request)
        val (status, body) = handler(request)
        return HttpStreamResponse(
            statusCode = status,
            stream = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    companion object {
        fun client(
            token: String = "",
            handler: (HttpRequestData) -> Pair<Int, String>,
        ): Pair<APIClient, StubTransport> {
            val transport = StubTransport(handler)
            return APIClient(baseURL = "http://crumble.test/", token = token, transport = transport) to transport
        }
    }
}

/** A transport whose response can be released after local optimistic state changes. */
private class GatedTransport(
    private val responseStatus: Int,
    private val responseBody: String,
) : HttpTransport {
    val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())
    val gate = CompletableDeferred<Unit>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        gate.await()
        return HttpResponseData(responseStatus, responseBody.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse {
        throw UnsupportedOperationException("streaming is not used by gated mutation tests")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CrumbleTests {

    @Test
    fun personalSkillFailureRestoresDraftAndSkillsWithoutClobberingNewerAgentFields() = runTest {
        val oldSkill = Skill(id = "old", name = "Old", instructions = "Keep this")
        val transport = GatedTransport(500, "{\"error\":\"busy\"}")
        val client = APIClient(baseURL = "http://crumble.test/", token = "token", transport = transport)
        val model = AppModel(client = client, scope = TestScope(StandardTestDispatcher(testScheduler)))
        model.route = Route.Skills
        model.agents = listOf(Agent(id = "a1", name = "Before", skills = listOf(oldSkill)))
        model.skillDraft = dev.crumble.android.model.SkillDraft(
            skillID = "",
            agentID = "a1",
            name = "New skill",
            instructions = "Do something",
        )

        model.saveSkill()
        testScheduler.runCurrent()
        assertEquals("New skill", model.agents.single().skills?.last()?.name)
        model.agents = model.agents.map { it.copy(name = "Newer name") }
        transport.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("Newer name", model.agents.single().name)
        assertEquals(listOf(oldSkill), model.agents.single().skills)
        assertEquals("New skill", model.skillDraft?.name)
        assertTrue(model.errorText.contains("busy"))
    }

    @Test
    fun sharedSkillAssignmentFailureRollsBackMembershipWithoutClobberingNewerAgentFields() = runTest {
        val transport = GatedTransport(500, "{\"error\":\"busy\"}")
        val client = APIClient(baseURL = "http://crumble.test/", token = "token", transport = transport)
        val model = AppModel(client = client, scope = TestScope(StandardTestDispatcher(testScheduler)))
        model.sharedSkills = listOf(dev.crumble.android.model.SharedSkill(id = "shared-1", name = "Shared", instructions = "Use it"))
        model.agents = listOf(Agent(id = "a1", name = "Before", sharedSkills = emptyList()))

        model.setSharedSkillEnabled("a1", "shared-1", enabled = true)
        testScheduler.runCurrent()
        assertEquals(listOf("shared-1"), model.agents.single().sharedSkills)
        model.agents = model.agents.map { it.copy(name = "Newer name") }
        transport.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("Newer name", model.agents.single().name)
        assertEquals(emptyList<String>(), model.agents.single().sharedSkills)
        assertTrue(model.errorText.contains("busy"))
    }

    @Test
    fun runningPartialChatShowsOnlyThinkingUntilTurnCompletes() {
        val partial = ChatSnapshot(status = "running", partial = "draft text", agentId = "a1")
        assertEquals(listOf(ChatRow.Thinking), ChatRow.rows(emptyList(), partial, hasMore = false))

        val first = Message(id = "m1", role = "assistant", blocks = JsonPrimitive("first"))
        val second = Message(id = "m2", role = "assistant", blocks = JsonPrimitive("second"))
        val completed = ChatRow.rows(listOf(first, second), ChatSnapshot(status = "idle", partial = null), hasMore = false)
        assertEquals(listOf("m1", "m2"), completed.filterIsInstance<ChatRow.MessageRow>().map { it.message.id })
        assertTrue(completed.none { it is ChatRow.Partial || it is ChatRow.Thinking })
    }

    @Test
    fun activityFeedDecodesOperationalRecordsAndDetails() = runTest {
        val (client, stub) = StubTransport.client { req ->
            assertEquals("/v1/activity/feed", req.pathWithQuery)
            200 to """{"activities":[{"id":"evt-1","agentId":"a1","agentName":"Atlas","entryId":"run-1","timestamp":1720000000000,"kind":"tool-call","details":{"toolName":"search","query":"calendar"}}],"hasMore":true}"""
        }
        val feed = client.activityFeed()
        assertTrue(feed.hasMore)
        assertEquals("evt-1", feed.activities.single().id)
        assertEquals("tool-call", feed.activities.single().kind)
        assertEquals("search", feed.activities.single().displayLabel())
        assertEquals("/v1/activity/feed", stub.requests.single().pathWithQuery)
    }

    @Test
    fun agentsSendBearerAndDecode() = runTest {
        val (client, stub) = StubTransport.client(token = "test-token") {
            200 to """[{"id":"a1","name":"Atlas","enabled":true,"lastMessage":"See you tomorrow."}]"""
        }
        val agents = client.agents()
        assertEquals(listOf("Atlas"), agents.map { it.name })
        assertEquals("See you tomorrow.", agents.first().lastMessage)
        assertEquals("/v1/agents", stub.requests.first().pathWithQuery)
        assertEquals("Bearer test-token", stub.requests.first().headers["Authorization"])
    }

    @Test
    fun updateAgentCanClearCapabilities() = runTest {
        val (client, stub) = StubTransport.client { 200 to """{"id":"a1"}""" }
        client.updateAgent(
            "a1",
            patch = mapOf(
                "tools" to JsonArray(emptyList()),
                "skills" to CrumbleJson.encodeToJsonElement(ListSerializer(Skill.serializer()), emptyList()),
            ),
        )
        val body = String(stub.requests.first().body ?: ByteArray(0), StandardCharsets.UTF_8)
        assertTrue(body.contains(""""tools":[]""") && body.contains(""""skills":[]"""))
    }

    @Test
    fun sharedCatalogIncludesDefaultsAndOptionalSkills() = runTest {
        val (client, _) = StubTransport.client { req ->
            assertEquals("/v1/skills", req.pathWithQuery)
            200 to """[{"id":"common:tasks/SKILL.md","name":"tasks","instructions":"Complete tasks","source":"bundled","path":"tasks/SKILL.md"},{"id":"s1","name":"Writing","instructions":"Write plainly","source":"shared"},{"id":"legacy","name":"Legacy","instructions":"Existing skill"}]"""
        }
        val skills = client.sharedSkills()
        assertEquals(listOf(true, false, false), skills.map { it.isDefault })
        assertEquals("tasks/SKILL.md", skills.first().path)
    }

    @Test
    fun memoryOverviewLoadsEveryBotWithoutSelectingOne() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, stub) = StubTransport.client { req ->
            when (req.pathWithQuery) {
                "/v1/agents" -> 200 to """[{"id":"a1","name":"Atlas"},{"id":"a2","name":"Beacon"}]"""
                "/v1/memories" -> 200 to """[{"id":"shared","content":"For every bot"}]"""
                "/v1/agents/a1/memories" -> 200 to """[{"id":"personal-1","content":"Only Atlas"}]"""
                "/v1/agents/a2/memories" -> 200 to """[{"id":"personal-2","content":"Only Beacon"}]"""
                else -> 404 to ""
            }
        }
        val model = AppModel(client = client, scope = testScope)
        model.openPanel(Route.Memory)
        advanceUntilIdle()
        assertFalse(model.loading)
        assertEquals(Route.Memory, model.route)
        assertEquals("", model.selectedAgentID)
        assertEquals(listOf("a1", "a2"), model.agents.map { it.id })
        assertEquals(listOf("personal-1"), model.agentMemories["a1"]?.map { it.id })
        assertEquals(listOf("personal-2"), model.agentMemories["a2"]?.map { it.id })
        assertEquals(listOf("shared"), model.sharedMemories.map { it.id })
        assertEquals("", model.errorText)
        assertTrue(stub.requests.none { it.pathWithQuery.contains("/chat") })
    }

    @Test
    fun memoryActionsUseTheEntryOwnerWithoutAnActiveBot() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, stub) = StubTransport.client { req ->
            when (req.pathWithQuery) {
                "/v1/agents" -> 200 to """[{"id":"a2","name":"Beacon"}]"""
                "/v1/memories", "/v1/agents/a2/memories" -> 200 to "[]"
                "/v1/agents/a2/memories/m2" ->
                    if (req.method == "DELETE") 204 to "" else 200 to """{"id":"m2","content":"Updated"}"""
                else -> 404 to ""
            }
        }
        val model = AppModel(client = client, scope = testScope)
        model.agentMemories = mapOf("a2" to listOf(Memory(id = "m2", content = "Only Beacon")))
        model.openMemoryForm("m2", agentID = "a2")
        assertEquals("Only Beacon", model.memoryDraft?.content)
        assertEquals("a2", model.memoryDraft?.agentID)
        model.memoryDraft = model.memoryDraft?.copy(content = "Updated")
        model.saveMemory()
        advanceUntilIdle()
        model.deleteMemory("m2", agentID = "a2")
        advanceUntilIdle()
        assertFalse(model.loading)
        assertEquals("", model.errorText)
        assertEquals("", model.selectedAgentID)
        val writes = stub.requests.filter { it.method in listOf("PATCH", "DELETE") }
        assertEquals(listOf("/v1/agents/a2/memories/m2", "/v1/agents/a2/memories/m2"), writes.map { it.pathWithQuery })
    }

    @Test
    fun chatContracts() = runTest {
        val (client, stub) = StubTransport.client { req ->
            when {
                req.pathWithQuery.startsWith("/v1/agents/a1/chat/history") ->
                    200 to """{"messages":[{"entryId":"a1:8","role":"user","content":"older"}],"hasMore":false,"oldestEntryId":"a1:8"}"""
                req.pathWithQuery == "/v1/agents/a1/messages" -> 202 to ""
                else -> 404 to ""
            }
        }
        val history = client.chatHistory("a1", before = "a1:42", limit = 100)
        assertEquals("a1:8", history.messages?.first()?.entryId)
        assertEquals("/v1/agents/a1/chat/history?before=a1%3A42&limit=100", stub.requests.last().pathWithQuery)
        client.sendChatMessage("a1", content = "hello", requestID = "req", replyTo = "a1:41")
        val body = String(stub.requests.last().body ?: ByteArray(0), StandardCharsets.UTF_8)
        assertTrue(body.contains(""""replyTo":"a1:41""""))
    }

    @Test
    fun messagesCarryAttachments() = runTest {
        val (client, stub) = StubTransport.client { 202 to "" }
        client.sendChatMessage(
            "a1",
            content = "",
            requestID = "req",
            replyTo = "",
            attachments = listOf(OutgoingAttachment(name = "dot.png", mimeType = "image/png", data = "AAAA")),
        )
        val bodyStr = String(stub.requests.last().body ?: ByteArray(0), StandardCharsets.UTF_8)
        val obj = CrumbleJson.parseToJsonElement(bodyStr) as JsonObject
        assertNull(obj["replyTo"])
        val attachmentsArr = obj["attachments"] as JsonArray
        assertEquals(JsonPrimitive("image/png"), (attachmentsArr.first() as JsonObject)["mimeType"])
        val msg = CrumbleJson.decodeFromString(
            Message.serializer(),
            """{"id":"a1:5:0","role":"user","content":"","attachments":[{"name":"dot.png","mimeType":"image/png","size":70,"kind":"image"}]}""",
        )
        assertTrue(msg.attachments?.first()?.isImage == true)
        assertTrue(ChatRow.shownInChat(msg))
    }

    @Test
    fun failedSendsKeepTheirAttachments() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { 500 to """{"error":"busy"}""" }
        val model = AppModel(client = client, scope = testScope)
        model.snapshot = ChatSnapshot(status = "idle", agentId = "a1")
        model.attachments = listOf(PendingAttachment(name = "notes.txt", mimeType = "text/plain", data = "hi".toByteArray()))
        model.sendMessage()
        assertTrue(model.attachments.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("notes.txt"), model.attachments.map { it.name })
        assertTrue(model.errorText.contains("busy"))
    }

    @Test
    fun mcpSignInRoundTrip() = runTest {
        val (client, stub) = StubTransport.client { req ->
            when (req.pathWithQuery) {
                "/v1/mcp-servers/s1/oauth" -> 200 to """{"authorizationUrl":"https://auth.example.com/authorize?state=x"}"""
                "/v1/mcp-servers/s1/oauth/callback" -> 200 to """{"id":"s1","name":"Linear","url":"https://mcp.linear.app/mcp","enabled":true,"auth":"oauth"}"""
                else -> 404 to ""
            }
        }
        val start = client.startMCPSignIn("s1", redirectURI = AppModel.oauthRedirectURI)
        assertTrue(start.authorizationUrl?.startsWith("https://auth.example.com/") == true)
        val sent = String(stub.requests.last().body ?: ByteArray(0), StandardCharsets.UTF_8)
        assertTrue(sent.contains("dev.crumble.android:/oauth/callback"))
        val server = client.finishMCPSignIn("s1", code = "c", state = "x", iss = "https://auth.example.com")
        assertEquals("oauth", server.auth)
        val finished = String(stub.requests.last().body ?: ByteArray(0), StandardCharsets.UTF_8)
        assertTrue(finished.contains(""""iss":"https://auth.example.com""""))
    }

    @Test
    fun errorsCarryStatusAndBody() = runTest {
        val (client, _) = StubTransport.client { 401 to "bad token" }
        try {
            client.agents()
            fail("expected an error")
        } catch (e: APIError) {
            assertEquals(401, e.statusCode)
            assertEquals("bad token", e.body)
            assertEquals("Authentication failed. Check the server token in Settings.", friendlyError(e))
        }
    }

    @Test
    fun scheduleRunUnwrapsTheEnvelope() = runTest {
        val (client, _) = StubTransport.client { 200 to """{"admitted":true,"result":{"id":"e1","status":"pending"}}""" }
        assertEquals("e1", client.runSchedule("s1").id)
    }

    @Test
    fun taskPickersPreserveTheInstantAndSubmitOnlyTheSelectedMode() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val response = """{"id":"s1","agentId":"a1","name":"Greeting","prompt":"Say hi","enabled":true,"timezone":"Europe/Berlin","runAt":"2026-10-18T10:10:00Z"}"""
        val (client, stub) = StubTransport.client { req ->
            200 to if (req.method == "PATCH") response else "[]"
        }
        val model = AppModel(client = client, scope = testScope)
        model.agents = listOf(Agent(id = "a1", name = "Atlas"))
        val schedule = CrumbleJson.decodeFromString(Schedule.serializer(), response)

        for (repeats in listOf(false, true)) {
            model.schedules = listOf(schedule)
            model.openTaskForm("s1")
            val draft = model.taskDraft!!
            assertFalse(draft.repeats)
            val zdt = ZonedDateTime.ofInstant(draft.runDate, ZoneId.of(draft.timezone))
            assertEquals(12, zdt.hour)
            assertEquals(10, zdt.minute)
            model.taskDraft = draft.copy(repeats = repeats)
            model.saveTask()
            advanceUntilIdle()
            assertFalse(model.loading)
            assertEquals("", model.errorText)
            val req = stub.requests.last { it.method == "PATCH" }
            val payload = CrumbleJson.parseToJsonElement(String(req.body!!, StandardCharsets.UTF_8)) as JsonObject
            if (repeats) {
                assertEquals(JsonPrimitive("0 9 * * *"), payload["cron"])
                assertEquals(JsonNull, payload["runAt"])
            } else {
                assertEquals(JsonPrimitive("2026-10-18T10:10:00Z"), payload["runAt"])
                assertEquals(JsonNull, payload["cron"])
            }
        }
    }

    @Test
    fun chatEventsDeliverSnapshotsThenReportClosing() = runTest {
        val (client, _) = StubTransport.client { req ->
            assertEquals("/v1/agents/a7/events", req.pathWithQuery)
            200 to "event: heartbeat\ndata: {}\n\nevent: snapshot\ndata: {\"agentId\":\"a7\",\ndata: \"status\":\"running\"}\n\n"
        }
        val payloads = mutableListOf<String>()
        try {
            client.chatEvents("a7") { payloads.add(it) }
        } catch (_: SSEClosedError) {
        }
        assertEquals(listOf("{\"agentId\":\"a7\",\n\"status\":\"running\"}"), payloads)
    }

    @Test
    fun messageAppearsBeforeHTTPResponseAndSSEAckReconciles() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, stub) = StubTransport.client { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/chat?limit=5" -> 200 to """{"agentId":"a1","status":"idle","messages":[]}"""
                "/v1/agents/a1/messages" -> 202 to ""
                else -> 404 to ""
            }
        }
        val model = AppModel(client = client, scope = testScope)
        model.selectAgent("a1")
        // Advance just the initial chat load
        testScheduler.runCurrent()
        assertNotNull(model.snapshot)

        model.draft = "instant"
        model.sendMessage()
        val optimistic = model.messages.firstOrNull { it.text == "instant" }
        assertEquals(MessageDeliveryState.sending, optimistic?.deliveryState)
        val requestID = optimistic?.requestId
        assertNotNull(requestID)

        testScheduler.runCurrent()
        assertEquals(1, stub.requests.count { it.pathWithQuery == "/v1/agents/a1/messages" })

        model.applySnapshot(
            ChatSnapshot(
                messages = listOf(
                    Message(
                        id = "a1:1:0",
                        role = "user",
                        content = JsonPrimitive("instant"),
                        requestId = requestID,
                    ),
                ),
                status = "idle",
                agentId = "a1",
            ),
        )
        assertEquals(1, model.messages.count { it.text == "instant" })
        assertEquals(MessageDeliveryState.confirmed, model.messages.first().deliveryState)
        model.cancelStream()
    }

    @Test
    fun recentChatIsVisibleBeforeNetworkAndScopedToConnectionAndBot() = runTest {
        val credentials = dev.crumble.android.storage.InMemoryCredentialStore()
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, stub) = StubTransport.client(token = "owner-one") { 503 to "" }
        val model = AppModel(client = client, credentials = credentials, scope = testScope)
        model.selectAgent("a1")
        val rows = (1..12).map {
            Message(id = "a1:$it:0", entryId = "a1:$it", role = "user", content = JsonPrimitive("message $it"))
        }
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = rows, hasMore = false, oldestEntryId = "a1:1"))
        model.cancelStream()

        // Recreating the model exercises the encrypted-store boundary, not only an in-memory map.
        val reopened = AppModel(client = client, credentials = credentials, scope = testScope)
        reopened.selectAgent("a1")
        assertEquals((8..12).map { "message $it" }, reopened.messages.map { it.text })
        assertEquals("a1:8", reopened.oldestEntryID)
        assertTrue(reopened.hasMore)
        assertTrue(stub.requests.isEmpty())
        reopened.cancelStream()
        reopened.selectAgent("a2")
        assertTrue(reopened.messages.isEmpty())
        reopened.cancelStream()

        val (otherClient, _) = StubTransport.client(token = "owner-two") { 503 to "" }
        val other = AppModel(client = otherClient, credentials = credentials, scope = testScope)
        other.selectAgent("a1")
        assertTrue(other.messages.isEmpty())
        other.cancelStream()
    }

    @Test
    fun cachedChatRevalidatesFromStreamWithoutDuplicateGetOrHistoryGap() = runTest {
        val credentials = dev.crumble.android.storage.InMemoryCredentialStore()
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        fun rows(range: IntRange) = range.map {
            Message(id = "a1:$it:0", entryId = "a1:$it", role = "user", content = JsonPrimitive("message $it"))
        }
        val fresh = ChatSnapshot(agentId = "a1", messages = rows(20..24), hasMore = true, oldestEntryId = "a1:20")
        val (client, stub) = StubTransport.client { req ->
            if (req.pathWithQuery == "/v1/agents/a1/events?limit=5") {
                200 to "event: snapshot\ndata: ${CrumbleJson.encodeToString(ChatSnapshot.serializer(), fresh)}\n\n"
            } else 503 to ""
        }
        val seed = AppModel(client = client, credentials = credentials, scope = testScope)
        seed.selectAgent("a1")
        seed.applySnapshot(ChatSnapshot(agentId = "a1", messages = rows(1..5), hasMore = false, oldestEntryId = "a1:1"))
        seed.cancelStream()
        // Settle the seed's queued GET while retaining its persisted recent chat.
        testScheduler.runCurrent()
        stub.requests.clear()

        val reopened = AppModel(client = client, credentials = credentials, scope = testScope)
        reopened.selectAgent("a1")
        assertEquals("message 5", reopened.messages.last().text)
        testScheduler.runCurrent()
        assertTrue(stub.requests.any { it.pathWithQuery == "/v1/agents/a1/events?limit=5" })
        // Deliver the stream's first snapshot at the same reconciliation seam used by chatEvents.
        reopened.applySnapshot(fresh)
        assertEquals((20..24).map { "message $it" }, reopened.messages.map { it.text })
        assertEquals("a1:20", reopened.oldestEntryID)
        assertTrue(reopened.hasMore)
        assertFalse(stub.requests.any { it.pathWithQuery.contains("/chat") })
        reopened.cancelStream()
    }

    @Test
    fun failedSendCanRetryWithTheSameRequestID() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        var attempts = 0
        val (client, stub) = StubTransport.client { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/chat?limit=5" -> 200 to """{"agentId":"a1","status":"idle","messages":[]}"""
                "/v1/agents/a1/messages" -> {
                    attempts += 1
                    if (attempts == 1) 500 to """{"error":"busy"}""" else 202 to ""
                }
                else -> 404 to ""
            }
        }
        val model = AppModel(client = client, scope = testScope)
        model.selectAgent("a1")
        testScheduler.runCurrent()

        model.draft = "retry me"
        model.sendMessage()
        testScheduler.runCurrent()
        assertEquals(MessageDeliveryState.failed, model.messages.first().deliveryState)
        val failed = model.messages.first()
        model.retryMessage(failed)
        assertEquals(MessageDeliveryState.sending, model.messages.first().deliveryState)
        testScheduler.runCurrent()

        val requestIds = stub.requests
            .filter { it.pathWithQuery == "/v1/agents/a1/messages" }
            .mapNotNull { it.body }
            .map { CrumbleJson.parseToJsonElement(String(it, StandardCharsets.UTF_8)) as JsonObject }
            .mapNotNull { (it["requestId"] as? JsonPrimitive)?.content }
        assertEquals(2, requestIds.size)
        assertEquals(failed.requestId, requestIds[0])
        assertEquals(failed.requestId, requestIds[1])
        model.cancelStream()
    }

    @Test
    fun identicalMessagesGetDistinctOptimisticIDs() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, stub) = StubTransport.client { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/chat?limit=5" -> 200 to """{"agentId":"a1","status":"idle","messages":[]}"""
                "/v1/agents/a1/messages" -> 202 to ""
                else -> 404 to ""
            }
        }
        val model = AppModel(client = client, scope = testScope)
        model.selectAgent("a1")
        testScheduler.runCurrent()

        model.draft = "same"
        model.sendMessage()
        model.draft = "same"
        model.sendMessage()
        val rows = model.messages.filter { it.text == "same" }
        assertEquals(2, rows.size)
        assertEquals(2, rows.mapNotNull { it.requestId }.toSet().size)
        testScheduler.runCurrent()
        assertEquals(2, stub.requests.count { it.pathWithQuery == "/v1/agents/a1/messages" })
        model.cancelStream()
    }

    @Test
    fun pendingMessageSurvivesAgentSwitch() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/chat?limit=5", "/v1/agents/a2/chat?limit=5" -> {
                    val id = if (req.pathWithQuery.contains("a2")) "a2" else "a1"
                    200 to """{"agentId":"$id","status":"idle","messages":[]}"""
                }
                "/v1/agents/a1/messages" -> 202 to ""
                else -> 404 to ""
            }
        }
        val model = AppModel(client = client, scope = testScope)
        model.selectAgent("a1")
        testScheduler.runCurrent()

        model.draft = "keep me"
        model.sendMessage()
        assertTrue(model.messages.any { it.text == "keep me" })
        model.selectAgent("a2")
        testScheduler.runCurrent()
        assertFalse(model.messages.any { it.text == "keep me" })
        model.selectAgent("a1")
        testScheduler.runCurrent()
        assertTrue(model.messages.any { it.text == "keep me" })
        model.cancelStream()
    }

    @Test
    fun memoryCreateAppearsBeforeServerResponseAndReconciles() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { req ->
            if (req.method == "POST" && req.pathWithQuery == "/v1/memories") {
                200 to """{"id":"m1","content":"Remember this"}"""
            } else {
                404 to ""
            }
        }
        val model = AppModel(client = client, scope = testScope)
        model.route = Route.Memory
        model.openMemoryForm("")
        model.memoryDraft = model.memoryDraft?.copy(content = "Remember this")
        model.saveMemory()

        assertNull(model.memoryDraft)
        assertEquals(Route.Memory, model.route)
        assertEquals(1, model.sharedMemories.size)
        assertTrue(model.sharedMemories[0].id.startsWith("pending-memory-"))
        advanceUntilIdle()
        assertEquals(listOf("m1"), model.sharedMemories.map { it.id })
        assertEquals(Route.Memory, model.route)
        assertEquals("", model.errorText)
    }

    @Test
    fun failedMemoryDeleteRestoresOnlyDeletedRecord() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { req ->
            if (req.method == "DELETE") 500 to """{"error":"busy"}""" else 404 to ""
        }
        val model = AppModel(client = client, scope = testScope)
        model.route = Route.Memory
        model.sharedMemories = listOf(Memory(id = "m1", content = "First"), Memory(id = "m2", content = "Second"))

        model.deleteMemory("m1")
        assertEquals(listOf("m2"), model.sharedMemories.map { it.id })
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2"), model.sharedMemories.map { it.id })
        assertEquals(listOf("First", "Second"), model.sharedMemories.map { it.content })
        assertTrue(model.errorText.contains("busy"))
    }

    @Test
    fun failedMemorySaveDoesNotReplaceNewerDraft() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { req ->
            if (req.method == "POST") 500 to """{"error":"busy"}""" else 404 to ""
        }
        val model = AppModel(client = client, scope = testScope)
        model.route = Route.Memory
        model.openMemoryForm("")
        model.memoryDraft = model.memoryDraft?.copy(content = "Old draft")
        model.saveMemory()

        model.openMemoryForm("")
        model.memoryDraft = model.memoryDraft?.copy(content = "Newer draft")
        advanceUntilIdle()

        assertEquals("Newer draft", model.memoryDraft?.content)
        assertTrue(model.sharedMemories.isEmpty())
        assertTrue(model.errorText.contains("busy"))
    }

    @Test
    fun profileEditAppearsImmediatelyAndFailurePreservesNewerDraft() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { req ->
            if (req.method == "PATCH") 500 to """{"error":"busy"}""" else 404 to ""
        }
        val model = AppModel(client = client, scope = testScope)
        model.agents = listOf(Agent(id = "a1", name = "Before", lastMessage = "Latest reply"))
        model.route = Route.Memory
        model.agentDraft = AgentDraft(isNew = false, agentID = "a1", name = "After")
        model.saveAgentEditor()
        assertEquals("After", model.agents.first().name)
        assertEquals("Latest reply", model.agents.first().lastMessage)
        assertNull(model.agentDraft)
        model.agentDraft = AgentDraft(isNew = false, agentID = "a1", name = "Newer draft")
        advanceUntilIdle()
        assertFalse(model.loading)
        assertEquals("Before", model.agents.first().name)
        assertEquals("Newer draft", model.agentDraft?.name)
    }

    @Test
    fun agentCreationUsesProvisionalRowWithoutStealingNavigation() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { req ->
            if (req.method == "POST") 201 to """{"id":"created","name":"New bot","enabled":true}""" else 404 to ""
        }
        val model = AppModel(client = client, scope = testScope)
        model.route = Route.Memory
        model.agentDraft = AgentDraft(isNew = true, agentID = "", name = "New bot")
        model.saveAgentEditor()
        assertTrue(model.agents.first().id.startsWith("pending:"))
        assertNull(model.agentDraft)
        model.route = Route.Skills
        advanceUntilIdle()
        assertFalse(model.loading)
        assertEquals(listOf("created"), model.agents.map { it.id })
        assertEquals(Route.Skills, model.route)
    }

    @Test
    fun failedAgentDeleteRestoresOnlyRemovedRow() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (client, _) = StubTransport.client { 500 to """{"error":"busy"}""" }
        val model = AppModel(client = client, scope = testScope)
        model.agents = listOf(Agent(id = "a1", name = "First"), Agent(id = "a2", name = "Second"))
        model.route = Route.Memory
        model.agentDraft = AgentDraft(isNew = false, agentID = "a1", name = "First")
        model.deleteSelected()
        assertEquals(listOf("a2"), model.agents.map { it.id })
        model.agents = listOf(model.agents[0].copy(name = "Second updated"))
        advanceUntilIdle()
        assertFalse(model.loading)
        assertEquals(listOf("a1", "a2"), model.agents.map { it.id })
        assertEquals("Second updated", model.agents[1].name)
        assertEquals(Route.Memory, model.route)
    }

    @Test
    fun sidebarPreviewsUpdateWithoutOpeningTheBotChat() {
        val model = AppModel(client = APIClient(baseURL = "http://127.0.0.1:1", token = ""))
        model.agents = listOf(Agent(id = "a1", name = "Atlas", lastMessage = "Earlier"), Agent(id = "a2", name = "Beacon"))
        model.route = Route.Memory
        val event = CrumbleJson.decodeFromString(
            ActivityEvent.serializer(),
            """{"type":"chat-preview","agentId":"a2","agentName":"Beacon","lastMessage":"A new reply from Beacon"}""",
        )
        model.handleActivity(event)
        assertEquals("Earlier", model.agents[0].lastMessage)
        assertEquals("A new reply from Beacon", model.agents[1].lastMessage)
        assertEquals(Route.Memory, model.route)
        assertTrue(model.messages.isEmpty())
        assertNull(notification(event, NotificationContext(appActive = false, route = null)))
        model.handleActivity(ActivityEvent(type = "chat-preview", agentId = "a2", agentName = "Beacon", lastMessage = ""))
        assertEquals("", model.agents[1].lastMessage)
    }

    @Test
    fun sseRejectsSnapshotsWithoutAgent() {
        val parser = SSEParser()
        parser.feed("event: snapshot")
        parser.feed("data: {\"status\":\"idle\"}")
        try {
            parser.feed("")
            fail("expected InvalidPayload")
        } catch (_: SSEParser.InvalidPayload) {
        }
    }

    @Test
    fun mergeKeepsLoadedHistoryInEntryOrder() {
        val older = listOf(Message(id = "a1:2:0", role = "user"), Message(id = "a1:10:0", role = "assistant"))
        val snapshot = listOf(
            Message(id = "a1:10:0", role = "assistant", content = JsonPrimitive("updated")),
            Message(id = "a1:11:1", role = "user"),
            Message(id = "a1:11:0", role = "user"),
        )
        val merged = mergeMessages(older, snapshot)
        assertEquals(listOf("a1:2:0", "a1:10:0", "a1:11:0", "a1:11:1"), merged.map { it.id })
        assertEquals("updated", merged[1].text)
    }

    @Test
    fun tasksNeedExactlyOneScheduleMode() {
        assertNotNull(validateTask(name = "", prompt = "", cron = "", runAt = ""))
        assertNotNull(validateTask(name = "Daily", prompt = "Check inbox", cron = "0 9 * * *", runAt = "2026-10-18T09:00:00Z"))
        assertNull(validateTask(name = "Daily", prompt = "Check inbox", cron = "0 9 * * *", runAt = ""))
        assertNull(validateTask(name = "Once", prompt = "Check inbox", cron = "", runAt = "2026-10-18T09:00:00Z"))
        assertNotNull(validateTask(name = "Once", prompt = "Check inbox", cron = "", runAt = "tomorrow"))
    }

    @Test
    fun taskRunAtPreservesExplicitOffsetInstant() {
        assertEquals(taskRunDate("2026-10-18T12:10:00+02:00"), taskRunDate("2026-10-18T10:10:00Z"))
        val fractional = taskRunDate("2026-10-18T12:10:00.123Z")!!
        val whole = taskRunDate("2026-10-18T12:10:00Z")!!
        assertTrue(abs((fractional.toEpochMilli() - whole.toEpochMilli()) - 123L) <= 1L)
        assertNotNull(validateTask(name = "Task", prompt = "Hi", cron = "0 9 * * *", runAt = "", timezone = "Not/AZone"))
    }

    @Test
    fun requestIDsAreVersion4UUIDs() {
        val regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(regex.matches(newRequestID()))
    }

    @Test
    fun avatarSeedsAcceptLegacyValues() {
        val cases = mapOf(
            "bot:violet" to "violet",
            "critters:violet" to "violet",
            "sparkles" to "a1",
            "😀" to "a1",
            "" to "a1",
        )
        for ((avatar, seed) in cases) {
            assertEquals(seed, agentAvatarSeed(Agent(id = "a1", avatar = avatar)))
        }
        assertEquals(BotLook("agent-seed"), BotLook("agent-seed"))
    }

    @Test
    fun chatHidesToolActivity() {
        val tool = Message(
            id = "a1:1:0",
            role = "assistant",
            blocks = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("toolCall"))))),
        )
        val reply = Message(
            id = "a1:1:1",
            role = "assistant",
            content = JsonPrimitive("Hi"),
            blocks = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("Hi"))))),
        )
        assertFalse(ChatRow.shownInChat(tool))
        assertTrue(ChatRow.shownInChat(reply))
    }

    @Test
    fun shiftReturnInsertsANewlineAtTheCaret() {
        val text = "hello world"
        val res = insertingNewline(into = text, replacing = 5..4) // empty selection at index 5
        assertEquals("hello\n world", res.text)
        assertEquals(" world", res.text.substring(res.caret))
        assertEquals("hello \n", insertingNewline(into = text, replacing = 6..10).text)
        assertEquals("hello world\n", insertingNewline(into = text, replacing = null).text)
    }

    @Test
    fun filesBecomeAttachmentsTheServerAccepts() {
        val text = loadAttachment(
            name = "notes.md",
            mimeTypeHint = "text/markdown",
            data = "# Groceries".toByteArray(StandardCharsets.UTF_8),
        )
        assertTrue(text.mimeType.startsWith("text/") && text.data.contentEquals("# Groceries".toByteArray()))

        val converted = loadAttachment(
            name = "photo.tiff",
            mimeTypeHint = "image/tiff",
            data = ByteArray(16),
            jpegReencoder = { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) },
        )
        assertEquals("image/jpeg", converted.mimeType)
        assertEquals("photo.jpg", converted.name)

        try {
            loadAttachment(
                name = "doc.pdf",
                mimeTypeHint = "application/pdf",
                data = "%PDF-1.4".toByteArray(),
            )
            fail("expected AttachmentError")
        } catch (_: AttachmentError) {
        }
    }

    @Test
    fun notificationsSkipWhatIsOnScreen() {
        val reply = ActivityEvent(
            type = "reply",
            agentId = "a1",
            agentName = "Atlas",
            origin = "owner",
            preview = "**Done**, two meetings.",
        )
        assertEquals(
            "Done, two meetings.",
            notification(reply, NotificationContext(appActive = false, route = Route.AgentChat("a1")))?.body,
        )
        assertNull(notification(reply, NotificationContext(appActive = true, route = Route.AgentChat("a1"))))
        assertEquals(
            "Atlas",
            notification(reply, NotificationContext(appActive = true, route = Route.AgentChat("a2")))?.title,
        )
        val delegated = reply.copy(origin = "bot")
        assertNull(notification(delegated, NotificationContext(appActive = false, route = null)))
    }

    @Test
    fun activityStreamPassesOnlyActivity() {
        val parser = SSEParser(accepting = setOf("activity"))
        parser.feed("event: heartbeat")
        parser.feed("data: {}")
        assertNull(parser.feed(""))
        parser.feed("event: activity")
        parser.feed("""data: {"type":"reply","agentId":"a1","agentName":"Atlas"}""")
        assertNotNull(parser.feed(""))
    }

    @Test
    fun botCommunicationEventRetainsAttributionAndAppearsInChat() {
        val data = """{"id":"exchange-1","role":"event","kind":"bot-message","content":"I asked how Bot2 feels.","communication":{"executionId":"run-1","fromAgentId":"a1","fromName":"Atlas","toAgentId":"a2","toName":"Beacon","status":"done"}}"""
        val message = CrumbleJson.decodeFromString(Message.serializer(), data)
        val rows = ChatRow.rows(messages = listOf(message), snapshot = null, hasMore = false)
        assertEquals(1, rows.size)
        val retained = (rows[0] as ChatRow.MessageRow).message
        assertEquals("bot-message", retained.kind)
        assertEquals("Atlas", retained.communication?.fromName)
        assertEquals("Beacon", retained.communication?.toName)
        assertEquals("done", retained.communication?.status)
    }

    @Test
    fun signInCallbacksCarryACodeOrAReason() {
        val ok = oauthResult("dev.crumble.android:/oauth/callback?code=abc&state=xyz")
        assertEquals("abc", ok.code)
        assertEquals("xyz", ok.state)
        assertNull(ok.iss)

        val issued = oauthResult("dev.crumble.android:/oauth/callback?code=abc&state=xyz&iss=https%3A%2F%2Fexecutor.sh%2Fapi%2Fauth")
        assertEquals("https://executor.sh/api/auth", issued.iss)

        try {
            oauthResult("dev.crumble.android:/oauth/callback?error=access_denied&error_description=User%20declined")
            fail("expected SignInError")
        } catch (_: SignInError) {
        }

        try {
            oauthResult("dev.crumble.android:/oauth/callback")
            fail("expected SignInError")
        } catch (_: SignInError) {
        }
    }

    @Test
    fun markdownSplitsBlocks() {
        val blocks = MarkdownBlock.parse("Two meetings:\n\n- 09:00 Design\n- 14:30 Call\n\n```\ncode\n```")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("Two meetings:"),
                MarkdownBlock.ListBlock(
                    listOf(
                        MarkdownBlock.Item(marker = "•", text = "09:00 Design", indent = 0),
                        MarkdownBlock.Item(marker = "•", text = "14:30 Call", indent = 0),
                    ),
                ),
                MarkdownBlock.Code("code"),
            ),
            blocks,
        )
    }

    @Test
    fun mentionQueryFindsNamesWithoutTreatingEmailAddressesAsMentions() {
        val multiword = "Ask @Research Buddy"
        val multiwordMatch = mentionQuery(multiword, selectionStart = multiword.length)
        assertEquals("Research Buddy", multiwordMatch?.query)
        assertEquals(4, multiwordMatch?.start)
        assertEquals(15, multiwordMatch?.length)

        val unicode = "Ask @Zoë"
        val unicodeMatch = mentionQuery(unicode, selectionStart = unicode.length)
        assertEquals("Zoë", unicodeMatch?.query)
        assertEquals(4, unicodeMatch?.start)
        assertEquals(4, unicodeMatch?.length)

        val email = "mail me@example.com"
        assertNull(mentionQuery(email, selectionStart = email.length))
        assertEquals("Re", mentionQuery(multiword, selectionStart = 7)?.query)
        assertNull(mentionQuery(multiword, selectionStart = 3, selectionEnd = 4))

        val atlas = Agent(id = "atlas", name = "Atlas", enabled = true)
        val buddy = Agent(id = "buddy", name = "Buddy", enabled = true)
        val completion = MentionCompletion.find("Ask @", selectionStart = 5, bots = listOf(buddy, atlas))
        assertEquals(listOf("atlas", "buddy"), completion?.bots?.map { it.id })
    }
}
