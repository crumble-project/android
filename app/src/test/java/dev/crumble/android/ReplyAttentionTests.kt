package dev.crumble.android

import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import dev.crumble.android.generated.CredentialRequest
import dev.crumble.android.generated.Credentials
import dev.crumble.android.model.DisplayedReplySurface
import dev.crumble.android.model.SecretRequestRef
import dev.crumble.android.model.Route
import dev.crumble.android.model.attentionEventIDs
import dev.crumble.android.model.credentialReadKey
import dev.crumble.android.model.replyCardReady
import dev.crumble.android.network.domain
import dev.crumble.android.ui.replySurfaceVisible
import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.Message
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyAttentionTests {
    @Test
    fun foregroundChangesReevaluateVisibleReplySurfaces() = runTest {
        val model = AppModel(scope = backgroundScope, loadCredentials = false)
        val observed = mutableListOf<Boolean>()
        backgroundScope.launch { snapshotFlow { model.appActive }.collect { observed.add(it) } }
        runCurrent()
        model.appActive = false
        Snapshot.sendApplyNotifications()
        runCurrent()
        model.appActive = true
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(listOf(true, false, true), observed)
    }

    @Test
    fun historyReferencesReachTheDomainMessage() = runTest {
        val refs = """[{"eventId":"reply-event","surface":{"kind":"text"}}]"""
        val (api, _) = StubTransport.client {
            200 to """{"messages":[{"id":"m1","entryId":"e1","role":"assistant","content":"Answer","replyReferences":$refs}],"hasMore":false,"oldestEntryId":null}"""
        }
        val message = api.chatHistory("a1", "", 5).messages!!.single()
        assertEquals(CrumbleJson.parseToJsonElement(refs), CrumbleJson.encodeToJsonElement(message).jsonObject["replyReferences"])
        val event = CrumbleJson.decodeFromString<dev.crumble.android.generated.SnapshotEventPayload>(snapshotFixture(ChatSnapshot(agentId = "a1", messages = listOf(message)))).domain()
        assertEquals(message.replyReferences, event.messages!!.single().replyReferences)
    }

    @Test
    fun onlyExactSurfaceIdentityAndVisibleReadyContentCounts() {
        val message = CrumbleJson.decodeFromString<Message>("""{"id":"m1","role":"assistant","content":"Answer","replyReferences":[{"eventId":"text","surface":{"kind":"text"}},{"eventId":"tool","surface":{"kind":"tool-card","toolCallId":"t1"}},{"eventId":"held","surface":{"kind":"held-action","actionId":"a1"}}]}""")
        assertEquals(listOf("text"), message.attentionEventIDs(DisplayedReplySurface.Text))
        assertEquals(listOf("tool"), message.attentionEventIDs(DisplayedReplySurface.ToolCard("t1")))
        assertEquals(listOf("held"), message.attentionEventIDs(DisplayedReplySurface.HeldAction("a1")))
        assertTrue(message.attentionEventIDs(DisplayedReplySurface.ToolCard("another")).isEmpty())
        assertTrue(message.attentionEventIDs(DisplayedReplySurface.HeldAction("another")).isEmpty())
        val viewport = Rect(0f, 100f, 300f, 400f)
        val content = Rect(10f, 150f, 200f, 200f)
        assertTrue(replySurfaceVisible(true, content, viewport))
        assertFalse(replySurfaceVisible(false, content, viewport))
        assertFalse(replySurfaceVisible(true, Rect.Zero, viewport))
        assertFalse(replySurfaceVisible(true, content, Rect.Zero))
        assertFalse(replySurfaceVisible(true, Rect(10f, 0f, 200f, 100f), viewport))
        assertFalse(replySurfaceVisible(true, Rect(10f, 400f, 200f, 500f), viewport))
    }

    @Test
    fun secretCardsRequireWaitingOrFreshConfirmedContentNotPlaceholders() = runTest {
        val model = AppModel(scope = backgroundScope, loadCredentials = false)
        model.selectAgent("a1")
        val ref = SecretRequestRef("TOKEN", listOf("example.test"), "now")
        val message = Message(id = "m", credentialRequest = ref, toolCallId = "t1")
        assertFalse(model.replyCardReady(message))
        model.decisionsFailed = true
        assertFalse(model.replyCardReady(message))
        model.decisionsFailed = false
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        assertFalse(model.replyCardReady(message))
        model.freshSecretRequests = setOf(credentialReadKey(ref))
        assertTrue(model.replyCardReady(message))
        model.freshSecretRequests = emptySet()
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), listOf(CredentialRequest("TOKEN", listOf("example.test"), "Needed", "now"))))
        assertTrue(model.replyCardReady(message))
    }

    @Test
    fun activeReplyEventRefreshesReferencesWithoutReplacingNewerSnapshotsOrNavigation() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        var held: CompletableDeferred<Unit>? = null
        var response = ChatSnapshot(agentId = "a1")
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequestData): HttpResponseData {
                requests.add(request)
                val body = when {
                    request.pathWithQuery.contains("/chat") -> {
                        val captured = snapshotFixture(response)
                        held?.await()
                        captured
                    }
                    request.pathWithQuery == "/v1/agents" -> "[${agentFixture("a1")}]"
                    else -> "[]"
                }
                return HttpResponseData(200, body.toByteArray())
            }
            override suspend fun openStream(request: HttpRequestData) = HttpStreamResponse(503, ByteArrayInputStream(byteArrayOf()))
        }
        val api = APIClient("https://crumble.test", "owner", transport)
        val app = AppModel(client = api, scope = backgroundScope)
        app.selectAgent("a1")
        runCurrent()
        app.appActive = true
        val gate = CompletableDeferred<Unit>()
        held = gate
        response = ChatSnapshot(agentId = "a1", messages = listOf(Message(id = "answer", entryId = "e1", role = "assistant", content = JsonPrimitive("Old"))))
        requests.clear()
        app.handleActivity(ActivityEvent(type = "reply", agentId = "a1", eventId = "reply-event"))
        runCurrent()
        assertEquals(1, requests.count { it.pathWithQuery.contains("/chat") })
        app.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(Message(id = "answer", entryId = "e1", role = "assistant", content = JsonPrimitive("New")))))
        gate.complete(Unit)
        runCurrent()
        assertEquals("New", app.messages.single().text)

        val navigationGate = CompletableDeferred<Unit>()
        held = navigationGate
        app.draft = "Keep this draft"
        app.handleActivity(ActivityEvent(type = "reply", agentId = "a1", eventId = "later-event"))
        runCurrent()
        app.route = Route.Tasks
        app.selectAgent("a1")
        runCurrent()
        navigationGate.complete(Unit)
        runCurrent()
        assertEquals("New", app.messages.single().text)
        assertEquals("Keep this draft", app.draft)
    }
}
