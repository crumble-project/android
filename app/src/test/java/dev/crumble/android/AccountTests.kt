package dev.crumble.android

import dev.crumble.android.model.AccountMode
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.canCreateAccount
import dev.crumble.android.model.inviteToken
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.INVITE_HEADER
import dev.crumble.android.network.friendlyError
import dev.crumble.android.storage.InMemoryCredentialStore
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER = "https://crumble.example"
private const val ME = "user-me"
private const val SIGN_UP = "POST /api/auth/sign-up/email"
private const val SIGN_IN = "POST /api/auth/sign-in/email"
private const val SESSION = "GET /api/auth/get-session"

private class AccountTransport : HttpTransport {
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
    fun hold(key: String) = CompletableDeferred<Unit>().also { held[key] = it }
    fun answer(key: String, status: Int = 200, body: String) {
        responses[key] = status to body
    }
}

private fun sessionJson() = """{"session":{"id":"s","userId":"$ME"},"user":{"id":"$ME","role":"user"}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class AccountTests {
    private fun scripted() = AccountTransport().apply {
        answer(SESSION, body = sessionJson())
        answer("GET /v1/agents", body = "[]")
        answer("GET /v1/group-chats", body = """{"groups":[]}""")
        answer("GET /v1/password-managers", body = """{"passwordManagers":[]}""")
    }

    private fun TestScope.signedOut(transport: AccountTransport, store: InMemoryCredentialStore = InMemoryCredentialStore()): AppModel =
        // The activity stream retries forever once connected, so the model can't live in the test's own scope.
        AppModel(credentials = store, scope = backgroundScope, loadCredentials = false, transport = transport).also {
            it.serverURL = SERVER
            it.email = " new@example.com "
            it.password = "hunter2hunter2"
            it.accountName = " New Person "
            it.accountMode = AccountMode.SignUp
        }

    // MARK: Creating an account

    @Test
    fun signUpSendsTheInviteHeaderAndWaitsForTheServer() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, body = """{"token":"new-session","user":{"id":"u"}}""")
        val gate = transport.hold(SIGN_UP)
        val store = InMemoryCredentialStore()
        val model = signedOut(transport, store)
        model.inviteCode = "$SERVER/connect?invite=abc-_123"

        model.saveSettings()
        runCurrent()
        assertTrue(model.savingConnection)
        assertEquals("", model.token)
        assertEquals("", model.notice)

        gate.complete(Unit)
        runCurrent()
        val request = transport.sent(SIGN_UP).single()
        assertEquals("abc-_123", request.headers[INVITE_HEADER])
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals(
            """{"email":"new@example.com","password":"hunter2hunter2","name":"New Person"}""",
            request.body?.toString(StandardCharsets.UTF_8),
        )
        assertEquals("new-session", model.token)
        assertEquals("new-session", store.read("server-token"))
        assertEquals("", model.password)
        assertEquals(AccountMode.SignIn, model.accountMode)
        assertEquals("", model.inviteCode)
        assertEquals("", model.accountName)
        assertEquals("Connected", model.notice)
    }

    @Test
    fun signUpWithoutAnInviteOmitsTheHeader() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, body = """{"token":"new-session"}""")
        val model = signedOut(transport)
        model.inviteCode = "   "

        model.saveSettings()
        runCurrent()
        assertFalse(transport.sent(SIGN_UP).single().headers.containsKey(INVITE_HEADER))
    }

    @Test
    fun closedSignUpShowsTheServersReasonAndKeepsTheForm() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, 403, """{"code":"FORBIDDEN","message":"Sign-up is closed. Ask an admin for an invite link."}""")
        val model = signedOut(transport)
        model.inviteCode = "spent"

        model.saveSettings()
        runCurrent()
        assertEquals("Sign-up is closed. Ask an admin for an invite link.", model.errorText)
        assertEquals(AccountMode.SignUp, model.accountMode)
        assertEquals("hunter2hunter2", model.password)
        assertEquals("spent", model.inviteCode)
        assertEquals("", model.token)
        assertFalse(model.savingConnection)
    }

    @Test
    fun aRetryAfterACreatedAccountFailedToConnectSignsIn() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, body = """{"token":"new-session"}""")
        transport.answer("GET /v1/agents", 500, """{"error":"Starting up"}""")
        val model = signedOut(transport)
        model.inviteCode = "once"

        model.saveSettings()
        runCurrent()
        assertEquals("Your account was created, but connecting failed: Starting up", model.errorText)
        assertEquals(AccountMode.SignIn, model.accountMode)
        assertEquals("hunter2hunter2", model.password)
        assertEquals("", model.token)

        transport.answer("GET /v1/agents", body = "[]")
        transport.answer(SIGN_IN, body = """{"token":"signed-in"}""")
        model.saveSettings()
        runCurrent()
        assertEquals(1, transport.sent(SIGN_UP).size)
        assertEquals("signed-in", model.token)
    }

    @Test
    fun anIncompleteSignUpStaysLocal() = runTest {
        val transport = scripted()
        val model = signedOut(transport)
        model.accountName = "  "

        model.saveSettings()
        runCurrent()
        assertEquals("Enter your name, email, and password.", model.errorText)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun switchingModesClearsTheError() {
        val model = AppModel(loadCredentials = false)
        model.errorText = "old"
        model.switchAccountMode()
        assertEquals(AccountMode.SignUp, model.accountMode)
        assertEquals("", model.errorText)
        model.switchAccountMode()
        assertEquals(AccountMode.SignIn, model.accountMode)
    }

    @Test
    fun inviteTokensComeFromLinksOrBareCodes() {
        assertEquals("tok-_en", inviteToken(" https://bots.example/connect?invite=tok-_en\n"))
        assertEquals("a b", inviteToken("https://bots.example/connect?x=1&invite=a%20b"))
        assertEquals("tok-_en", inviteToken("  tok-_en "))
        assertEquals("", inviteToken("   "))
        assertEquals("not a link", inviteToken("not a link"))
    }

    @Test
    fun anAccountNeedsANameEmailAndPassword() {
        assertTrue(canCreateAccount("A", "a@b", "pw"))
        assertFalse(canCreateAccount(" ", "a@b", "pw"))
        assertFalse(canCreateAccount("A", " ", "pw"))
        assertFalse(canCreateAccount("A", "a@b", ""))
    }

    // MARK: Session

    @Test
    fun anEmptySessionReadsAsSignedOut() = runTest {
        val transport = scripted()
        transport.answer(SESSION, body = "null")
        val client = APIClient(baseURL = SERVER, token = "stale", transport = transport)
        val failure = runCatching { client.accountSession() }.exceptionOrNull()
        assertEquals("Authentication failed. Sign in again in Settings.", friendlyError(failure!!))
    }
}
