package dev.crumble.android

import dev.crumble.android.generated.GroupChat
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.PendingAttachment
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.storage.CredentialStore
import dev.crumble.android.storage.InMemoryCredentialStore
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER = "https://crumble.example"
private const val GROUP = "11111111-1111-4111-8111-111111111111"
private const val GROUP_PATH = "/v1/group-chats/$GROUP"
private const val SEND = "POST /v1/agents/a1/messages"
private const val SAVE_DELAY = 1_000L

private val GROUP_JSON = """{"id":"$GROUP","name":"Team","memberIds":["a1","a2"],"createdAt":"t","updatedAt":"t"}"""
private val GROUP_SNAPSHOT = """{"messages":[],"hasMore":false,"oldestEntryId":null,"groupChat":$GROUP_JSON,"activity":{"paused":false,"botTurns":0,"members":[]},"updatedAt":1}"""

private class DraftTransport : HttpTransport {
    val responses = mutableMapOf(
        "GET /v1/agents" to (200 to "[${agentFixture("a1", "Ada")},${agentFixture("a2", "Kev")}]"),
        "GET /v1/agents/a1/chat?limit=5" to (200 to snapshotFixture(ChatSnapshot(agentId = "a1", status = "idle"))),
        "GET /v1/agents/a2/chat?limit=5" to (200 to snapshotFixture(ChatSnapshot(agentId = "a2", status = "idle"))),
        "GET $GROUP_PATH/chat" to (200 to GROUP_SNAPSHOT),
        "POST $GROUP_PATH/messages" to (201 to """{"requestId":"r","entryId":"e"}"""),
        "DELETE $GROUP_PATH" to (204 to ""),
        "POST /api/auth/sign-out" to (200 to "{}"),
        SEND to (202 to executionFixture),
    )

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        val (status, body) = responses["${request.method} ${request.pathWithQuery}"] ?: (404 to "")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    // A 404 would read as the group having been deleted, which deletes its draft.
    override suspend fun openStream(request: HttpRequestData) =
        HttpStreamResponse(200, ByteArrayInputStream(ByteArray(0)))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerDraftTests {
    private val scopes = mutableListOf<CoroutineScope>()
    private val transport = DraftTransport()
    private val store = InMemoryCredentialStore()

    private fun scenario(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
        }
    }

    /** Each call is a fresh process: nothing carries over but the store. */
    private fun TestScope.launchApp(token: String = "session", server: String = SERVER) = AppModel(
        client = APIClient(server, token, transport),
        credentials = store,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes.add(it) },
        transport = transport,
    ).also {
        it.agents = listOf(Agent(id = "a1", name = "Ada"), Agent(id = "a2", name = "Kev"))
        it.groupChats = listOf(GroupChat(GROUP, "Team", listOf("a1", "a2"), "t", "t"))
    }

    private fun TestScope.settle() {
        advanceTimeBy(SAVE_DELAY)
        runCurrent()
    }

    private fun TestScope.open(model: AppModel, id: String = "a1") {
        model.selectAgent(id)
        runCurrent()
    }

    private fun TestScope.openGroup(model: AppModel) {
        model.openGroupChat(GROUP)
        runCurrent()
    }

    private fun TestScope.assertNoDrafts() {
        val app = launchApp()
        open(app)
        assertEquals("", app.draft)
        open(app, "a2")
        assertEquals("", app.draft)
        openGroup(app)
        assertEquals("", app.groupText())
    }

    private fun AppModel.groupText() = groupComposer(GROUP).draft

    private fun AppModel.typeInGroup(text: String) = updateGroupComposer(GROUP) { it.copy(draft = text) }

    @Test
    fun aBotDraftSurvivesARestart() = scenario {
        val first = launchApp()
        open(first)
        first.draft = "half written"
        settle()

        val second = launchApp()
        open(second)

        assertEquals("half written", second.draft)
    }

    @Test
    fun aDraftStaysWithItsBot() = scenario {
        val first = launchApp()
        open(first)
        first.draft = "for Ada"
        open(first, "a2")
        first.draft = "for Kev"
        settle()

        val second = launchApp()
        open(second, "a2")
        assertEquals("for Kev", second.draft)
        open(second, "a1")
        assertEquals("for Ada", second.draft)
    }

    @Test
    fun anotherAccountOrServerSeesNoDraft() = scenario {
        val first = launchApp()
        open(first)
        first.draft = "private"
        settle()

        val otherAccount = launchApp(token = "other")
        open(otherAccount)
        assertEquals("", otherAccount.draft)

        val otherServer = launchApp(server = "https://other.example")
        open(otherServer)
        assertEquals("", otherServer.draft)
    }

    @Test
    fun attachmentsAndReplyTargetsAreNeverSaved() = scenario {
        val first = launchApp()
        open(first)
        first.draft = "with a file"
        first.attachments = listOf(PendingAttachment(name = "note.txt", mimeType = "text/plain", data = "hi".toByteArray()))
        settle()

        val second = launchApp()
        open(second)

        assertEquals("with a file", second.draft)
        assertTrue(second.attachments.isEmpty())
    }

    @Test
    fun aSentDraftDoesNotComeBack() = scenario {
        val first = launchApp()
        open(first)
        first.draft = "sent"
        settle()
        first.applySnapshot(ChatSnapshot(agentId = "a1", status = "idle"))
        first.sendMessage()
        runCurrent()
        settle()

        val second = launchApp()
        open(second)

        assertEquals("", second.draft)
    }

    @Test
    fun aFailedSendIsStillADraftAfterARestart() = scenario {
        transport.responses[SEND] = 503 to """{"error":"busy"}"""
        val first = launchApp()
        open(first)
        first.applySnapshot(ChatSnapshot(agentId = "a1", status = "idle"))
        first.draft = "keep me"
        first.sendMessage()
        runCurrent()
        assertEquals("keep me", first.draft)
        settle()

        val second = launchApp()
        open(second)

        assertEquals("keep me", second.draft)
    }

    @Test
    fun aFailedSendForAnotherBotIsSavedToo() = scenario {
        transport.responses[SEND] = 503 to """{"error":"busy"}"""
        val first = launchApp()
        open(first)
        first.applySnapshot(ChatSnapshot(agentId = "a1", status = "idle"))
        first.draft = "keep me"
        first.sendMessage()
        open(first, "a2")
        runCurrent()
        settle()

        val second = launchApp()
        open(second)

        assertEquals("keep me", second.draft)
    }

    @Test
    fun aGroupDraftSurvivesARestart() = scenario {
        val first = launchApp()
        openGroup(first)
        first.typeInGroup("to the team")
        settle()

        val second = launchApp()
        openGroup(second)

        assertEquals("to the team", second.groupText())
    }

    @Test
    fun aSentGroupDraftDoesNotComeBack() = scenario {
        val first = launchApp()
        openGroup(first)
        first.typeInGroup("to the team")
        settle()
        first.sendGroupMessage()
        runCurrent()
        settle()

        val second = launchApp()
        openGroup(second)

        assertEquals("", second.groupText())
    }

    @Test
    fun deletingAGroupDeletesItsDraft() = scenario {
        val first = launchApp()
        openGroup(first)
        first.typeInGroup("to the team")
        settle()
        first.deleteGroup(GROUP)
        runCurrent()
        settle()

        val second = launchApp()
        openGroup(second)

        assertEquals("", second.groupText())
    }

    @Test
    fun signingOutDeletesDraftsEvenOnesNotYetSaved() = scenario {
        val first = launchApp()
        openGroup(first)
        first.typeInGroup("to the team")
        open(first)
        first.draft = "saved"
        settle()
        open(first, "a2")
        first.draft = "still in memory"
        first.signOut()
        runCurrent()
        settle()

        assertNoDrafts()
    }

    @Test
    fun changingServerDeletesDrafts() = scenario {
        val first = launchApp()
        openGroup(first)
        first.typeInGroup("to the team")
        open(first, "a2")
        first.draft = "not yet stored"
        settle()
        open(first)
        first.draft = "for the old server"
        first.serverURL = "https://other.example"
        first.password = "secret"
        first.email = "a@b.dev"
        transport.responses["POST /api/auth/sign-in/email"] = 200 to """{"token":"new"}"""
        first.saveSettings()
        runCurrent()
        settle()
        assertEquals("https://other.example", first.client?.baseURL)

        assertNoDrafts()
    }

    @Test
    fun leavingTheScreenSavesAtOnce() = scenario {
        val first = launchApp()
        open(first)
        first.draft = "typed just before leaving"
        first.saveDrafts()

        val second = launchApp()
        open(second)

        assertEquals("typed just before leaving", second.draft)
    }

    @Test
    fun aDraftNotYetStoredIsStillThereWhenTheBotReopens() = scenario {
        val model = launchApp()
        open(model)
        model.draft = "typed"
        model.draft = ""
        open(model, "a2")
        model.draft = "typed for Kev"
        open(model, "a1")

        assertEquals("", model.draft)
        open(model, "a2")
        assertEquals("typed for Kev", model.draft)
    }

    @Test
    fun anAttachmentParkedWithADraftComesBackWithIt() = scenario {
        val model = launchApp()
        open(model)
        model.draft = "with a file"
        model.attachments = listOf(PendingAttachment(name = "note.txt", mimeType = "text/plain", data = "hi".toByteArray()))
        settle()
        open(model, "a2")
        open(model, "a1")

        assertEquals("with a file", model.draft)
        assertEquals(1, model.attachments.size)
    }

    @Test
    fun deletingABotDeletesItsDraft() = scenario {
        transport.responses["DELETE /v1/agents/a1"] = 204 to ""
        val first = launchApp()
        open(first)
        first.draft = "for a gone bot"
        settle()
        first.deleteSelected()
        runCurrent()
        settle()

        val second = launchApp()
        open(second)

        assertEquals("", second.draft)
    }

    @Test
    fun theStoreNeverHoldsAFailedWrite() = scenario {
        val failing = object : CredentialStore by store {
            override fun write(account: String, value: String) = throw IllegalStateException("full")
        }
        val model = AppModel(
            client = APIClient(SERVER, "session", transport),
            credentials = failing,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes.add(it) },
            transport = transport,
        )
        model.selectAgent("a1")
        runCurrent()
        model.draft = "still typed"
        settle()

        assertEquals("still typed", model.draft)
    }
}
