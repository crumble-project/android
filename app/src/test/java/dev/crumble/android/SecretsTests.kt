package dev.crumble.android

import dev.crumble.android.generated.Credential
import dev.crumble.android.generated.CredentialRequest
import dev.crumble.android.generated.Credentials
import dev.crumble.android.generated.LoginRequest
import dev.crumble.android.generated.Logins
import dev.crumble.android.generated.TranscriptMessage
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CredentialForm
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.LoginForm
import dev.crumble.android.model.LoginMode
import dev.crumble.android.model.Message
import dev.crumble.android.model.Route
import dev.crumble.android.model.SecretDraft
import dev.crumble.android.model.SecretRequestRef
import dev.crumble.android.model.SecretRequestState
import dev.crumble.android.model.credentialForm
import dev.crumble.android.model.credentialReadKey
import dev.crumble.android.model.hostList
import dev.crumble.android.model.isWebLink
import dev.crumble.android.model.loginFields
import dev.crumble.android.model.loginForm
import dev.crumble.android.model.restored
import dev.crumble.android.model.secretKey
import dev.crumble.android.model.secretRequestState
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.domain
import dev.crumble.android.storage.CredentialStore
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SECRET = "s3cret-value"

private fun bodyOf(request: HttpRequestData) =
    CrumbleJson.parseToJsonElement(request.body!!.toString(StandardCharsets.UTF_8)).jsonObject

private fun bodyText(request: HttpRequestData) = request.body?.toString(StandardCharsets.UTF_8).orEmpty()

/** Answers by "METHOD path", holding back any request whose key has a gate. */
private class SecretTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    val responses = mutableMapOf<String, Pair<Int, String>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        val key = "${request.method} ${request.pathWithQuery}"
        held[key]?.await()
        val (status, body) = responses[key] ?: (503 to """{"error":"unscripted $key"}""")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun sent(key: String) = requests.filter { "${it.method} ${it.pathWithQuery}" == key }
}

private class RecordingStore : CredentialStore {
    val written = mutableListOf<String>()
    override fun read(account: String): String? = null
    override fun write(account: String, value: String) {
        written.add(value)
    }

    override fun delete(account: String) = Unit
    override fun deletePrefixed(prefix: String) = Unit
}

private fun hostsJson(hosts: List<String>) = hosts.joinToString(",") { "\"$it\"" }

private fun credentialJson(name: String = "ACME_TOKEN", hosts: List<String> = listOf("api.acme.com"), updated: String = "2026-11-05T10:00:00Z") =
    """{"name":"$name","hosts":[${hostsJson(hosts)}],"secret":{"configured":true,"passwordManager":null,"reference":null},"createdAt":"$updated","updatedAt":"$updated","lastUsedAt":null,"lastError":null,"github":false}"""

private fun credentialRequestJson(name: String = "ACME_TOKEN", at: String = "t1") =
    """{"name":"$name","hosts":["api.acme.com"],"reason":"Deploys","requestedAt":"$at"}"""

private fun credentialsJson(items: List<String> = emptyList(), requests: List<String> = emptyList()) =
    """{"credentials":[${items.joinToString(",")}],"requests":[${requests.joinToString(",")}]}"""

private fun loginJson(name: String = "github") =
    """{"name":"$name","hosts":["github.com"],"fields":{"username":{"configured":true,"passwordManager":null,"reference":null},"password":{"configured":true,"passwordManager":null,"reference":null}},"createdAt":"t","updatedAt":"t","lastUsedAt":null,"lastError":null}"""

private fun loginsJson(items: List<String> = emptyList(), requests: List<String> = emptyList()) =
    """{"logins":[${items.joinToString(",")}],"requests":[${requests.joinToString(",")}]}"""

private fun managerJson() = """{"id":"1password","name":"1Password","connected":true,"connectedAt":"t","usedBy":[]}"""

private val credential = Credential("ACME_TOKEN", listOf("api.acme.com"), null, "t", "t", null, null, false)

private val credentialRequest = CredentialRequest("ACME_TOKEN", listOf("api.acme.com"), "Deploys", "t1")

private val loginRequest = LoginRequest("github", listOf("github.com"), "Sign in", false, "t1")

private const val CREDENTIALS = "/v1/agents/a1/credentials"
private const val LOGINS = "/v1/agents/a1/logins"
private const val PUT_CREDENTIAL = "PUT $CREDENTIALS/ACME_TOKEN"
private const val PUT_LOGIN = "PUT $LOGINS/github"
private const val MANAGERS = "GET /v1/password-managers"

private fun credentialMessage(requestedAt: String? = "t1") =
    Message(id = "t1", entryId = "t", role = "toolResult", credentialRequest = SecretRequestRef("ACME_TOKEN", listOf("api.acme.com"), requestedAt))

private fun loginMessage() = Message(id = "t2", entryId = "t2", role = "toolResult", loginRequest = SecretRequestRef("github", listOf("github.com"), "t1"))

@OptIn(ExperimentalCoroutinesApi::class)
class SecretsTests {
    private fun modelWith(transport: SecretTransport, scope: TestScope, store: CredentialStore? = null): AppModel {
        val client = APIClient(baseURL = "https://example.test", token = "session", transport = transport)
        val model = if (store == null) AppModel(client = client, scope = scope) else AppModel(client = client, credentials = store, scope = scope)
        return model.also { it.agents = listOf(Agent(id = "a1", name = "Atlas"), Agent(id = "a2", name = "Scout")) }
    }

    private fun scripted() = SecretTransport().apply {
        responses["GET /v1/agents"] = 200 to "[${agentFixture("a1")}]"
        responses["GET $CREDENTIALS"] = 200 to credentialsJson()
        responses["GET /v1/agents/a2/credentials"] = 200 to credentialsJson()
        responses["GET $LOGINS"] = 200 to loginsJson()
        responses["GET /v1/agents/a2/logins"] = 200 to loginsJson()
        responses[MANAGERS] = 200 to """{"passwordManagers":[${managerJson()}]}"""
    }

    private fun newCredential(model: AppModel, value: SecretDraft = SecretDraft(text = SECRET)) {
        model.openCredentialForm("a1", credentialRequest)
        model.credentialForm = model.credentialForm!!.copy(hosts = "API.acme.com, *.acme.com", secret = value)
    }

    @Test
    fun hostsSplitOnCommasSpacesAndLines() {
        assertEquals(listOf("api.acme.com", "*.acme.com", "b.io"), hostList(" API.acme.com, *.acme.com\nb.io "))
        assertEquals(emptyList<String>(), hostList(" , "))
    }

    @Test
    fun secretDraftsNeverPrintTheirValue() {
        val draft = SecretDraft(text = SECRET)
        val form = CredentialForm(owner = "a1", request = credentialRequest, name = "A", hosts = "a.io", secret = draft)
        val login = LoginForm(owner = "a1", request = loginRequest, password = draft)
        for (text in listOf(draft, form, login, draft.input)) {
            assertFalse(text.toString().contains(SECRET))
        }
    }

    @Test
    fun credentialFormChecksItsFieldsLikeTheWeb() {
        val form = credentialForm("a1", credentialRequest)
        assertFalse(form.copy(name = "acme", hosts = "a.io", secret = SecretDraft(text = "v")).ready)
        assertTrue(form.copy(name = "acme").nameInvalid)
        assertFalse(form.copy(name = "ACME", hosts = "", secret = SecretDraft(text = "v")).ready)
        assertFalse(form.copy(name = "ACME", hosts = "a.io").ready)
        assertTrue(form.copy(name = "ACME", hosts = "a.io", secret = SecretDraft(text = "v")).ready)
    }

    @Test
    fun aRequestLocksItsNameAndKeepsItsHosts() {
        val request = CredentialRequest("ACME_TOKEN", listOf("a.io", "b.io"), "Deploys", "t")
        val form = credentialForm("a1", request)
        assertEquals("a1", form.owner)
        assertEquals("ACME_TOKEN", form.name)
        assertEquals("a.io, b.io", form.hosts)
    }

    @Test
    fun loginRequestAsksForTheOneTimeCode() {
        val form = loginForm("a1", LoginRequest("github", listOf("github.com"), "Sign in", true, "t"))
        assertTrue(form.hasCode)
        val filled = form.copy(username = SecretDraft(text = "u"), password = SecretDraft(text = "p"))
        assertFalse(filled.ready)
        assertTrue(filled.copy(code = SecretDraft(text = "c")).ready)
    }

    @Test
    fun aOnePasswordItemFillsEveryField() {
        val form = loginForm("a1", loginRequest).copy(name = "github", hosts = "github.com", mode = LoginMode.item, item = " op://vault/item ")
        assertTrue(form.ready)
        assertTrue(form.readsPasswordManager)
        assertFalse(form.copy(item = "op://vault/item/field").ready)
        val body = loginFields(form)
        assertFalse("fields" in body)
        assertEquals(JsonPrimitive("op://vault/item"), (body["item"] as JsonObject)["reference"])
        assertEquals(JsonPrimitive("1password"), (body["item"] as JsonObject)["passwordManager"])
    }

    @Test
    fun cardStateNeedsAFreshReadToCallARequestAnswered() {
        fun state(request: String?, listed: Boolean = true, saved: Boolean = false, fresh: Boolean = true, failed: Boolean = false) =
            secretRequestState(request, listed, saved, fresh, failed)
        assertEquals(SecretRequestState.Loading, state(null, listed = false))
        assertEquals(SecretRequestState.Unavailable, state(null, listed = false, failed = true))
        assertEquals(SecretRequestState.Loading, state(null, fresh = false))
        assertEquals(SecretRequestState.Waiting("r"), state("r", fresh = false))
        assertEquals(SecretRequestState.Saved, state(null, saved = true))
        assertEquals(SecretRequestState.Declined, state(null))
    }

    @Test
    fun anAskFromALaterRequestDoesNotAnswerAnOlderCard() {
        val ref = SecretRequestRef("ACME_TOKEN", requestedAt = "t1")
        assertTrue(ref.answers("ACME_TOKEN", "t1"))
        assertFalse(ref.answers("ACME_TOKEN", "t2"))
        assertTrue(SecretRequestRef("ACME_TOKEN").answers("ACME_TOKEN", "t2"))
    }

    @Test
    fun onlyWebPagesLeaveTheApp() {
        assertTrue(isWebLink("https://auth.example.com/device"))
        assertTrue(isWebLink("HTTP://auth.example.com"))
        assertFalse(isWebLink("intent://scan#Intent;end"))
        assertFalse(isWebLink("javascript:alert(1)"))
    }

    @Test
    fun rollbackPutsARecordBackInPlaceAndKeepsOtherChanges() {
        val a = credential.copy(name = "A")
        val b = credential.copy(name = "B")
        val c = credential.copy(name = "C")
        val before = Credentials(listOf(a, b, c), emptyList())
        val after = Credentials(listOf(a, c, credential.copy(name = "D")), emptyList())
        assertEquals(listOf("A", "B", "C", "D"), after.restored(before, "B").credentials.map { it.name })
        assertEquals(listOf("A", "C", "D"), Credentials(listOf(a, c, credential.copy(name = "D")), emptyList()).restored(Credentials(emptyList(), emptyList()), "B").credentials.map { it.name })
        assertTrue(Logins(emptyList(), emptyList()).restored(Logins(emptyList(), emptyList()), "x").logins.isEmpty())
    }

    @Test
    fun transcriptResultsBecomeRequestCards() {
        fun message(tool: String, isError: Boolean = false) = CrumbleJson.decodeFromString(
            TranscriptMessage.serializer(),
            """{"id":"m","entryId":"e","role":"toolResult","content":"","toolName":"$tool","isError":$isError,"toolDetails":{"name":"ACME","hosts":["a.io"],"requestedAt":"t1"}}""",
        ).domain()
        assertEquals(SecretRequestRef("ACME", listOf("a.io"), "t1"), message("request_credential").credentialRequest)
        assertEquals(SecretRequestRef("ACME", listOf("a.io"), "t1"), message("request_login").loginRequest)
        assertNull(message("request_credential", isError = true).credentialRequest)
        assertNull(message("other").credentialRequest)
        assertTrue(ChatRow.shownInChat(message("request_login")))
    }

    @Test
    fun savingAValueShowsTheRowAtOnceAndForgetsTheSecret() = runTest {
        val transport = scripted()
        transport.responses[PUT_CREDENTIAL] = 200 to credentialJson(hosts = listOf("api.acme.com", "*.acme.com"), updated = "server")
        val gate = CompletableDeferred<Unit>().also { transport.held[PUT_CREDENTIAL] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        newCredential(model)

        model.saveCredential()
        testScheduler.runCurrent()

        assertNull(model.credentialForm)
        val shown = model.botCredentials["a1"]!!.credentials.single()
        assertEquals("", shown.updatedAt)
        assertEquals(listOf("api.acme.com", "*.acme.com"), shown.hosts)
        assertTrue(secretKey("credential", "a1", "ACME_TOKEN") in model.pendingMutations)
        val body = bodyOf(transport.sent(PUT_CREDENTIAL).single())
        assertEquals(JsonPrimitive(SECRET), (body["secret"] as JsonObject)["value"])
        assertEquals(JsonArray(listOf(JsonPrimitive("api.acme.com"), JsonPrimitive("*.acme.com"))), body["hosts"])

        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(listOf(credentialJson(updated = "server")))
        gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("server"), model.botCredentials["a1"]!!.credentials.map { it.updatedAt })
        assertTrue(model.pendingMutations.isEmpty())
    }

    @Test
    fun aFailedSaveRemovesTheRowAndReopensTheFormWithTheEntries() = runTest {
        val transport = scripted()
        transport.responses[PUT_CREDENTIAL] = 400 to """{"error":"bad hosts"}"""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        newCredential(model)

        model.saveCredential()
        testScheduler.runCurrent()

        assertTrue(model.botCredentials["a1"]!!.credentials.isEmpty())
        val form = model.credentialForm!!
        assertEquals("bad hosts", form.error)
        assertEquals(SECRET, form.secret.text)
        assertEquals("", model.errorText)

        transport.responses[PUT_CREDENTIAL] = 200 to credentialJson()
        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(listOf(credentialJson()))
        model.saveCredential()
        testScheduler.runCurrent()
        assertNull(model.credentialForm)
        assertEquals(1, model.botCredentials["a1"]!!.credentials.size)
    }

    @Test
    fun aFailureAfterLeavingDropsTheSecretAndReportsIt() = runTest {
        val transport = scripted()
        transport.responses[PUT_CREDENTIAL] = 400 to """{"error":"bad hosts"}"""
        val gate = CompletableDeferred<Unit>().also { transport.held[PUT_CREDENTIAL] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.route = Route.Memory
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        newCredential(model)
        model.saveCredential()
        testScheduler.runCurrent()

        model.openPanel(Route.Avatars)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.runCurrent()

        assertNull(model.credentialForm)
        assertEquals("bad hosts", model.errorText)
        assertTrue(model.botCredentials["a1"]!!.credentials.isEmpty())
    }

    @Test
    fun aReferenceStaysPendingUntilThePasswordManagerAnswers() = runTest {
        val transport = scripted()
        transport.responses[PUT_CREDENTIAL] = 200 to credentialJson()
        val gate = CompletableDeferred<Unit>().also { transport.held[PUT_CREDENTIAL] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        newCredential(model, SecretDraft(manager = "1password", text = "op://v/i/f"))

        model.saveCredential()
        testScheduler.runCurrent()

        assertTrue(model.credentialForm!!.saving)
        assertFalse(model.credentialForm!!.ready)
        assertTrue(model.botCredentials["a1"]!!.credentials.isEmpty())

        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(listOf(credentialJson()))
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(model.credentialForm)
        assertEquals(1, model.botCredentials["a1"]!!.credentials.size)
    }

    @Test
    fun aRefusedReferenceKeepsTheFormOpenWithItsError() = runTest {
        val transport = scripted()
        transport.responses[PUT_CREDENTIAL] = 400 to """{"error":"cannot read it"}"""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        newCredential(model, SecretDraft(manager = "1password", text = "op://v/i/f"))

        model.saveCredential()
        testScheduler.runCurrent()

        val form = model.credentialForm!!
        assertFalse(form.saving)
        assertEquals("cannot read it", form.error)
        assertEquals("op://v/i/f", form.secret.text)
    }

    @Test
    fun cardLoadsThenSavesAnswerAtOnceAndNeverStoresIt() = runTest {
        val transport = scripted()
        val store = RecordingStore()
        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(requests = listOf(credentialRequestJson()))
        transport.responses[PUT_CREDENTIAL] = 200 to credentialJson()
        val gate = CompletableDeferred<Unit>().also { transport.held[PUT_CREDENTIAL] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope, store)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(credentialMessage())))
        testScheduler.runCurrent()

        val request = model.botCredentials["a1"]!!.requests.single()
        assertTrue(credentialReadKey(credentialMessage().credentialRequest!!) in model.freshSecretRequests)
        model.openCredentialForm("a1", request)
        model.credentialForm = model.credentialForm!!.copy(secret = SecretDraft(text = SECRET))
        model.saveCredential()
        testScheduler.runCurrent()

        assertNull(model.credentialForm)
        assertTrue(model.botCredentials["a1"]!!.requests.isEmpty())
        assertEquals(1, model.botCredentials["a1"]!!.credentials.size)

        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(listOf(credentialJson()))
        gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(1, model.botCredentials["a1"]!!.credentials.size)
        assertTrue(transport.sent(PUT_CREDENTIAL).single().let(::bodyText).contains(SECRET))
        assertFalse(store.written.any { it.contains(SECRET) })
    }

    @Test
    fun aFailedCardAnswerComesBackWithItsErrorAndKeepsPolling() = runTest {
        val transport = scripted()
        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(requests = listOf(credentialRequestJson()))
        transport.responses[PUT_CREDENTIAL] = 503 to """{"error":"down"}"""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(credentialMessage())))
        testScheduler.runCurrent()
        val request = model.botCredentials["a1"]!!.requests.single()
        model.openCredentialForm("a1", request)
        model.credentialForm = model.credentialForm!!.copy(secret = SecretDraft(text = SECRET))

        model.saveCredential()
        testScheduler.runCurrent()

        assertEquals(SECRET, model.credentialForm?.secret?.text)
        assertEquals("down", model.credentialForm?.error)
        assertEquals(listOf(request), model.botCredentials["a1"]!!.requests)
        assertTrue(model.botCredentials["a1"]!!.credentials.isEmpty())

        val reads = transport.sent("GET $CREDENTIALS").size
        testScheduler.advanceTimeBy(5_100)
        testScheduler.runCurrent()
        assertTrue(transport.sent("GET $CREDENTIALS").size > reads)
        model.cancelStream()
    }

    @Test
    fun decliningACardSettlesItAtOnceAndRestoresItOnFailure() = runTest {
        val transport = scripted()
        transport.responses["GET $LOGINS"] = 200 to loginsJson(requests = listOf("""{"name":"github","hosts":["github.com"],"reason":"Sign in","oneTimeCode":false,"requestedAt":"t1"}"""))
        val decline = "POST $LOGINS/github/decline"
        transport.responses[decline] = 503 to """{"error":"down"}"""
        val gate = CompletableDeferred<Unit>().also { transport.held[decline] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(loginMessage())))
        testScheduler.runCurrent()
        assertEquals(1, model.botLogins["a1"]!!.requests.size)

        model.declineLoginRequest("a1", "github")
        testScheduler.runCurrent()
        assertTrue(model.botLogins["a1"]!!.requests.isEmpty())

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, model.botLogins["a1"]!!.requests.size)
        assertEquals("down", model.decisionErrors[secretKey("login", "a1", "github")])
        model.cancelStream()
    }

    @Test
    fun switchingBotsDropsTheOpenRequestForm() = runTest {
        val transport = scripted()
        transport.responses["GET /v1/agents/a2/chat"] = 503 to ""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openCredentialForm("a1", credentialRequest)
        model.selectAgent("a1")
        assertNotNull(model.credentialForm)
        model.selectAgent("a2")
        assertNull(model.credentialForm)
    }

    @Test
    fun theChatRequestOpensTheEditableSheetWithTheBotsHosts() = runTest {
        val transport = scripted()
        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(requests = listOf(credentialRequestJson()))
        transport.responses[PUT_CREDENTIAL] = 200 to credentialJson()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(credentialMessage())))
        testScheduler.runCurrent()
        val request = model.botCredentials["a1"]!!.requests.single()

        model.openCredentialForm("a1", request)
        val form = model.credentialForm!!
        assertEquals("a1", form.owner)
        assertEquals(request, form.request)
        assertEquals(request.hosts.joinToString(", "), form.hosts)

        model.credentialForm = form.copy(hosts = "api.acme.dev", secret = SecretDraft(text = SECRET))
        model.saveCredential()
        testScheduler.runCurrent()

        val body = bodyText(transport.sent(PUT_CREDENTIAL).single())
        assertTrue(body.contains("api.acme.dev"))
        assertFalse(request.hosts.any { body.contains(it) })
        assertEquals(Route.AgentChat("a1"), model.route)
        model.cancelStream()
    }

    @Test
    fun aChatRequestAnsweredFromAPasswordManagerWaitsForTheServer() = runTest {
        val transport = scripted()
        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(requests = listOf(credentialRequestJson()))
        transport.responses[PUT_CREDENTIAL] = 200 to credentialJson()
        val gate = CompletableDeferred<Unit>().also { transport.held[PUT_CREDENTIAL] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(credentialMessage())))
        testScheduler.runCurrent()
        val request = model.botCredentials["a1"]!!.requests.single()

        model.openCredentialForm("a1", request)
        val form = model.credentialForm!!
        model.credentialForm = form.copy(secret = SecretDraft(manager = "onepassword", text = "op://vault/item"))
        model.saveCredential()
        testScheduler.runCurrent()

        assertTrue(model.credentialForm!!.saving)
        assertEquals(listOf(request), model.botCredentials["a1"]!!.requests)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(model.credentialForm)
        model.cancelStream()
    }

    @Test
    fun leavingAScreenDropsItsForms() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        newCredential(model)
        model.openLoginForm("a1", loginRequest)
        model.navigate(Route.Avatars)
        assertNull(model.credentialForm)
        assertNull(model.loginForm)
    }

    @Test
    fun savingALoginWithValuesShowsTheRowAndClearsTheForm() = runTest {
        val transport = scripted()
        transport.responses[PUT_LOGIN] = 200 to loginJson()
        val gate = CompletableDeferred<Unit>().also { transport.held[PUT_LOGIN] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.botLogins = mapOf("a1" to Logins(emptyList(), emptyList()))
        model.openLoginForm("a1", loginRequest)
        model.loginForm = model.loginForm!!.copy(name = "github", hosts = "github.com", username = SecretDraft(text = "kev"), password = SecretDraft(text = SECRET))

        model.saveLogin()
        testScheduler.runCurrent()

        assertNull(model.loginForm)
        val shown = model.botLogins["a1"]!!.logins.single()
        assertNotNull(shown.fields.password)
        assertNull(shown.fields.one_time_code)
        val fields = bodyOf(transport.sent(PUT_LOGIN).single())["fields"] as JsonObject
        assertEquals(JsonPrimitive(SECRET), (fields["password"] as JsonObject)["value"])

        transport.responses["GET $LOGINS"] = 200 to loginsJson(listOf(loginJson()))
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("t", model.botLogins["a1"]!!.logins.single().createdAt)
    }

    @Test
    fun aOnePasswordItemWaitsForTheServerAndKeepsItsErrorInTheForm() = runTest {
        val transport = scripted()
        transport.responses[PUT_LOGIN] = 400 to """{"error":"cannot read the item"}"""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.botLogins = mapOf("a1" to Logins(emptyList(), emptyList()))
        model.openLoginForm("a1", loginRequest)
        model.loginForm = model.loginForm!!.copy(name = "github", hosts = "github.com", mode = LoginMode.item, item = "op://vault/item")

        model.saveLogin()
        assertTrue(model.loginForm!!.saving)
        testScheduler.runCurrent()

        assertEquals("cannot read the item", model.loginForm!!.error)
        assertTrue(model.botLogins["a1"]!!.logins.isEmpty())
    }
}
