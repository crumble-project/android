package dev.crumble.android

import dev.crumble.android.model.Agent
import dev.crumble.android.model.AttachmentInfo
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.Memory
import dev.crumble.android.model.MemoryDraft
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageDeliveryState
import dev.crumble.android.model.PendingAttachment
import dev.crumble.android.model.Route
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.mentionableBots
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.storage.InMemoryCredentialStore
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER = "https://crumble.example"
private const val SEND = "POST /v1/agents/a1/messages"
private const val BUSY = """{"error":"busy"}"""

/** Answers from `responses`; a held request waits until the test releases it. */
private class Scripted : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val responses = mutableMapOf<String, Pair<Int, String>>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    init {
        responses["GET /v1/agents/a1/chat?limit=5"] = 200 to snapshotFixture(ChatSnapshot(agentId = "a1", status = "idle"))
        responses["GET /v1/agents/a2/chat?limit=5"] = 200 to snapshotFixture(ChatSnapshot(agentId = "a2", status = "idle"))
        responses["GET /v1/agents"] = 200 to "[${agentFixture("a1", "Ada")},${agentFixture("a2", "Kev")}]"
        responses["GET /v1/memories"] = 200 to "[]"
        responses["GET /v1/agents/a1/memories"] = 200 to "[]"
        responses["GET /v1/agents/a2/memories"] = 200 to "[]"
        responses[SEND] = 202 to executionFixture
        responses["POST /v1/agents/a1/files"] = 201 to uploadedFileFixture()
    }

    fun hold(key: String) = CompletableDeferred<Unit>().also { held[key] = it }
    fun sent(key: String) = requests.filter { "${it.method} ${it.pathWithQuery}" == key }

    fun requestIDs() = sent(SEND).map { request ->
        val body = CrumbleJson.parseToJsonElement(String(request.body!!, StandardCharsets.UTF_8)) as JsonObject
        (body["requestId"] as JsonPrimitive).content
    }

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        val key = "${request.method} ${request.pathWithQuery}"
        requests.add(request)
        held[key]?.await()
        val (status, body) = responses[key] ?: (404 to "")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData) =
        HttpStreamResponse(404, ByteArrayInputStream(ByteArray(0)))
}

private fun scheduleJson(id: String, name: String) =
    """{"id":"$id","agentId":"a1","name":"$name","kind":"prompt","prompt":"Hi","command":null,"timeoutSeconds":600,"notify":false,"eventSourceId":null,"enabled":true,"timezone":"UTC","runAt":null,"cron":"0 9 * * *","nextRunAt":null,"nextRunLabel":"Soon","description":"Once","timing":"Once","createdAt":"2026-11-05T10:00:00Z","createdByAgentId":null}"""

private fun scheduleOf(id: String, name: String = "Task") = CrumbleJson.decodeFromString(Schedule.serializer(), scheduleJson(id, name))

private val ATTACHMENT = PendingAttachment(
    name = "note.txt",
    mimeType = "text/plain",
    data = "hi".toByteArray(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DraftsAndReadsTests {
    private val scopes = mutableListOf<CoroutineScope>()

    /** Chat streams retry forever, so a failed assertion must still stop them or the test never ends. */
    private fun scenario(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
        }
    }

    private fun TestScope.model(transport: Scripted) = AppModel(
        client = APIClient(SERVER, "session", transport),
        credentials = InMemoryCredentialStore(),
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes.add(it) },
        transport = transport,
    )

    private fun TestScope.openChat(model: AppModel, id: String = "a1") {
        model.selectAgent(id)
        runCurrent()
    }

    // Drafts

    @Test
    fun returningFromAPanelKeepsTheComposer() = scenario {
        val model = model(Scripted())
        openChat(model)
        val reply = Message(id = "r", entryId = "a1:1", role = "assistant", content = JsonPrimitive("Hi"))
        model.draft = "half written"
        model.attachments = listOf(ATTACHMENT)
        model.replyTo = reply

        model.navigate(Route.Memory)
        runCurrent()
        model.navigate(Route.AgentChat("a1"))
        runCurrent()

        assertEquals("half written", model.draft)
        assertEquals(listOf(ATTACHMENT), model.attachments)
        assertEquals(reply, model.replyTo)
    }

    @Test
    fun eachBotKeepsItsOwnDraft() = scenario {
        val model = model(Scripted())
        openChat(model)
        model.draft = "for Ada"
        model.attachments = listOf(ATTACHMENT)

        openChat(model, "a2")
        assertEquals("", model.draft)
        assertTrue(model.attachments.isEmpty())
        model.draft = "for Kev"

        openChat(model, "a1")
        assertEquals("for Ada", model.draft)
        assertEquals(listOf(ATTACHMENT), model.attachments)

        openChat(model, "a2")
        assertEquals("for Kev", model.draft)
        assertTrue(model.attachments.isEmpty())
    }

    @Test
    fun anotherAccountStartsWithEmptyDrafts() = scenario {
        val model = model(Scripted())
        openChat(model)
        model.draft = "for Ada"
        openChat(model, "a2")
        model.draft = "for Kev"
        model.token = "session"

        model.signOut()
        runCurrent()
        assertEquals("", model.draft)

        openChat(model)
        assertEquals("", model.draft)
    }

    @Test
    fun aBotDeletedForGoodLeavesNoDraft() = scenario {
        val transport = Scripted().apply { responses["DELETE /v1/agents/a1"] = 204 to "" }
        val model = model(transport)
        model.agents = listOf(Agent(id = "a1", name = "Ada"), Agent(id = "a2", name = "Kev"))
        openChat(model)
        model.draft = "for Ada"

        model.deleteSelected()
        runCurrent()
        assertEquals("", model.draft)

        openChat(model, "a1")
        assertEquals("", model.draft)
    }

    // Retrying a failed send

    @Test
    fun sendingARestoredDraftRetriesWithTheSameRequestID() = scenario {
        val transport = Scripted().apply { responses[SEND] = 500 to BUSY }
        val model = model(transport)
        openChat(model)
        model.draft = "only once"
        model.attachments = listOf(ATTACHMENT)
        model.sendMessage()
        runCurrent()

        assertEquals("only once", model.draft)
        assertEquals(listOf(ATTACHMENT), model.attachments)
        transport.responses[SEND] = 202 to executionFixture
        model.sendMessage()
        runCurrent()

        assertEquals(1, transport.requestIDs().toSet().size)
        assertEquals(2, transport.requestIDs().size)
        assertEquals(1, transport.sent("POST /v1/agents/a1/files").size)
        transport.sent(SEND).forEach { request ->
            val body = CrumbleJson.parseToJsonElement(request.body!!.toString(StandardCharsets.UTF_8)).jsonObject
            assertEquals(listOf("33333333-3333-4333-8333-333333333333"), body["files"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertNull(body["attachments"])
        }
        assertEquals(1, model.messages.count { it.text == "only once" })
        assertEquals(MessageDeliveryState.sending, model.messages.single().deliveryState)
        assertEquals("", model.draft)
        assertTrue(model.attachments.isEmpty())
    }

    @Test
    fun anEditedDraftIsANewMessage() = scenario {
        val transport = Scripted().apply { responses[SEND] = 500 to BUSY }
        val model = model(transport)
        openChat(model)
        model.draft = "first try"
        model.sendMessage()
        runCurrent()

        transport.responses[SEND] = 202 to executionFixture
        model.draft = "second thoughts"
        model.sendMessage()
        runCurrent()

        assertEquals(2, transport.requestIDs().toSet().size)
        assertEquals(MessageDeliveryState.failed, model.messages.first { it.text == "first try" }.deliveryState)
    }

    @Test
    fun aSnapshotConfirmingAFailedSendClearsTheRestoredDraft() = scenario {
        val transport = Scripted().apply { responses[SEND] = 500 to BUSY }
        val model = model(transport)
        openChat(model)
        model.draft = "arrived anyway"
        model.sendMessage()
        runCurrent()
        val requestID = transport.requestIDs().single()

        model.applySnapshot(
            ChatSnapshot(
                agentId = "a1",
                status = "idle",
                messages = listOf(Message(id = "a1:1:0", entryId = "a1:1", role = "user", content = JsonPrimitive("arrived anyway"), requestId = requestID)),
            ),
        )

        assertEquals("", model.draft)
        model.sendMessage()
        runCurrent()
        assertEquals(1, transport.sent(SEND).size)
    }

    @Test
    fun aSendFailingAfterReturningToTheBotStillFailsItsRow() = scenario {
        val transport = Scripted()
        val gate = transport.hold(SEND)
        val model = model(transport)
        openChat(model)
        model.draft = "slow"
        model.sendMessage()
        runCurrent()

        model.navigate(Route.Memory)
        runCurrent()
        model.navigate(Route.AgentChat("a1"))
        runCurrent()
        transport.responses[SEND] = 500 to BUSY
        gate.complete(Unit)
        runCurrent()

        assertEquals(MessageDeliveryState.failed, model.messages.single { it.text == "slow" }.deliveryState)
        assertEquals("slow", model.draft)
    }

    @Test
    fun aSendFailingWhileOnAnotherBotRestoresItsDraftThere() = scenario {
        val transport = Scripted()
        val gate = transport.hold(SEND)
        val model = model(transport)
        openChat(model)
        model.draft = "slow"
        model.sendMessage()
        runCurrent()

        openChat(model, "a2")
        model.draft = "for Kev"
        transport.responses[SEND] = 500 to BUSY
        gate.complete(Unit)
        runCurrent()
        assertEquals("for Kev", model.draft)

        openChat(model, "a1")
        assertEquals("slow", model.draft)
        assertEquals(MessageDeliveryState.failed, model.messages.single { it.text == "slow" }.deliveryState)
    }

    // Panel reads

    @Test
    fun aPageLoadsDuringAnUnrelatedSlowSave() = scenario {
        val transport = Scripted().apply {
            responses["GET /v1/memories"] = 200 to "[${memoryFixture("m1", "Fresh")}]"
            responses["PATCH /v1/agents/a1"] = 200 to agentFixture("a1", "Ada")
            hold("PATCH /v1/agents/a1")
        }
        val model = model(transport)
        model.agents = listOf(Agent(id = "a1", name = "Ada", provider = "openai", model = "gpt", tools = listOf("memory")))
        model.openEditor("a1")
        model.saveAgentEditor()
        runCurrent()
        assertTrue(model.pendingMutations.isNotEmpty())

        model.openPanel(Route.Memory)
        runCurrent()

        assertEquals(listOf("Fresh"), model.sharedMemories.map { it.content })
    }

    @Test
    fun aReadKeepsOnlyTheRecordsBeingChanged() = scenario {
        val transport = Scripted().apply {
            responses["GET /v1/memories"] = 200 to "[${memoryFixture("edited", "Server")},${memoryFixture("deleted", "Server")},${memoryFixture("other", "Fresh")}]"
            responses["PATCH /v1/memories/edited"] = 200 to memoryFixture("edited", "Mine")
            hold("PATCH /v1/memories/edited")
            hold("DELETE /v1/memories/deleted")
        }
        val model = model(transport)
        model.route = Route.Memory
        model.sharedMemories = listOf(
            Memory(id = "edited", content = "Before"),
            Memory(id = "deleted", content = "Before"),
            Memory(id = "other", content = "Stale"),
        )
        model.memoryDraft = MemoryDraft(memoryID = "edited", content = "Mine")
        model.saveMemory()
        model.deleteMemory("deleted")
        runCurrent()

        model.openPanel(Route.Memory)
        runCurrent()

        assertEquals(listOf("edited" to "Mine", "other" to "Fresh"), model.sharedMemories.map { it.id to it.content })
    }

    @Test
    fun aReadOverlappingAConfirmedChangeFetchesAgain() = scenario {
        val transport = Scripted().apply {
            responses["POST /v1/memories"] = 200 to memoryFixture("m1", "Saved")
        }
        val model = model(transport)
        model.route = Route.Memory
        val read = transport.hold("GET /v1/memories")
        model.openPanel(Route.Memory)
        runCurrent()

        model.memoryDraft = MemoryDraft(memoryID = "", content = "Saved")
        model.saveMemory()
        runCurrent()
        assertEquals(listOf("m1"), model.sharedMemories.map { it.id })

        val second = transport.hold("GET /v1/memories")
        read.complete(Unit)
        runCurrent()
        assertEquals(2, transport.sent("GET /v1/memories").size)
        assertEquals(listOf("m1"), model.sharedMemories.map { it.id })

        transport.responses["GET /v1/memories"] = 200 to "[${memoryFixture("m1", "Saved")},${memoryFixture("m2", "Another client")}]"
        second.complete(Unit)
        runCurrent()
        assertEquals(listOf("m1", "m2"), model.sharedMemories.map { it.id })
    }

    @Test
    fun aCreateSeenByAReadBeforeItsSaveAnswersShowsOnce() = scenario {
        val transport = Scripted().apply {
            responses["POST /v1/memories"] = 200 to memoryFixture("m1", "Saved")
            responses["GET /v1/memories"] = 200 to "[${memoryFixture("m1", "Saved")}]"
        }
        val save = transport.hold("POST /v1/memories")
        val model = model(transport)
        model.route = Route.Memory
        model.memoryDraft = MemoryDraft(memoryID = "", content = "Saved")
        model.saveMemory()
        runCurrent()

        model.openPanel(Route.Memory)
        runCurrent()
        save.complete(Unit)
        runCurrent()

        assertEquals(listOf("m1"), model.sharedMemories.map { it.id })
    }

    @Test
    fun tasksLoadDuringAnUnrelatedSaveAndKeepTheOneBeingEdited() = scenario {
        val transport = Scripted().apply {
            responses["GET /v1/schedules"] = 200 to "[${scheduleJson("s1", "Server")},${scheduleJson("s2", "Fresh")}]"
            responses["PATCH /v1/schedules/s1"] = 200 to scheduleJson("s1", "Mine")
            hold("PATCH /v1/schedules/s1")
        }
        val model = model(transport)
        model.route = Route.Tasks
        model.agents = listOf(Agent(id = "a1", name = "Ada"))
        model.schedules = listOf(scheduleOf("s1", "Before"))
        model.openTaskForm("s1")
        model.taskDraft = model.taskDraft!!.copy(name = "Mine")
        model.allowTaskSave()
        model.saveTask()
        runCurrent()

        model.openPanel(Route.Tasks)
        runCurrent()

        assertEquals(listOf("Mine", "Fresh"), model.schedules.map { it.name })
    }

    @Test
    fun refreshingDuringAnUnrelatedSaveStillLoadsBots() = scenario {
        val transport = Scripted().apply {
            responses["PATCH /v1/memories/m1"] = 200 to memoryFixture("m1", "Mine")
            hold("PATCH /v1/memories/m1")
        }
        val model = model(transport)
        model.sharedMemories = listOf(Memory(id = "m1", content = "Before"))
        model.memoryDraft = MemoryDraft(memoryID = "m1", content = "Mine")
        model.saveMemory()
        runCurrent()

        model.refresh()
        runCurrent()

        assertEquals(listOf("a1", "a2"), model.agents.map { it.id })
        assertEquals("a1", model.selectedAgentID)
    }

    // Server scope

    @Test
    fun aLateBotApprovalStaysOnItsServer() = scenario {
        val transport = Scripted().apply {
            responses["POST /v1/agents/a1/bot-requests/b1/approve"] = 200 to """{"request":${botRequestFixture("approved")},"agent":${agentFixture("created", "Old server")}}"""
        }
        val gate = transport.hold("POST /v1/agents/a1/bot-requests/b1/approve")
        val model = model(transport)
        openChat(model)
        model.token = "session"
        model.decideBotRequest("b1", approve = true)
        runCurrent()
        assertEquals(1, transport.sent("POST /v1/agents/a1/bot-requests/b1/approve").size)

        model.signOut()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(model.agents.none { it.id == "created" })
    }

    @Test
    fun attachmentImagesDoNotOutliveTheSession() = scenario {
        val transport = Scripted().apply {
            responses["GET /v1/agents/a1/files/33333333-3333-4333-8333-333333333333"] = 200 to "image"
            responses["GET /v1/agents/a1/chat?limit=5"] = 200 to snapshotFixture(ChatSnapshot(
                agentId = "a1", messages = listOf(Message(
                    id = "a1:1:0", entryId = "a1:1", role = "user",
                    attachments = listOf(AttachmentInfo(id = "33333333-3333-4333-8333-333333333333", name = "image.png", mimeType = "image/png", size = 5)),
                )),
            ))
        }
        val model = model(transport)
        openChat(model)
        model.loadAttachmentImage("a1:1", 0)
        assertEquals("/v1/agents/a1/files/33333333-3333-4333-8333-333333333333", transport.requests.last().pathWithQuery)
        assertEquals(1, model.attachmentBytes.size)
        model.token = "session"

        model.signOut()
        runCurrent()

        assertTrue(model.attachmentBytes.isEmpty())
    }

    // Rollback

    @Test
    fun aFailedBotDeleteRestoresUnsavedProfileEdits() = scenario {
        val transport = Scripted().apply { responses["DELETE /v1/agents/a1"] = 500 to BUSY }
        val model = model(transport)
        model.agents = listOf(Agent(id = "a1", name = "Ada"), Agent(id = "a2", name = "Kev"))
        openChat(model)
        model.openEditor("a1")
        val edits = model.agentDraft!!.copy(name = "Unsaved")
        model.agentDraft = edits

        model.deleteSelected()
        assertNull(model.agentDraft)
        runCurrent()

        assertEquals(edits, model.agentDraft)
        assertEquals("a1", model.selectedAgentID)
    }

    @Test
    fun aFailedTaskSaveKeepsTheTaskInPlace() = scenario {
        val transport = Scripted().apply { responses["PATCH /v1/schedules/s1"] = 500 to BUSY }
        val model = model(transport)
        model.route = Route.Tasks
        model.agents = listOf(Agent(id = "a1", name = "Ada"))
        model.schedules = listOf(scheduleOf("s1"), scheduleOf("s2"))
        model.openTaskForm("s1")
        model.taskDraft = model.taskDraft!!.copy(name = "Renamed")
        model.allowTaskSave()
        model.saveTask()
        runCurrent()

        assertEquals(listOf("s1", "s2"), model.schedules.map { it.id })
        assertEquals("Task", model.schedules.first().name)
    }

    @Test
    fun aFailedBotSaveKeepsChangesConfirmedMeanwhile() = scenario {
        val transport = Scripted().apply {
            responses["PATCH /v1/agents/a1"] = 500 to BUSY
            hold("PATCH /v1/agents/a1")
        }
        val gate = transport.held.getValue("PATCH /v1/agents/a1")
        val model = model(transport)
        model.agents = listOf(Agent(id = "a1", name = "Ada", provider = "openai", model = "gpt", description = "Before", sharedSkills = listOf("s1"), lastMessage = "Old"))
        model.openEditor("a1")
        model.agentDraft = model.agentDraft!!.copy(name = "Renamed")
        model.saveAgentEditor()
        runCurrent()
        assertEquals("Renamed", model.agents.single().name)

        model.agents = model.agents.map { it.copy(sharedSkills = listOf("s1", "s2"), lastMessage = "New", description = "Other client") }
        gate.complete(Unit)
        runCurrent()

        val bot = model.agents.single()
        assertEquals("Ada", bot.name)
        assertEquals(listOf("s1", "s2"), bot.sharedSkills)
        assertEquals("New", bot.lastMessage)
        assertEquals("Other client", bot.description)
    }

    // Saves that cannot start

    @Test
    fun aSaveThatCannotStartKeepsItsForm() = scenario {
        val transport = Scripted().apply {
            responses["PATCH /v1/memories/m1"] = 200 to memoryFixture("m1", "First")
            hold("PATCH /v1/memories/m1")
        }
        val model = model(transport)
        model.route = Route.Memory
        model.sharedMemories = listOf(Memory(id = "m1", content = "Before"))
        model.memoryDraft = MemoryDraft(memoryID = "m1", content = "First")
        model.saveMemory()
        runCurrent()

        val second = MemoryDraft(memoryID = "m1", content = "Second")
        model.memoryDraft = second
        model.saveMemory()

        assertEquals(second, model.memoryDraft)
        assertTrue(model.errorText.isNotEmpty())
    }

    // Bot defaults and mentions

    @Test
    fun aNewBotStartsWithTheWebsitesTools() = scenario {
        val transport = Scripted().apply { responses["POST /v1/agents"] = 201 to agentFixture("created", "New") }
        val model = model(transport)
        model.createAgent()
        model.agentDraft = model.agentDraft!!.copy(name = "New", provider = "openai", model = "gpt")
        model.saveAgentEditor()
        runCurrent()

        val body = CrumbleJson.parseToJsonElement(String(transport.sent("POST /v1/agents").single().body!!, StandardCharsets.UTF_8)).jsonObject
        assertEquals(setOf("memory", "web", "delegate"), body.getValue("tools").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun mentionsNeedOnlyTheDelegateTool() {
        val ada = Agent(id = "a1", name = "Ada", tools = listOf("delegate"), delegates = emptyList())
        val kev = Agent(id = "a2", name = "Kev", enabled = true)
        val asleep = Agent(id = "a3", name = "Zed", enabled = false)

        assertEquals(listOf(kev), mentionableBots(ada, listOf(ada, kev, asleep)))
        assertTrue(mentionableBots(ada.copy(tools = listOf("web")), listOf(ada, kev)).isEmpty())
    }
}
