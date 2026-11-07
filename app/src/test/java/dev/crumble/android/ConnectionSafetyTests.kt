package dev.crumble.android

import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.MCPServer
import dev.crumble.android.model.Route
import dev.crumble.android.model.isExposedHttp
import dev.crumble.android.model.splitSources
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.storage.AndroidKeyStoreCredentials
import dev.crumble.android.storage.InMemoryCredentialStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val BASE = "https://example.test"
private const val CALLBACK = "dev.crumble.android:/oauth/callback?code=c&state=x"
private const val START = "POST /v1/mcp-servers/s1/oauth"
private const val FINISH = "POST /v1/mcp-servers/s1/oauth/callback"

private val SIGNED_IN =
    """{"id":"s1","name":"Linear","url":"https://mcp.linear.app/mcp","enabled":true,"agentId":null,"oauthClientId":null,"oauthClientSecret":{"configured":false,"passwordManager":null,"reference":null},"token":{"configured":false,"passwordManager":null,"reference":null},"error":null,"auth":"oauth","authenticated":true}"""

private class SafetyTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    val responses = mutableMapOf<String, Pair<Int, String>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        val key = "${request.method} ${request.pathWithQuery}"
        requests.add(request)
        held[key]?.await()
        val (status, body) = responses[key] ?: (503 to """{"error":"unscripted"}""")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun sent(key: String) = requests.count { "${it.method} ${it.pathWithQuery}" == key }

    fun hold(key: String) = CompletableDeferred<Unit>().also { held[key] = it }
}

private fun cachedChatKey(id: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$BASE\nsession".toByteArray())
    return "recent-chat:${digest.joinToString("") { "%02x".format(it) }}:$id"
}

private fun xml(path: String): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))

private fun Element.excludes(domain: String, path: String): Boolean {
    val rules = getElementsByTagName("exclude")
    return (0 until rules.length).map { rules.item(it) as Element }
        .any { it.getAttribute("domain") == domain && it.getAttribute("path") == path }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSafetyTests {
    private fun modelWith(transport: SafetyTransport, scope: CoroutineScope, credentials: InMemoryCredentialStore = InMemoryCredentialStore()) =
        AppModel(
            client = APIClient(BASE, "session", transport),
            credentials = credentials,
            scope = scope,
            transport = transport,
        ).also {
            it.agents = listOf(Agent(id = "a1", name = "Atlas"), Agent(id = "a2", name = "Scout"))
            it.mcpServers = listOf(MCPServer(id = "s1", name = "Linear"), MCPServer(id = "s2", name = "Notion"))
        }

    private fun signInTransport() = SafetyTransport().apply {
        responses[START] = 200 to """{"authorizationUrl":"https://auth.example.com/authorize?state=x"}"""
        responses["POST /v1/mcp-servers/s2/oauth"] = 200 to """{"authorizationUrl":"https://auth.example.com/authorize?state=y"}"""
        responses[FINISH] = 200 to SIGNED_IN
    }

    @Test
    fun dismissingSettingsDropsTypedSecrets() = runTest {
        val model = modelWith(SafetyTransport(), backgroundScope)
        model.navigate(Route.General)
        model.password = "typed"
        model.inviteCode = "invite"

        model.navigate(Route.General.parent)

        assertEquals("", model.password)
        assertEquals("", model.inviteCode)
    }

    @Test
    fun aConnectionThatFailsAfterDismissalLeavesNoSecretBehind() = runTest {
        val transport = SafetyTransport()
        val gate = transport.hold("POST /api/auth/sign-in/email")
        val model = modelWith(transport, backgroundScope)
        model.serverURL = BASE
        model.email = "me@example.com"
        model.password = "typed"
        model.saveSettings()
        runCurrent()

        model.navigate(Route.General)
        model.navigate(Route.General.parent)
        gate.complete(Unit)
        runCurrent()

        assertEquals("", model.password)
        assertFalse(model.savingConnection)
        assertTrue(model.errorText.isNotEmpty())
    }

    @Test
    fun leavingAPanelForTheSelectedBotDropsItsCredentialForm() = runTest {
        val model = modelWith(SafetyTransport(), backgroundScope)
        model.selectAgent("a1")
        runCurrent()
        model.openPanel(Route.Credentials)
        model.openCredentialForm("a1")
        assertNotNull(model.credentialForm)

        model.selectAgent("a1")

        assertEquals(Route.AgentChat("a1"), model.route)
        assertNull(model.credentialForm)
    }

    @Test
    fun reselectingTheOpenChatKeepsAnOpenCredentialForm() = runTest {
        val model = modelWith(SafetyTransport(), backgroundScope)
        model.selectAgent("a1")
        runCurrent()
        model.openCredentialForm("a1")

        model.selectAgent("a1")

        assertNotNull(model.credentialForm)
    }

    @Test
    fun deletingABotRemovesItsCachedChatOnceConfirmed() = runTest {
        val transport = SafetyTransport()
        val gate = transport.hold("DELETE /v1/agents/a1")
        transport.responses["DELETE /v1/agents/a1"] = 200 to ""
        val store = InMemoryCredentialStore(mapOf(cachedChatKey("a1") to "{}", cachedChatKey("a2") to "{}"))
        val model = modelWith(transport, backgroundScope, store)
        model.selectAgent("a1")
        runCurrent()

        model.deleteSelected()
        runCurrent()
        assertEquals("{}", store.read(cachedChatKey("a1")))

        gate.complete(Unit)
        runCurrent()
        assertNull(store.read(cachedChatKey("a1")))
        assertEquals("{}", store.read(cachedChatKey("a2")))
    }

    @Test
    fun aFailedBotDeleteKeepsItsCachedChat() = runTest {
        val transport = SafetyTransport()
        val store = InMemoryCredentialStore(mapOf(cachedChatKey("a1") to "{}"))
        val model = modelWith(transport, backgroundScope, store)
        model.selectAgent("a1")
        runCurrent()

        model.deleteSelected()
        runCurrent()

        assertEquals("{}", store.read(cachedChatKey("a1")))
        assertTrue(model.agents.any { it.id == "a1" })
    }

    @Test
    fun backingOutOfTheBrowserEndsTheWait() = runTest {
        val transport = signInTransport()
        val model = modelWith(transport, backgroundScope)
        val opened = mutableListOf<String>()
        model.beginMCPSignIn("s1") { opened.add(it) }
        runCurrent()
        assertEquals("s1", model.signingIn)
        assertEquals(1, opened.size)

        model.browserReturned()

        assertNull(model.signingIn)
        model.beginMCPSignIn("s2") { opened.add(it) }
        runCurrent()
        assertEquals("s2", model.signingIn)
        assertEquals(2, opened.size)
    }

    @Test
    fun resumingBeforeTheBrowserOpensKeepsThePendingState() = runTest {
        val transport = signInTransport()
        val gate = transport.hold(START)
        val model = modelWith(transport, backgroundScope)
        val opened = mutableListOf<String>()
        model.beginMCPSignIn("s1") { opened.add(it) }
        runCurrent()

        model.browserReturned()
        assertEquals("s1", model.signingIn)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, opened.size)
        assertEquals("s1", model.signingIn)
    }

    @Test
    fun theResumeAfterACallbackWaitsForTheServer() = runTest {
        val transport = signInTransport()
        val gate = transport.hold(FINISH)
        val model = modelWith(transport, backgroundScope)
        model.beginMCPSignIn("s1") {}
        runCurrent()

        model.handleOAuthCallback(CALLBACK)
        model.browserReturned()
        runCurrent()
        assertEquals("s1", model.signingIn)
        assertEquals("", model.notice)

        gate.complete(Unit)
        runCurrent()
        assertNull(model.signingIn)
        assertEquals("Signed in", model.notice)
        assertEquals(true, model.mcpServers.first { it.id == "s1" }.authenticated)
    }

    @Test
    fun aCallbackAfterLookingAwayStillFinishes() = runTest {
        val transport = signInTransport()
        val gate = transport.hold(FINISH)
        val model = modelWith(transport, backgroundScope)
        model.beginMCPSignIn("s1") {}
        runCurrent()
        model.browserReturned()

        model.handleOAuthCallback(CALLBACK)
        runCurrent()
        assertEquals("s1", model.signingIn)

        gate.complete(Unit)
        runCurrent()
        assertNull(model.signingIn)
        assertEquals("Signed in", model.notice)
    }

    @Test
    fun aRefusedCallbackAllowsARetry() = runTest {
        val transport = signInTransport()
        transport.responses[FINISH] = 400 to """{"error":"Sign-in expired"}"""
        val model = modelWith(transport, backgroundScope)
        model.beginMCPSignIn("s1") {}
        runCurrent()

        model.handleOAuthCallback(CALLBACK)
        runCurrent()

        assertNull(model.signingIn)
        assertTrue(model.errorText.contains("Sign-in expired"))
        assertEquals("", model.notice)
    }

    @Test
    fun aNewSignInReplacesAnAbandonedOne() = runTest {
        val transport = signInTransport()
        val gate = transport.hold("POST /v1/mcp-servers/s2/oauth")
        val model = modelWith(transport, backgroundScope)
        model.beginMCPSignIn("s1") {}
        runCurrent()
        model.browserReturned()
        model.beginMCPSignIn("s2") {}
        runCurrent()

        model.handleOAuthCallback(CALLBACK)
        runCurrent()

        assertEquals("s2", model.signingIn)
        assertEquals(0, transport.sent(FINISH))
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun aBrowserThatWontOpenReportsTheFailure() = runTest {
        val model = modelWith(signInTransport(), backgroundScope)

        model.beginMCPSignIn("s1") { throw IllegalStateException("No browser") }
        runCurrent()
        model.handleOAuthCallback(CALLBACK)
        runCurrent()

        assertNull(model.signingIn)
        assertEquals("", model.notice)
    }

    @Test
    fun onlyPublicHttpAddressesAreExposed() {
        for (url in listOf(
            "http://crumble.example",
            "HTTP://crumble.example:7337/",
            "  http://crumble.example  ",
            "http://8.8.8.8:7337",
            "http://172.32.0.1",
            "http://192.169.0.1",
            "http://11.0.0.1",
            "http://[2001:4860:4860::8888]",
            "http://localhost.evil.example",
            "http://2130706433",
        )) assertTrue(url, isExposedHttp(url))

        for (url in listOf(
            "https://crumble.example",
            "http://localhost:7337",
            "http://LOCALHOST.",
            "http://app.localhost",
            "http://127.0.0.1:7337",
            "http://127.8.8.8",
            "http://10.0.2.2:7337",
            "http://172.16.0.1",
            "http://172.31.255.1",
            "http://192.168.1.20",
            "http://169.254.1.1",
            "http://[::1]:7337",
            "http://[fd00::1]",
            "http://[fe80::1]",
            "http://[::ffff:192.168.1.20]",
            "",
            "not a url",
            "http://999.1.1.1",
        )) assertFalse(url, isExposedHttp(url))
    }

    @Test
    fun sessionResetRunsOnSignOutAndReconnect() = runTest {
        val transport = SafetyTransport()
        transport.responses["POST /api/auth/sign-out"] = 200 to "{}"
        transport.responses["POST /api/auth/sign-in/email"] = 200 to """{"token":"next"}"""
        transport.responses["GET /v1/agents"] = 200 to "[]"
        var resets = 0
        val model = AppModel(
            client = APIClient(BASE, "session", transport),
            scope = backgroundScope,
            transport = transport,
            onSessionReset = { resets += 1 },
        )
        model.serverURL = BASE
        model.email = "me@example.com"
        model.password = "typed"
        model.saveSettings()
        runCurrent()
        assertEquals(1, resets)

        model.signOut()
        runCurrent()
        assertEquals(2, resets)
    }

    @Test
    fun citationsOfOtherSchemesNeverBecomeChips() {
        val reply = splitSources("See [source](javascript:alert(1)) and [source](intent://x).", mapOf("x" to "Title"))
        assertTrue(reply.chips.isEmpty())
    }

    @Test
    fun backupsLeaveOutTheCredentialPreferences() {
        val prefs = "${AndroidKeyStoreCredentials.SERVICE}.xml"
        val full = xml("src/main/res/xml/backup_rules.xml").documentElement
        assertTrue(full.excludes("sharedpref", prefs))

        val extraction = xml("src/main/res/xml/data_extraction_rules.xml").documentElement
        for (scope in listOf("cloud-backup", "device-transfer")) {
            val rules = extraction.getElementsByTagName(scope).item(0) as Element
            assertTrue(scope, rules.excludes("sharedpref", prefs))
        }

        val application = xml("src/main/AndroidManifest.xml").documentElement
            .getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
    }
}
