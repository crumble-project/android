package dev.crumble.android

import dev.crumble.android.generated.Credential
import dev.crumble.android.generated.CredentialRequest
import dev.crumble.android.generated.Credentials
import dev.crumble.android.generated.Login
import dev.crumble.android.generated.LoginFields
import dev.crumble.android.generated.LoginRequest
import dev.crumble.android.generated.Logins
import dev.crumble.android.generated.PasswordManager
import dev.crumble.android.generated.Provider
import dev.crumble.android.generated.ProviderExternalAuth
import dev.crumble.android.generated.ProviderLoginMethod
import dev.crumble.android.generated.SecretStatus
import dev.crumble.android.generated.TranscriptMessage
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CredentialForm
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.LoginForm
import dev.crumble.android.model.LoginMode
import dev.crumble.android.model.ManagerTokenForm
import dev.crumble.android.model.Message
import dev.crumble.android.model.ProviderKeyForm
import dev.crumble.android.model.Route
import dev.crumble.android.model.SKILLS_ALL
import dev.crumble.android.model.SecretDraft
import dev.crumble.android.model.SecretInput
import dev.crumble.android.model.SecretKind
import dev.crumble.android.model.SecretRequestRef
import dev.crumble.android.model.SecretRequestState
import dev.crumble.android.model.SecretRow
import dev.crumble.android.model.credentialFields
import dev.crumble.android.model.credentialForm
import dev.crumble.android.model.credentialReadKey
import dev.crumble.android.model.hostList
import dev.crumble.android.model.isWebLink
import dev.crumble.android.model.keepPlaceholder
import dev.crumble.android.model.loginFields
import dev.crumble.android.model.loginForm
import dev.crumble.android.model.matchingProviders
import dev.crumble.android.model.providerStatus
import dev.crumble.android.model.restored
import dev.crumble.android.model.secretBotsShown
import dev.crumble.android.model.secretDraft
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
import kotlinx.serialization.json.JsonNull
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

private fun managerJson(id: String = "1password", connected: Boolean = true, usedBy: String = "") =
    """{"id":"$id","name":"1Password","connected":$connected,"connectedAt":${if (connected) "\"t\"" else "null"},"usedBy":[$usedBy]}"""

private fun providerJson(id: String = "openai", credentialType: String? = null, activeLogin: String? = null, configured: Boolean = credentialType != null) =
    """{"provider":"$id","name":"OpenAI","configured":$configured,"authMethods":[{"type":"api_key","label":"Key","subscription":false},{"type":"oauth","label":"ChatGPT","subscription":true}],"credentialType":${credentialType?.let { "\"$it\"" } ?: "null"},"apiKey":{"configured":${credentialType == "api_key"},"passwordManager":null,"reference":null},"activeLoginId":${activeLogin?.let { "\"$it\"" } ?: "null"},"externalAuth":null}"""

private fun sessionJson(status: String = "pending", prompt: String = "null", events: String = "") =
    """{"id":"l1","provider":"openai","method":"oauth","status":"$status","createdAt":1,"expiresAt":2,"prompt":$prompt,"events":[$events],"failure":null}"""

private fun promptJson(id: String = "p1", type: String = "secret") =
    """{"id":"$id","type":"$type","message":"Paste the code"}"""

private val credential = Credential("ACME_TOKEN", listOf("api.acme.com"), null, "t", "t", null, null, false)

private val provider = Provider(
    provider = "openai", name = "OpenAI", configured = false,
    authMethods = listOf(ProviderLoginMethod("oauth", "ChatGPT", true)),
    credentialType = null, apiKey = SecretStatus(false, null, null), activeLoginId = null, externalAuth = null,
)

private const val CREDENTIALS = "/v1/agents/a1/credentials"
private const val LOGINS = "/v1/agents/a1/logins"
private const val PUT_CREDENTIAL = "PUT $CREDENTIALS/ACME_TOKEN"
private const val PUT_LOGIN = "PUT $LOGINS/github"
private const val MANAGERS = "GET /v1/password-managers"
private const val SESSION = "GET /v1/provider-logins/l1"

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
        responses["GET /v1/providers"] = 200 to "[${providerJson()}]"
    }

    private fun newCredential(model: AppModel, value: SecretDraft = SecretDraft(text = SECRET)) {
        model.openCredentialForm("a1")
        model.credentialForm = model.credentialForm!!.copy(name = "ACME_TOKEN", hosts = "API.acme.com, *.acme.com", secret = value)
    }

    @Test
    fun hostsSplitOnCommasSpacesAndLines() {
        assertEquals(listOf("api.acme.com", "*.acme.com", "b.io"), hostList(" API.acme.com, *.acme.com\nb.io "))
        assertEquals(emptyList<String>(), hostList(" , "))
    }

    @Test
    fun secretDraftsNeverPrintTheirValue() {
        val draft = SecretDraft(text = SECRET)
        val form = CredentialForm(botID = "a1", owner = "a1", name = "A", hosts = "a.io", secret = draft)
        val login = LoginForm(botID = "a1", owner = "a1", password = draft)
        val token = ManagerTokenForm(PasswordManager("1password", "1Password", false, null, emptyList()), draft)
        val key = ProviderKeyForm(provider, draft)
        for (text in listOf(draft, form, login, token, key, draft.input)) {
            assertFalse(text.toString().contains(SECRET))
        }
    }

    @Test
    fun aSavedReferenceStartsTheDraftAndStaysUnchangedUntilEdited() {
        val saved = secretDraft(SecretStatus(true, "1password", "op://v/i/f"))
        assertEquals("op://v/i/f", saved.text)
        assertFalse(saved.changed)
        assertTrue(saved.copy(text = " op://v/i/g ").changed)
        assertEquals(SecretInput.Reference("1password", "op://v/i/g"), saved.copy(text = " op://v/i/g ").input)
        assertEquals("", secretDraft(SecretStatus(true, null, null)).text)
        assertNull(saved.source(null).input)
    }

    @Test
    fun credentialFormChecksItsFieldsLikeTheWeb() {
        val form = credentialForm("a1", emptyList())
        assertFalse(form.copy(name = "acme", hosts = "a.io", secret = SecretDraft(text = "v")).ready)
        assertTrue(form.copy(name = "acme").nameInvalid)
        assertFalse(form.copy(name = "ACME", hosts = "", secret = SecretDraft(text = "v")).ready)
        assertFalse(form.copy(name = "ACME", hosts = "a.io").ready)
        assertTrue(form.copy(name = "ACME", hosts = "a.io", secret = SecretDraft(text = "v")).ready)
        assertTrue(credentialForm("a1", emptyList(), existing = credential).ready)
    }

    @Test
    fun aRequestLocksItsNameAndKeepsItsHosts() {
        val request = CredentialRequest("ACME_TOKEN", listOf("a.io", "b.io"), "Deploys", "t")
        val form = credentialForm(null, listOf(Agent(id = "a1", name = "Atlas")), request = request)
        assertEquals("a1", form.owner)
        assertNull(form.botID)
        assertTrue(form.locked)
        assertEquals("a.io, b.io", form.hosts)
    }

    @Test
    fun loginRequestAsksForTheOneTimeCode() {
        val form = loginForm("a1", emptyList(), request = LoginRequest("github", listOf("github.com"), "Sign in", true, "t"))
        assertTrue(form.hasCode)
        val filled = form.copy(username = SecretDraft(text = "u"), password = SecretDraft(text = "p"))
        assertFalse(filled.ready)
        assertTrue(filled.copy(code = SecretDraft(text = "c")).ready)
    }

    @Test
    fun aSavedCredentialKeepsItsSecretUnlessChanged() {
        val existing = credential.copy(secret = dev.crumble.android.generated.CredentialSecret(true, "1password", "op://v/i/f"))
        val form = credentialForm("a1", emptyList(), existing = existing)
        assertFalse("secret" in credentialFields(form))
        assertFalse(form.readsPasswordManager)
        assertNull(keepPlaceholder(existing))
        val moved = form.copy(secret = form.secret.source("bitwarden").copy(text = "id"))
        assertTrue(moved.readsPasswordManager)
        assertEquals(
            JsonObject(mapOf("reference" to JsonObject(mapOf("passwordManager" to JsonPrimitive("bitwarden"), "reference" to JsonPrimitive("id"))))),
            credentialFields(moved)["secret"],
        )
    }

    @Test
    fun turningOffTheCodeRemovesOnlyASavedOne() {
        val saved = Login(
            "github", listOf("github.com"),
            LoginFields(SecretStatus(true, null, null), SecretStatus(true, null, null), SecretStatus(true, null, null)),
            "t", "t", null, null,
        )
        val form = loginForm("a1", emptyList(), existing = saved)
        val off = loginFields(form.copy(hasCode = false))["fields"] as JsonObject
        assertEquals(JsonNull, off["one_time_code"])
        assertEquals(setOf("one_time_code"), off.keys)
        val untouched = loginFields(form)["fields"] as JsonObject
        assertTrue(untouched.isEmpty())
        val plain = loginFields(loginForm("a1", emptyList()).copy(username = SecretDraft(text = "u")))["fields"] as JsonObject
        assertEquals(setOf("username"), plain.keys)
    }

    @Test
    fun aOnePasswordItemFillsEveryField() {
        val form = loginForm("a1", emptyList()).copy(name = "github", hosts = "github.com", mode = LoginMode.item, item = " op://vault/item ")
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
    fun onlyBotsWithMatchesOrFailuresGetAFrameAcrossAllBots() {
        val rows = mapOf("a1" to listOf(SecretRow("ACME", listOf("api.acme.com"), null, null)), "a2" to emptyList())
        val ids = listOf("a1", "a2", "a3")
        assertEquals(listOf("a1", "a3"), secretBotsShown(ids, SKILLS_ALL, "", { rows[it].orEmpty() }, { it == "a3" }))
        assertEquals(listOf("a1"), secretBotsShown(ids, SKILLS_ALL, "acme.com", { rows[it].orEmpty() }, { false }))
        assertEquals(listOf("a2"), secretBotsShown(ids, "a2", "zzz", { rows[it].orEmpty() }, { false }))
    }

    @Test
    fun providerStatusFollowsTheWeb() {
        val manager = PasswordManager("1password", "1Password", true, "t", emptyList())
        assertEquals("Signed in", providerStatus(provider.copy(credentialType = "oauth"), emptyList()))
        assertEquals("Key saved", providerStatus(provider.copy(credentialType = "api_key"), emptyList()))
        val referenced = provider.copy(credentialType = "api_key", apiKey = SecretStatus(true, "1password", "op://a/b/c"))
        assertEquals("Key in 1Password", providerStatus(referenced, listOf(manager)))
        assertEquals("Key in a password manager", providerStatus(referenced, emptyList()))
        assertEquals("From environment", providerStatus(provider.copy(configured = true), emptyList()))
        assertEquals("Signing in…", providerStatus(provider.copy(activeLoginId = "l1"), emptyList()))
        assertNull(providerStatus(provider, emptyList()))
        val external = ProviderExternalAuth("cli_missing", null, null, null, "Install it")
        assertEquals("Not installed", providerStatus(provider.copy(externalAuth = external), emptyList()))
    }

    @Test
    fun providersSortByNameAndSearchNameOrID() {
        val zeta = provider.copy(provider = "zeta", name = "Zeta")
        val acme = provider.copy(provider = "acme", name = "Acme")
        assertEquals(listOf("acme", "zeta"), matchingProviders(listOf(zeta, acme), "").map { it.provider })
        assertEquals(listOf("zeta"), matchingProviders(listOf(zeta, acme), "zet").map { it.provider })
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
    fun aLateListDoesNotReplaceAPendingSave() = runTest {
        val transport = scripted()
        transport.responses[PUT_CREDENTIAL] = 200 to credentialJson(updated = "server")
        val list = CompletableDeferred<Unit>().also { transport.held["GET $CREDENTIALS"] = it }
        val save = CompletableDeferred<Unit>().also { transport.held[PUT_CREDENTIAL] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.openPanel(Route.Credentials)
        testScheduler.runCurrent()
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        newCredential(model)
        model.saveCredential()
        testScheduler.runCurrent()

        list.complete(Unit)
        testScheduler.runCurrent()

        assertEquals("", model.botCredentials["a1"]!!.credentials.single().updatedAt)
        transport.responses["GET $CREDENTIALS"] = 200 to credentialsJson(listOf(credentialJson(updated = "server")))
        save.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("server", model.botCredentials["a1"]!!.credentials.single().updatedAt)
    }

    @Test
    fun aFailedSaveRemovesTheRowAndReopensTheFormWithTheEntries() = runTest {
        val transport = scripted()
        transport.responses[PUT_CREDENTIAL] = 400 to """{"error":"bad hosts"}"""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.route = Route.Credentials
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
        model.route = Route.Credentials
        model.botCredentials = mapOf("a1" to Credentials(emptyList(), emptyList()))
        newCredential(model)
        model.saveCredential()
        testScheduler.runCurrent()

        model.openPanel(Route.Logins)
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
        model.openCredentialForm("a1", request = request)
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
        model.openCredentialForm("a1", request = request)
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
        model.openCredentialForm("a1")
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

        model.openCredentialForm("a1", request = request)
        val form = model.credentialForm!!
        assertEquals("a1", form.botID)
        assertEquals(request, form.request)
        assertEquals(request.hosts.joinToString(", "), form.hosts)
        assertTrue(form.locked)

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

        model.openCredentialForm("a1", request = request)
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
        model.openLoginForm("a1")
        model.openProviderKey(provider)
        model.tokenForm = ManagerTokenForm(PasswordManager("1password", "1Password", false, null, emptyList()), SecretDraft(text = SECRET))
        model.navigate(Route.PasswordManagers)
        assertNull(model.credentialForm)
        assertNull(model.loginForm)
        assertNull(model.providerKeyForm)
        assertNull(model.tokenForm)
    }

    @Test
    fun savingALoginWithValuesShowsTheRowAndClearsTheForm() = runTest {
        val transport = scripted()
        transport.responses[PUT_LOGIN] = 200 to loginJson()
        val gate = CompletableDeferred<Unit>().also { transport.held[PUT_LOGIN] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.botLogins = mapOf("a1" to Logins(emptyList(), emptyList()))
        model.openLoginForm("a1")
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
        model.openLoginForm("a1")
        model.loginForm = model.loginForm!!.copy(name = "github", hosts = "github.com", mode = LoginMode.item, item = "op://vault/item")

        model.saveLogin()
        assertTrue(model.loginForm!!.saving)
        testScheduler.runCurrent()

        assertEquals("cannot read the item", model.loginForm!!.error)
        assertTrue(model.botLogins["a1"]!!.logins.isEmpty())
    }

    @Test
    fun deletingAppliesAtOnceAndRestoresTheRowInPlaceOnFailure() = runTest {
        val transport = scripted()
        val delete = "DELETE $CREDENTIALS/B"
        transport.responses[delete] = 503 to """{"error":"down"}"""
        val gate = CompletableDeferred<Unit>().also { transport.held[delete] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.route = Route.Credentials
        model.botCredentials = mapOf("a1" to Credentials(listOf("A", "B", "C").map { credential.copy(name = it) }, emptyList()))

        model.deleteCredential("a1", "B")
        testScheduler.runCurrent()
        assertEquals(listOf("A", "C"), model.botCredentials["a1"]!!.credentials.map { it.name })

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("A", "B", "C"), model.botCredentials["a1"]!!.credentials.map { it.name })
        assertEquals("down", model.errorText)
    }

    @Test
    fun usesPageByTheLastRowUntilAShortPage() = runTest {
        val transport = scripted()
        val first = (1..50).joinToString(",") { """{"id":"u$it","at":"t","method":"GET","host":"a.io","path":"/x","status":${if (it == 1) "null" else "200"},"error":null}""" }
        transport.responses["GET $CREDENTIALS/ACME_TOKEN/uses?limit=50"] = 200 to "[$first]"
        transport.responses["GET $CREDENTIALS/ACME_TOKEN/uses?limit=50&before=u50"] = 200 to """[{"id":"u51","at":"t","method":"POST","host":"a.io","path":"/y","status":201,"error":null}]"""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)

        model.openSecretUses(SecretKind.credential, "a1", "ACME_TOKEN")
        testScheduler.runCurrent()
        val view = model.secretUses!!
        assertEquals(50, view.rows.size)
        assertTrue(view.hasMore)
        assertEquals("GET a.io/x", view.rows[0].detail)
        assertEquals("failed", view.rows[0].note)

        model.loadMoreSecretUses()
        testScheduler.runCurrent()
        assertEquals(51, model.secretUses!!.rows.size)
        assertFalse(model.secretUses!!.hasMore)
    }

    @Test
    fun fillsNameTheFieldAndALateReplyIsDroppedOnceClosed() = runTest {
        val transport = scripted()
        val path = "GET $LOGINS/github/uses?limit=50"
        transport.responses[path] = 200 to """[{"id":"u1","at":"t","host":"github.com","field":"one_time_code","error":"expired"}]"""
        val gate = CompletableDeferred<Unit>().also { transport.held[path] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.openSecretUses(SecretKind.login, "a1", "github")
        testScheduler.runCurrent()
        model.closeSecretUses()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(model.secretUses)

        transport.held.remove(path)
        model.openSecretUses(SecretKind.login, "a1", "github")
        testScheduler.runCurrent()
        assertEquals("One-time code on github.com", model.secretUses!!.rows.single().detail)
        assertEquals("expired", model.secretUses!!.rows.single().note)
    }

    @Test
    fun connectingAPasswordManagerStaysPendingAndClearsTheToken() = runTest {
        val transport = scripted()
        val connect = "PUT /v1/password-managers/1password"
        transport.responses[MANAGERS] = 200 to """{"passwordManagers":[${managerJson(connected = false)}]}"""
        transport.responses[connect] = 200 to managerJson()
        val gate = CompletableDeferred<Unit>().also { transport.held[connect] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.loadPasswordManagers()
        testScheduler.runCurrent()
        val manager = model.passwordManagers.single()
        model.openManagerToken(manager)
        model.tokenForm = model.tokenForm!!.copy(token = SecretDraft(text = " $SECRET "))

        model.saveManagerToken()
        testScheduler.runCurrent()
        assertTrue(model.tokenForm!!.saving)
        assertFalse(model.passwordManagers.single().connected)
        assertEquals(JsonPrimitive(SECRET), bodyOf(transport.sent(connect).single())["token"])

        transport.responses[MANAGERS] = 200 to """{"passwordManagers":[${managerJson()}]}"""
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(model.tokenForm)
        assertTrue(model.passwordManagers.single().connected)
    }

    @Test
    fun aRefusedTokenStaysInTheFormWithTheError() = runTest {
        val transport = scripted()
        transport.responses["PUT /v1/password-managers/1password"] = 400 to """{"error":"1Password rejected the token"}"""
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.openManagerToken(PasswordManager("1password", "1Password", false, null, emptyList()))
        model.tokenForm = model.tokenForm!!.copy(token = SecretDraft(text = SECRET))

        model.saveManagerToken()
        testScheduler.runCurrent()

        assertEquals("1Password rejected the token", model.tokenForm!!.error)
        assertEquals(SECRET, model.tokenForm!!.token.text)
        assertFalse(model.tokenForm!!.saving)
    }

    @Test
    fun disconnectingAppliesAtOnceAndRollsBackWhenRefused() = runTest {
        val transport = scripted()
        val disconnect = "DELETE /v1/password-managers/1password"
        transport.responses[disconnect] = 409 to """{"error":"1 secret still read from this password manager"}"""
        val gate = CompletableDeferred<Unit>().also { transport.held[disconnect] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.loadPasswordManagers()
        testScheduler.runCurrent()

        model.disconnectManager(model.passwordManagers.single())
        testScheduler.runCurrent()
        assertFalse(model.passwordManagers.single().connected)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(model.passwordManagers.single().connected)
        assertEquals("1 secret still read from this password manager", model.errorText)
    }

    @Test
    fun aManagerInUseCannotBeDisconnected() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.disconnectManager(PasswordManager("1password", "1Password", true, "t", listOf("secret")))
        testScheduler.runCurrent()
        assertTrue(transport.sent("DELETE /v1/password-managers/1password").isEmpty())
    }

    @Test
    fun providerKeySavesAtOnceAndComesBackOnFailure() = runTest {
        val transport = scripted()
        val put = "PUT /v1/providers/openai"
        transport.responses[put] = 503 to """{"error":"down"}"""
        val gate = CompletableDeferred<Unit>().also { transport.held[put] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.route = Route.Providers
        model.loadProviders()
        testScheduler.runCurrent()
        val account = model.providerAccounts.single()
        model.openProviderKey(account)
        model.providerKeyForm = model.providerKeyForm!!.copy(key = SecretDraft(text = SECRET))

        model.saveProviderAccountKey()
        testScheduler.runCurrent()
        assertNull(model.providerKeyForm)
        assertEquals("Key saved", providerStatus(model.providerAccounts.single(), emptyList()))
        assertEquals(JsonPrimitive(SECRET), bodyOf(transport.sent(put).single())["value"])

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(account, model.providerAccounts.single())
        assertEquals(SECRET, model.providerKeyForm!!.key.text)
        assertEquals("down", model.providerKeyForm!!.error)
    }

    @Test
    fun aProviderKeyReferenceWaitsAndRefreshesModels() = runTest {
        val transport = scripted()
        val put = "PUT /v1/providers/openai"
        transport.responses[put] = 200 to """{"provider":"openai","configured":true}"""
        val gate = CompletableDeferred<Unit>().also { transport.held[put] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.loadProviders()
        testScheduler.runCurrent()
        model.openProviderKey(model.providerAccounts.single())
        model.providerKeyForm = model.providerKeyForm!!.copy(key = SecretDraft(manager = "1password", text = "op://v/i/f"))

        model.saveProviderAccountKey()
        testScheduler.runCurrent()
        assertTrue(model.providerKeyForm!!.saving)
        assertNull(model.providerAccounts.single().credentialType)
        val reference = bodyOf(transport.sent(put).single())["reference"] as JsonObject
        assertEquals(JsonPrimitive("op://v/i/f"), reference["reference"])

        transport.responses["GET /v1/providers"] = 200 to "[${providerJson(credentialType = "api_key")}]"
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(model.providerKeyForm)
        assertEquals("api_key", model.providerAccounts.single().credentialType)
    }

    @Test
    fun signOutAppliesAtOnceAndRollsBackOnFailure() = runTest {
        val transport = scripted()
        transport.responses["GET /v1/providers"] = 200 to "[${providerJson(credentialType = "oauth")}]"
        val delete = "DELETE /v1/providers/openai"
        transport.responses[delete] = 503 to """{"error":"down"}"""
        val gate = CompletableDeferred<Unit>().also { transport.held[delete] = it }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.loadProviders()
        testScheduler.runCurrent()
        val account = model.providerAccounts.single()

        model.removeProviderCredential(account)
        testScheduler.runCurrent()
        assertNull(model.providerAccounts.single().credentialType)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(account, model.providerAccounts.single())
        assertEquals("down", model.errorText)
    }

    private fun startedLogin(transport: SecretTransport, scope: TestScope): AppModel {
        val model = modelWith(transport, scope)
        transport.responses["POST /v1/providers/openai/login"] = 201 to sessionJson()
        transport.responses[SESSION] = 200 to sessionJson(prompt = promptJson())
        return model
    }

    @Test
    fun signInShowsSuccessOnlyOnceTheServerConfirms() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = startedLogin(transport, scope)
        val gate = CompletableDeferred<Unit>().also { transport.held["POST /v1/providers/openai/login"] = it }

        model.openProviderLogin(provider)
        model.startProviderLogin()
        testScheduler.runCurrent()
        assertTrue(model.providerLogin!!.starting)
        assertNull(model.providerLogin!!.session)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("l1", model.providerLogin!!.sessionID)
        assertEquals("p1", model.providerLogin!!.session!!.prompt!!.id)
        assertEquals(JsonPrimitive("oauth"), bodyOf(transport.sent("POST /v1/providers/openai/login").single())["method"])

        model.providerLogin = model.providerLogin!!.copy(answer = SecretDraft(text = SECRET))
        val answer = "POST /v1/provider-logins/l1/responses"
        transport.responses[answer] = 200 to sessionJson(prompt = promptJson("p2", "text"))
        val answerGate = CompletableDeferred<Unit>().also { transport.held[answer] = it }
        model.answerProviderLogin(SECRET)
        testScheduler.runCurrent()
        assertTrue(model.providerLogin!!.answering)

        // A read from before the answer must not bring the old prompt back.
        testScheduler.advanceTimeBy(1_100)
        testScheduler.runCurrent()
        answerGate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("p2", model.providerLogin!!.session!!.prompt!!.id)
        assertEquals("", model.providerLogin!!.answer.text)
        val body = bodyOf(transport.sent(answer).single())
        assertEquals(JsonPrimitive("p1"), body["promptId"])
        assertEquals(JsonPrimitive(SECRET), body["value"])

        transport.responses[SESSION] = 200 to sessionJson(status = "succeeded")
        transport.responses["GET /v1/providers"] = 200 to "[${providerJson(credentialType = "oauth")}]"
        testScheduler.advanceTimeBy(1_100)
        testScheduler.runCurrent()
        assertEquals("succeeded", model.providerLogin!!.session!!.status)
        assertEquals("oauth", model.providerAccounts.single().credentialType)
        model.hideProviderLogin()
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
    }

    @Test
    fun aFailedAnswerKeepsTheEntryAndTheSignIn() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = startedLogin(transport, scope)
        transport.responses["POST /v1/provider-logins/l1/responses"] = 400 to """{"error":"wrong code"}"""
        model.openProviderLogin(provider.copy(activeLoginId = "l1"))
        testScheduler.runCurrent()
        model.providerLogin = model.providerLogin!!.copy(answer = SecretDraft(text = SECRET))

        model.answerProviderLogin(SECRET)
        testScheduler.runCurrent()

        assertEquals("wrong code", model.providerLogin!!.answerError)
        assertEquals(SECRET, model.providerLogin!!.answer.text)
        assertFalse(model.providerLogin!!.answering)
        model.hideProviderLogin()
    }

    @Test
    fun aNewPromptDropsTheOldEntry() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = startedLogin(transport, scope)
        model.openProviderLogin(provider.copy(activeLoginId = "l1"))
        testScheduler.runCurrent()
        model.providerLogin = model.providerLogin!!.copy(answer = SecretDraft(text = SECRET))

        transport.responses[SESSION] = 200 to sessionJson(prompt = promptJson("p2"))
        testScheduler.advanceTimeBy(1_100)
        testScheduler.runCurrent()

        assertEquals("", model.providerLogin!!.answer.text)
        model.hideProviderLogin()
    }

    @Test
    fun aLostSignInStopsReadingAndSaysSo() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        transport.responses[SESSION] = 410 to """{"error":"gone"}"""
        model.openProviderLogin(provider.copy(activeLoginId = "l1"))
        testScheduler.runCurrent()
        assertTrue(model.providerLogin!!.gone)
        val reads = transport.sent(SESSION).size
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertEquals(reads, transport.sent(SESSION).size)
    }

    @Test
    fun cancellingClosesEvenWhenTheServerFailsAndHidingStopsReading() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = startedLogin(transport, scope)
        transport.responses["DELETE /v1/provider-logins/l1"] = 503 to """{"error":"down"}"""
        model.openProviderLogin(provider.copy(activeLoginId = "l1"))
        testScheduler.runCurrent()

        model.cancelProviderLogin()
        testScheduler.runCurrent()
        assertNull(model.providerLogin)
        val reads = transport.sent(SESSION).size
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertEquals(reads, transport.sent(SESSION).size)
    }

    @Test
    fun aLateSignInReplyIsDroppedAfterHiding() = runTest {
        val transport = scripted()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = startedLogin(transport, scope)
        val gate = CompletableDeferred<Unit>().also { transport.held["POST /v1/providers/openai/login"] = it }
        model.openProviderLogin(provider)
        model.startProviderLogin()
        testScheduler.runCurrent()
        model.hideProviderLogin()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(model.providerLogin)
        assertTrue(transport.sent(SESSION).isEmpty())
    }
}
