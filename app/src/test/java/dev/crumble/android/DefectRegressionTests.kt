package dev.crumble.android

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.AttachmentError
import dev.crumble.android.generated.Credential
import dev.crumble.android.model.AttachmentLimits
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.JobKind
import dev.crumble.android.model.JobRef
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageOrigin
import dev.crumble.android.model.ProviderStatus
import dev.crumble.android.model.Route
import dev.crumble.android.model.botProviderOptions
import dev.crumble.android.model.messageLabel
import dev.crumble.android.model.readAttachment
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.storage.InMemoryCredentialStore
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = "https://crumble.example"

private fun providerList(vararg providers: Pair<String, Boolean>) = providers.joinToString(",", "[", "]") { (id, configured) ->
    """{"provider":"$id","name":"${id.uppercase()}","configured":$configured,"authMethods":[{"type":"api_key","label":"Key","subscription":false}],"credentialType":null,"apiKey":{"configured":false,"passwordManager":null,"reference":null},"activeLoginId":null,"externalAuth":null}"""
}

private class RegressionTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    val responses = mutableMapOf<String, Pair<Int, String>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        val key = "${request.method} ${request.pathWithQuery}"
        requests.add(request)
        val (status, body) = responses[key] ?: (503 to """{"error":"unscripted"}""")
        held[key]?.await()
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun hold(key: String) = CompletableDeferred<Unit>().also { held[key] = it }

    fun sent(key: String) = requests.filter { "${it.method} ${it.pathWithQuery}" == key }

    fun json(key: String): JsonObject =
        CrumbleJson.parseToJsonElement(String(sent(key).single().body!!, StandardCharsets.UTF_8)).jsonObject
}

private class CountingStream(private val size: Int) : InputStream() {
    var served = 0

    override fun read(): Int = if (served < size) { served += 1; 'a'.code } else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (served >= size) return -1
        val count = minOf(len, size - served)
        b.fill('a'.code.toByte(), off, off + count)
        served += count
        return count
    }
}

private val RUNNABLE_BOT = Agent(id = "a1", name = "Atlas", provider = "openai", model = "gpt", tools = listOf("memory", "delegate"))

@OptIn(ExperimentalCoroutinesApi::class)
class DefectRegressionTests {
    private fun model(transport: RegressionTransport, scope: CoroutineScope, credentials: InMemoryCredentialStore = InMemoryCredentialStore(), onSignOut: suspend (APIClient) -> Unit = {}) =
        AppModel(client = APIClient(BASE, "session", transport), credentials = credentials, scope = scope, transport = transport, onSignOut = onSignOut)
            .also { it.agents = listOf(RUNNABLE_BOT) }

    // Activity recreation

    private fun appViewModel(store: ViewModelStore, transport: RegressionTransport) = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer {
                AppViewModel { scope, changed ->
                    AppModel(client = APIClient(BASE, "session", transport), scope = scope, transport = transport, onNotificationsChanged = changed)
                }
            }
        },
    )[AppViewModel::class]

    @Test
    fun aSaveStartedBeforeRecreationStillRollsBackInTheNewActivity() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ViewModelStore()
            val transport = RegressionTransport()
            val patch = transport.hold("PATCH /v1/agents/a1")
            val first = appViewModel(store, transport)
            val model = first.model
            model.agents = listOf(RUNNABLE_BOT)
            model.draft = "half a sentence"
            model.openEditor("a1")
            model.agentDraft = model.agentDraft!!.copy(name = "Renamed")
            model.saveAgentEditor()
            assertNull(model.agentDraft)

            // The old activity's lifecycle ends here; the store outlives it.
            val second = appViewModel(store, transport)
            assertSame(model, second.model)
            assertEquals("half a sentence", second.model.draft)

            patch.complete(Unit)
            runCurrent()
            assertEquals("Atlas", second.model.agents.single().name)
            assertEquals("Renamed", second.model.agentDraft?.name)
            store.clear()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun launchWorkAndItsIntentRunOncePerModel() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ViewModelStore()
            val owner = appViewModel(store, RegressionTransport())
            assertTrue(owner.start())
            assertFalse(appViewModel(store, RegressionTransport()).start())
            store.clear()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun notificationChangesReachTheCurrentActivityOnly() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<String>()
            var fired: () -> Unit = {}
            val store = ViewModelStore()
            val owner = ViewModelProvider.create(
                store,
                viewModelFactory { initializer { AppViewModel { scope, changed -> fired = changed; AppModel(client = null, scope = scope) } } },
            )[AppViewModel::class]
            owner.model
            owner.onNotificationsChanged = { calls.add("old") }
            owner.onNotificationsChanged = { calls.add("new") }
            fired()
            assertEquals(listOf("new"), calls)
            store.clear()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // Bot editor

    @Test
    fun aNewBotStartsOnTheFirstConfiguredProvider() = runTest {
        val model = model(RegressionTransport(), backgroundScope)
        model.providers = listOf(ProviderStatus("melious", "Melious", false), ProviderStatus("openai", "OpenAI", true))
        model.createAgent()
        assertEquals("openai", model.agentDraft!!.provider)
        assertEquals(listOf("openai"), botProviderOptions(model.providers, "openai").map { it.value })
    }

    @Test
    fun aProviderListThatArrivesLateFillsTheDefaultWithoutTouchingATypedModel() = runTest {
        val transport = RegressionTransport()
        transport.responses["GET /v1/providers"] = 200 to providerList("melious" to false, "openai" to true)
        val gate = transport.hold("GET /v1/providers")
        val model = model(transport, backgroundScope)
        model.createAgent()
        runCurrent()
        assertEquals("", model.agentDraft!!.provider)
        model.agentDraft = model.agentDraft!!.copy(model = "typed")

        gate.complete(Unit)
        runCurrent()
        assertEquals("openai", model.agentDraft!!.provider)
        assertEquals("typed", model.agentDraft!!.model)
    }

    @Test
    fun aMissingProviderOrModelStopsTheSave() = runTest {
        val transport = RegressionTransport()
        val model = model(transport, backgroundScope)
        model.createAgent()
        model.agentDraft = model.agentDraft!!.copy(name = "New")
        model.saveAgentEditor()
        runCurrent()
        assertTrue(transport.sent("POST /v1/agents").isEmpty())
        assertNotNull(model.agentDraft)
        assertTrue(model.errorText.isNotEmpty())

        model.agentDraft = model.agentDraft!!.copy(provider = "openai", model = "   ")
        model.saveAgentEditor()
        runCurrent()
        assertTrue(transport.sent("POST /v1/agents").isEmpty())
    }

    @Test
    fun theCurrentProviderStaysListedWhenItsKeyIsGone() {
        val providers = listOf(ProviderStatus("melious", "Melious", false), ProviderStatus("openai", "OpenAI", true))
        assertEquals(listOf("Melious (not set up)", "OpenAI"), botProviderOptions(providers, "melious").map { it.label })
        assertEquals(listOf("Choose a provider", "OpenAI"), botProviderOptions(providers, "").map { it.label })
    }

    @Test
    fun savingABotSendsNeitherDelegatesNorSharedMemory() = runTest {
        val transport = RegressionTransport()
        transport.responses["PATCH /v1/agents/a1"] = 200 to agentFixture("a1")
        transport.responses["POST /v1/agents"] = 201 to agentFixture("created", "New")
        val model = model(transport, backgroundScope)
        model.openEditor("a1")
        model.saveAgentEditor()
        runCurrent()
        val patch = transport.json("PATCH /v1/agents/a1")
        assertFalse(patch.containsKey("delegates"))
        assertFalse(patch.containsKey("sharedMemory"))

        model.createAgent()
        model.agentDraft = model.agentDraft!!.copy(name = "New", provider = "openai", model = "gpt")
        model.saveAgentEditor()
        runCurrent()
        val post = transport.json("POST /v1/agents")
        assertFalse(post.containsKey("delegates"))
        assertFalse(post.containsKey("sharedMemory"))
    }

    // Message origins

    private fun user(kind: String?, name: String? = null, source: String? = null) = Message(
        id = "m",
        role = "user",
        content = JsonPrimitive("hello"),
        origin = kind?.let { MessageOrigin(kind = it, name = name, source = source) },
    )

    @Test
    fun onlyTheOwnersMessagesAreLabelledYou() {
        assertEquals("You", messageLabel(user(null)))
        assertEquals("You", messageLabel(user("owner")))
        assertEquals("Automation · Deploy, event from GitHub", messageLabel(user("event", "Deploy", "GitHub")))
        assertEquals("Automation · Deploy, event from a deleted source", messageLabel(user("event", "Deploy")))
        assertEquals("Script automation · Nightly", messageLabel(user("script", "Nightly")))
        assertEquals("Credential · ACME_TOKEN", messageLabel(user("credential", "ACME_TOKEN")))
        assertEquals("Bot request", messageLabel(user("bot_request")))
    }

    @Test
    fun jobMessagesStayOutOfTheChat() {
        assertFalse(ChatRow.shownInChat(user("coding", "fix")))
        assertFalse(ChatRow.shownInChat(user("desktop", "browse")))
        assertTrue(ChatRow.shownInChat(user("event", "Deploy", "GitHub")))
        assertTrue(ChatRow.shownInChat(user(null)))
    }

    // Attachments

    @Test
    fun aHugeTextFileIsRefusedWithoutReadingItAll() {
        val stream = CountingStream(2 * AttachmentLimits.TEXT_BYTES)
        val error = assertThrows(AttachmentError::class.java) {
            readAttachment("big.txt", "text/plain", declaredSize = null) { stream }
        }
        assertEquals("big.txt is larger than 512 KB.", error.message)
        assertTrue("read ${stream.served}", stream.served <= AttachmentLimits.TEXT_BYTES + 8192)
    }

    @Test
    fun aDeclaredSizeOverTheLimitNeverOpensTheFile() {
        var opened = false
        assertThrows(AttachmentError::class.java) {
            readAttachment("big.txt", "text/plain", declaredSize = 10L * 1024 * 1024 * 1024) { opened = true; null }
        }
        assertFalse(opened)
    }

    @Test
    fun aBinaryFileIsRefusedWithoutReadingIt() {
        var opened = false
        val error = assertThrows(AttachmentError::class.java) {
            readAttachment("disk.img", "application/octet-stream", declaredSize = null) { opened = true; null }
        }
        assertEquals("disk.img can't be attached. Attach images or text files.", error.message)
        assertFalse(opened)
    }

    @Test
    fun anOversizedPhotoStillReachesTheReencoderButAHugeOneDoesNot() {
        val photo = readAttachment(
            "photo.jpg",
            "image/jpeg",
            declaredSize = null,
            jpegReencoder = { byteArrayOf(1, 2, 3) },
        ) { CountingStream(AttachmentLimits.IMAGE_BYTES + 1) }
        assertEquals("photo.jpg", photo.name)
        assertEquals(3, photo.data.size)

        val stream = CountingStream(AttachmentLimits.IMAGE_SOURCE_BYTES + 1024 * 1024)
        assertThrows(AttachmentError::class.java) {
            readAttachment("huge.jpg", "image/jpeg", declaredSize = null, jpegReencoder = { byteArrayOf(1) }) { stream }
        }
        assertTrue("read ${stream.served}", stream.served <= AttachmentLimits.IMAGE_SOURCE_BYTES + 8192)
    }

    // Timezone

    @Test
    fun aBotChatMessageCarriesTheDeviceTimezone() = runTest {
        val transport = RegressionTransport()
        transport.responses["POST /v1/agents/a1/messages"] = 200 to executionFixture
        val client = APIClient(BASE, "session", transport)
        client.sendChatMessage("a1", "hello", "request", "")
        val body = CrumbleJson.parseToJsonElement(String(transport.requests.single().body!!, StandardCharsets.UTF_8)).jsonObject
        assertEquals(JsonPrimitive(ZoneId.systemDefault().id), body["timezone"])
    }

    // Reconnecting

    private fun connecting(transport: RegressionTransport) = transport.apply {
        responses["POST /api/auth/sign-in/email"] = 200 to """{"token":"next"}"""
        responses["GET /v1/agents"] = 200 to "[${agentFixture("a1")}]"
        responses["POST /api/auth/sign-out"] = 200 to "{}"
    }

    @Test
    fun connectingWithANewSessionEndsTheOldOne() = runTest {
        val transport = connecting(RegressionTransport())
        val unregistered = mutableListOf<Pair<String, String?>>()
        lateinit var model: AppModel
        model = model(transport, backgroundScope, onSignOut = { unregistered.add(it.token to model.client?.token) })
        model.token = "session"
        model.serverURL = BASE
        model.email = "me@example.com"
        model.password = "secret"
        model.saveSettings()
        runCurrent()

        assertEquals("next", model.client?.token)
        assertEquals("the old session unregisters before the new one takes over", listOf("session" to "session"), unregistered)
        assertEquals("Bearer session", transport.sent("POST /api/auth/sign-out").single().headers["Authorization"])
    }

    @Test
    fun keepingTheSameSessionRevokesNothing() = runTest {
        val transport = connecting(RegressionTransport())
        val model = model(transport, backgroundScope)
        model.token = "session"
        model.serverURL = BASE
        model.saveSettings()
        runCurrent()

        assertTrue(transport.sent("POST /api/auth/sign-out").isEmpty())
    }

    @Test
    fun aFailedRevokeDoesNotUndoTheNewConnection() = runTest {
        val transport = connecting(RegressionTransport())
        transport.responses["POST /api/auth/sign-out"] = 503 to """{"error":"down"}"""
        val model = model(transport, backgroundScope)
        model.token = "session"
        model.serverURL = BASE
        model.email = "me@example.com"
        model.password = "secret"
        model.saveSettings()
        runCurrent()

        assertEquals("next", model.client?.token)
        assertEquals("", model.errorText)
    }

    // Panels and the bot list

    @Test
    fun reconnectingFromAPanelStaysOnThePanel() = runTest {
        val transport = connecting(RegressionTransport())
        val model = model(transport, backgroundScope)
        model.token = "session"
        model.serverURL = BASE
        model.navigate(Route.Memory)
        runCurrent()
        val reads = transport.sent("GET /v1/memories").size
        model.saveSettings()
        runCurrent()

        assertEquals(Route.Memory, model.route)
        assertEquals("a1", model.selectedAgentID)
        assertTrue(transport.sent("GET /v1/memories").size > reads)
    }

    // Job questions

    @Test
    fun aJobListThatLandsAfterASessionChangeIsDropped() = runTest {
        val transport = connecting(RegressionTransport())
        transport.responses["GET /v1/agents/a1/coding-jobs"] = 200 to """{"jobs":[{"name":"old","conversationId":"c","status":"idle"}]}"""
        transport.responses["GET /v1/agents/a1/desktop-jobs"] = 200 to """{"jobs":[]}"""
        transport.responses["POST /v1/agents/a1/coding-jobs/fix/answer"] = 200 to """{"answered":true}"""
        val model = model(transport, backgroundScope)
        model.token = "session"
        model.serverURL = BASE
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.coding, "fix"))
        runCurrent()
        val late = transport.hold("GET /v1/agents/a1/coding-jobs")
        model.answerJobQuestion("fix", "q1", "That one")
        runCurrent()

        transport.held.clear()
        transport.responses["GET /v1/agents/a1/coding-jobs"] = 200 to """{"jobs":[]}"""
        model.email = "me@example.com"
        model.password = "secret"
        model.saveSettings()
        runCurrent()
        model.selectAgent("a1")
        runCurrent()
        late.complete(Unit)
        runCurrent()
        assertTrue(model.jobs.none { it.name == "old" })
    }
}
