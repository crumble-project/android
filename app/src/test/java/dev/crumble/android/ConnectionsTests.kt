package dev.crumble.android

import dev.crumble.android.generated.PluginSetting
import dev.crumble.android.generated.PluginSettings
import dev.crumble.android.generated.PluginSettingsPluginsItem
import dev.crumble.android.generated.SecretStatus
import dev.crumble.android.generated.ServerSettings
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CreateGitHubAppForm
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.GatewayAlias
import dev.crumble.android.model.GatewayAliasRow
import dev.crumble.android.model.GatewayChannelRow
import dev.crumble.android.model.GatewayConfig
import dev.crumble.android.model.GitHubStep
import dev.crumble.android.model.MCPDraft
import dev.crumble.android.model.MCPServer
import dev.crumble.android.model.PluginRow
import dev.crumble.android.model.Route
import dev.crumble.android.model.SecretDraft
import dev.crumble.android.model.SecretInput
import dev.crumble.android.model.ServerSettingsForm
import dev.crumble.android.model.WorkspaceField
import dev.crumble.android.model.gatewayBotOptions
import dev.crumble.android.model.gatewayErrors
import dev.crumble.android.model.gatewayForm
import dev.crumble.android.model.isGitHubReturn
import dev.crumble.android.model.judgeOptions
import dev.crumble.android.model.judgeValue
import dev.crumble.android.model.matchesMCP
import dev.crumble.android.model.pluginRows
import dev.crumble.android.model.relayStatusLabel
import dev.crumble.android.model.serverSettingsForm
import dev.crumble.android.model.toggled
import dev.crumble.android.model.visionOptions
import dev.crumble.android.model.visionStatus
import dev.crumble.android.model.webOrigin
import dev.crumble.android.model.workspaceHelp
import dev.crumble.android.model.workspaceRowTitle
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SECRET = "s3cret-value"
private const val BOT_A = "00000000-0000-4000-8000-000000000001"
private const val BOT_B = "00000000-0000-4000-8000-000000000002"

private class ConnectionTransport : HttpTransport {
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

private fun bodyText(request: HttpRequestData) = request.body?.toString(StandardCharsets.UTF_8).orEmpty()
private fun bodyOf(request: HttpRequestData) = CrumbleJson.parseToJsonElement(bodyText(request)).jsonObject

private const val NONE = """{"configured":false,"passwordManager":null,"reference":null}"""
private const val SAVED = """{"configured":true,"passwordManager":null,"reference":null}"""

private fun appJson(id: String = "app1", bots: String = "", installed: Boolean = true, user: String? = null, secret: String = NONE) =
    """{"id":"$id","slug":"$id","name":"$id","login":"$id[bot]","htmlUrl":"https://github.com/apps/$id","installUrl":"https://github.com/apps/$id/installations/new","account":"leo","installed":$installed,"clientSecret":$secret,"user":${user?.let { "\"$it\"" }},"botIds":[$bots],"createdAt":"t"}"""

private fun accountJson(id: String = "acc1", bots: String = "") =
    """{"id":"$id","login":"$id","htmlUrl":"https://github.com/$id","botIds":[$bots],"token":$SAVED,"signingKey":"ssh-ed25519 AAA","createdAt":"t"}"""

private fun gatewayJson(enabled: Boolean = true, owner: String? = null, status: String = "connected") =
    """{"id":"discord","label":"Discord","secretKey":"discord:token","tokenConfigured":true,"config":{"enabled":$enabled,"defaultBotId":null,"ownerId":${owner?.let { "\"$it\"" }},"allowedChannelIds":[],"allowDirectMessages":false,"aliases":[]},"status":"$status","error":null,"pendingCount":0,"failedCount":0}"""

private fun relayJson(url: String? = null, status: String = "disconnected", error: String? = null) =
    """{"url":${url?.let { "\"$it\"" }},"tokenConfigured":false,"status":"$status","error":${error?.let { "\"$it\"" }}}"""

private fun judgeJson(chosen: String? = null) =
    """{"judgeModel":${chosen?.let { """{"provider":"openai","model":"$it"}""" }},"effective":{"provider":"openai","model":"luna","name":"Luna","kind":"classifier"},"choices":[{"provider":"openai","model":"luna","name":"Luna","kind":"classifier"},{"provider":"openai","model":"sol","name":"Sol","kind":"classifier"}]}"""

private fun settingsJson(cpus: Int = 2, memory: Int = 4096, image: String? = "img", grace: Int = 30) =
    """{"signUpOpen":false,"workspaceCpus":$cpus,"workspaceMemoryMib":$memory,"codingImage":${image?.let { "\"$it\"" }},"questionGraceMinutes":$grace}"""

private fun pluginJson(configured: Boolean = false) =
    """{"key":"token","kind":"secret","label":"Bot token","whenMissing":"x","secret":${if (configured) SAVED else NONE}}"""

private fun pluginsJson(configured: Boolean = false) =
    """{"plugins":[{"id":"discord","contributes":["chatAdapters"],"settings":[${pluginJson(configured)}]}]}"""

private val PLUGIN_PATH = "/v1/plugin-settings/discord/token"

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsTests {
    private fun modelWith(transport: ConnectionTransport, scope: TestScope): AppModel {
        val client = APIClient(baseURL = "https://crumble.example:8443/", token = "session", transport = transport)
        return AppModel(client = client, scope = scope).also {
            it.agents = listOf(Agent(id = BOT_A, name = "Atlas"), Agent(id = BOT_B, name = "Scout"))
        }
    }

    private fun scripted() = ConnectionTransport().apply {
        answer("GET /v1/agents", body = "[${agentFixture(BOT_A)},${agentFixture(BOT_B, "Scout")}]")
        answer("GET /v1/password-managers", body = """{"passwordManagers":[]}""")
        answer("GET /v1/plugin-settings", body = pluginsJson())
        answer("GET /v1/message-gateways", body = """{"gateways":[${gatewayJson()}]}""")
        answer("GET /v1/github-apps", body = """{"apps":[${appJson()}]}""")
        answer("GET /v1/github-accounts", body = """{"accounts":[${accountJson()}]}""")
        answer("GET /v1/admin/settings", body = settingsJson())
        answer("GET /v1/capabilities", body = """{"coding":true,"desktop":false,"jobKinds":["coding"]}""")
        answer("GET /v1/event-relay", body = relayJson())
        answer("GET /v1/event-sources", body = """{"sources":[]}""")
        answer("GET /v1/judge-model", body = judgeJson())
        answer("GET /v1/vision-model", body = """{"visionModel":null,"choices":[{"provider":"openai","model":"eye","name":"Eye"}]}""")
        answer("GET /v1/web-search", body = """{"defaultProvider":"mixed","exaKeyConfigured":false}""")
    }

    // MARK: Pure rules

    @Test
    fun gatewayConfigMatchesTheServerRules() {
        val bots = setOf(BOT_A)
        assertEquals(emptyMap<String, String>(), gatewayErrors(GatewayConfig(ownerId = "12345"), bots))
        assertEquals("Enter one user ID, without spaces or commas.", gatewayErrors(GatewayConfig(ownerId = "1 2"), bots)["ownerId"])
        assertEquals("Enter one user ID, without spaces or commas.", gatewayErrors(GatewayConfig(ownerId = "1,2"), bots)["ownerId"])
        assertEquals(
            "Use letters, numbers, underscores or hyphens, without @.",
            gatewayErrors(GatewayConfig(aliases = listOf(GatewayAlias("@me", BOT_A))), bots)["aliases"],
        )
        assertEquals(
            "Choose a bot for every alias.",
            gatewayErrors(GatewayConfig(aliases = listOf(GatewayAlias("me", ""))), bots)["aliases"],
        )
        assertEquals(
            "Alias handles must be unique (ignoring case)",
            gatewayErrors(GatewayConfig(aliases = listOf(GatewayAlias("Me", BOT_A), GatewayAlias("mE", BOT_A))), bots)["aliases"],
        )
        assertEquals(
            emptyMap<String, String>(),
            gatewayErrors(GatewayConfig(aliases = listOf(GatewayAlias("résumé_1", BOT_A))), bots),
        )
    }

    @Test
    fun deletedBotsBlockOnlyAnEnabledGateway() {
        val gone = GatewayConfig(enabled = true, defaultBotId = BOT_B, aliases = listOf(GatewayAlias("a", BOT_B)))
        val errors = gatewayErrors(gone, setOf(BOT_A))
        assertEquals("The saved bot was deleted. Choose another.", errors["defaultBotId"])
        assertEquals("Choose an existing bot for every alias.", errors["aliases"])
        assertTrue(gatewayErrors(gone.copy(enabled = false), setOf(BOT_A)).isEmpty())
        assertEquals("Deleted bot", gatewayBotOptions(emptyList(), BOT_B, optional = true).last().label)
    }

    @Test
    fun gatewayFormTrimsAndDedupesLikeTheWeb() {
        val form = gatewayForm("discord", GatewayConfig()).copy(
            owner = "  ",
            channels = listOf(GatewayChannelRow(" 1 "), GatewayChannelRow("1"), GatewayChannelRow("  ")),
            aliases = listOf(GatewayAliasRow(" me ", BOT_A)),
        )
        val config = form.config()
        assertNull(config.ownerId)
        assertEquals(listOf("1"), config.allowedChannelIds)
        assertEquals(listOf(GatewayAlias("me", BOT_A)), config.aliases)
    }

    @Test
    fun pluginRowsFilterByContributionPluginAndKey() {
        val status = SecretStatus(false, null, null)
        val settings = PluginSettings(
            listOf(
                PluginSettingsPluginsItem("exa", listOf("webSearchProviders"), listOf(PluginSetting("key", "secret", "Exa key", "x", status))),
                PluginSettingsPluginsItem("doorstep", emptyList(), listOf(PluginSetting("token", "secret", "Token", "x", status))),
                PluginSettingsPluginsItem(
                    "discord", listOf("chatAdapters"),
                    listOf(PluginSetting("token", "secret", "Discord", "x", status), PluginSetting("other", "secret", "Other", "x", status)),
                ),
            ),
        )
        assertEquals(listOf("exa"), pluginRows(settings, contributes = "webSearchProviders").map { it.pluginID })
        assertEquals(listOf("doorstep"), pluginRows(settings, pluginID = "doorstep").map { it.pluginID })
        assertEquals(listOf("Discord"), pluginRows(settings, contributes = "chatAdapters", secretKey = "discord:token").map { it.setting.label })
        assertTrue(pluginRows(null).isEmpty())
    }

    @Test
    fun returnPagesMatchOnlyTheirStepAndOlderPrefixes() {
        assertTrue(isGitHubReturn("https://c.example/github-app/callback?code=1&state=2", GitHubStep.created))
        assertTrue(isGitHubReturn("https://c.example/ui/github-app/authorized?code=1", GitHubStep.authorized))
        assertFalse(isGitHubReturn("https://c.example/github-app/installed", GitHubStep.created))
        assertFalse(isGitHubReturn("https://github.com/settings/apps/new", GitHubStep.created))
        assertFalse(isGitHubReturn("not a url", GitHubStep.installed))
        assertEquals("https://crumble.example:8443", webOrigin("https://crumble.example:8443/"))
        assertEquals("https://crumble.example", webOrigin("https://crumble.example/app"))
    }

    @Test
    fun workspaceRowsAndNumbersReadLikeTheWeb() {
        val settings = ServerSettings(false, 4, 2048, null, 30)
        assertEquals("Workspace size (4 CPUs, 2 GB)", workspaceRowTitle(WorkspaceField.size, settings))
        assertEquals("Coding image (server default)", workspaceRowTitle(WorkspaceField.image, settings))
        assertEquals("Question grace period (30 min)", workspaceRowTitle(WorkspaceField.grace, settings))
        assertEquals(listOf("Desktop jobs need CRUMBLE_WORKSPACE_DESKTOP_IMAGE on the server."), workspaceHelp(coding = true, desktop = false))
        val size = serverSettingsForm(WorkspaceField.size, settings)
        assertEquals("2", size.memory)
        assertNull(size.copy(cpus = "many").patch())
        assertNull(ServerSettingsForm(WorkspaceField.grace, "1", "1", "", "soon").patch())
    }

    @Test
    fun aGoneModelStaysListedWithItsReason() {
        val judge = CrumbleJson.decodeFromString<dev.crumble.android.generated.JudgeModel>(judgeJson("gone"))
        assertEquals("openai/gone", judgeValue(judge))
        assertEquals("gone (no key)", judgeOptions(judge).last().label)
        val vision = CrumbleJson.decodeFromString<dev.crumble.android.generated.VisionModel>(
            """{"visionModel":{"provider":"openai","model":"gone"},"choices":[]}""",
        )
        assertEquals("gone (no key)", visionOptions(vision).last().label)
        assertTrue(visionStatus(vision).startsWith("No provider key reaches gone"))
    }

    @Test
    fun relayLabelsAndBotToggles() {
        assertEquals("Not set up", relayStatusLabel("disconnected"))
        assertEquals(listOf("a", "b"), toggled(listOf("a"), "b", true))
        assertEquals(emptyList<String>(), toggled(listOf("a"), "a", false))
        assertTrue(matchesMCP(MCPServer(name = "Linear", url = "https://mcp.linear.app"), "linear"))
        assertFalse(matchesMCP(MCPServer(name = "Linear", url = "https://mcp.linear.app"), "notion"))
    }

    // MARK: Choices

    @Test
    fun judgeChoiceAppliesAtOnceAndAReadCannotReplaceIt() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.JudgeModel)
        runCurrent()
        transport.answer("PUT /v1/judge-model", body = judgeJson("sol"))
        val gate = transport.hold("PUT /v1/judge-model")

        model.chooseJudgeModel("openai/sol")
        runCurrent()
        assertEquals("openai/sol", judgeValue(model.judgeModel.value!!))
        assertEquals("""{"judgeModel":{"provider":"openai","model":"sol"}}""", bodyText(transport.sent("PUT /v1/judge-model").single()))

        model.loadJudgeModel()
        runCurrent()
        assertEquals("openai/sol", judgeValue(model.judgeModel.value!!))

        gate.complete(Unit)
        runCurrent()
        assertEquals("openai/sol", judgeValue(model.judgeModel.value!!))
        assertTrue(model.pendingMutations.isEmpty())
    }

    @Test
    fun judgeChoiceRollsBackAndSaysWhy() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.JudgeModel)
        runCurrent()
        transport.answer("PUT /v1/judge-model", 500, """{"error":"Could not save"}""")

        model.chooseJudgeModel("openai/sol")
        runCurrent()
        assertEquals("default", judgeValue(model.judgeModel.value!!))
        assertEquals("Could not save", model.judgeModel.failure)
    }

    @Test
    fun choosingTheDefaultSendsNull() = runTest {
        val transport = scripted()
        transport.answer("GET /v1/judge-model", body = judgeJson("sol"))
        transport.answer("PUT /v1/judge-model", body = judgeJson())
        val model = modelWith(transport, this)
        model.navigate(Route.JudgeModel)
        runCurrent()
        model.chooseJudgeModel("default")
        runCurrent()
        assertEquals("""{"judgeModel":null}""", bodyText(transport.sent("PUT /v1/judge-model").single()))
    }

    @Test
    fun visionAndWebSearchRollBackOnFailure() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.VisionModel)
        model.navigate(Route.WebSearch)
        runCurrent()
        transport.answer("PUT /v1/vision-model", 500, """{"error":"no"}""")
        transport.answer("PUT /v1/web-search", 500, """{"error":"nope"}""")

        model.chooseVisionModel("openai/eye")
        model.chooseWebSearchProvider("exa")
        assertEquals("exa", model.webSearch.value?.defaultProvider)
        assertNotNull(model.visionModel.value?.visionModel)
        runCurrent()
        assertNull(model.visionModel.value?.visionModel)
        assertEquals("mixed", model.webSearch.value?.defaultProvider)
        assertEquals("nope", model.webSearch.failure)
    }

    // MARK: Plugin settings

    private fun pluginRow(model: AppModel) = pluginRows(model.pluginSettings.value).single()

    @Test
    fun pluginValueAppliesAtOnceAndReopensOnFailure() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.MessageGateway("discord"))
        runCurrent()
        val row = pluginRow(model)
        val gate = transport.hold("PUT $PLUGIN_PATH")
        transport.answer("PUT $PLUGIN_PATH", 500, """{"error":"Bad token"}""")

        model.openPluginSetting(row)
        model.pluginForm = model.pluginForm?.copy(secret = SecretDraft(text = SECRET))
        model.savePluginSetting()
        runCurrent()
        assertNull(model.pluginForm)
        assertTrue(pluginRow(model).setting.secret.configured)
        assertEquals("""{"value":"$SECRET"}""", bodyText(transport.sent("PUT $PLUGIN_PATH").single()))

        gate.complete(Unit)
        runCurrent()
        assertFalse(pluginRow(model).setting.secret.configured)
        assertEquals("Bad token", model.pluginForm?.error)
        assertEquals(SECRET, model.pluginForm?.secret?.text)
    }

    @Test
    fun pluginReferenceStaysPendingUntilThePasswordManagerAnswers() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.MessageGateway("discord"))
        runCurrent()
        val gate = transport.hold("PUT $PLUGIN_PATH")
        transport.answer("PUT $PLUGIN_PATH", body = pluginJson(configured = true))
        transport.answer("GET /v1/plugin-settings", body = pluginsJson(configured = true))
        val refreshes = transport.sent("GET /v1/message-gateways").size

        model.openPluginSetting(pluginRow(model))
        model.pluginForm = model.pluginForm?.copy(secret = SecretDraft(manager = "1password", text = "op://v/i/f"))
        model.savePluginSetting()
        runCurrent()
        assertTrue(model.pluginForm?.saving == true)
        assertFalse(pluginRow(model).setting.secret.configured)
        assertEquals(
            """{"reference":{"passwordManager":"1password","reference":"op://v/i/f"}}""",
            bodyText(transport.sent("PUT $PLUGIN_PATH").single()),
        )

        gate.complete(Unit)
        runCurrent()
        assertNull(model.pluginForm)
        assertTrue(pluginRow(model).setting.secret.configured)
        assertTrue(transport.sent("GET /v1/message-gateways").size > refreshes)
    }

    @Test
    fun pluginRemovalRollsBack() = runTest {
        val transport = scripted()
        transport.answer("GET /v1/plugin-settings", body = pluginsJson(configured = true))
        val model = modelWith(transport, this)
        model.navigate(Route.EventRelay)
        runCurrent()
        val row: PluginRow = pluginRow(model)
        val gate = transport.hold("DELETE $PLUGIN_PATH")
        transport.answer("DELETE $PLUGIN_PATH", 500, """{"error":"nope"}""")

        model.removePluginSetting(row)
        runCurrent()
        assertFalse(pluginRow(model).setting.secret.configured)
        gate.complete(Unit)
        runCurrent()
        assertTrue(pluginRow(model).setting.secret.configured)
        assertEquals("nope", model.errorText)
    }

    // MARK: Event relay

    @Test
    fun relayStatusComesOnlyFromTheServerAndAFailureKeepsTheAddress() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.EventRelay)
        runCurrent()
        val gate = transport.hold("PUT /v1/event-relay")
        transport.answer("PUT /v1/event-relay", 502, """{"error":"Relay unreachable"}""")

        model.relayDraft = " https://relay.example "
        model.saveRelay()
        runCurrent()
        assertTrue(model.relaySaving)
        assertEquals("disconnected", model.eventRelay.value?.status)
        assertEquals("""{"url":"https://relay.example"}""", bodyText(transport.sent("PUT /v1/event-relay").single()))

        gate.complete(Unit)
        runCurrent()
        assertFalse(model.relaySaving)
        assertEquals("Relay unreachable", model.eventRelay.failure)
        assertEquals(" https://relay.example ", model.relayDraft)

        transport.held.clear()
        transport.answer("PUT /v1/event-relay", body = relayJson("https://relay.example", "connecting"))
        model.saveRelay()
        runCurrent()
        assertEquals("connecting", model.eventRelay.value?.status)
        assertEquals("https://relay.example", model.relayDraft)
        assertEquals("", model.eventRelay.failure)
    }

    @Test
    fun aRefreshKeepsATypedRelayAddress() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.EventRelay)
        runCurrent()
        model.relayDraft = "https://typed.example"
        transport.answer("GET /v1/event-relay", body = relayJson("https://other.example", "connected"))
        model.loadRelay()
        runCurrent()
        assertEquals("connected", model.eventRelay.value?.status)
        assertEquals("https://typed.example", model.relayDraft)
    }

    // MARK: Gateways

    private fun TestScope.gatewayModel(transport: ConnectionTransport): AppModel {
        val model = modelWith(transport, this)
        model.navigate(Route.MessageGateway("discord"))
        runCurrent()
        return model
    }

    @Test
    fun invalidGatewayEditsSendNothing() = runTest {
        val transport = scripted()
        val model = gatewayModel(transport)
        model.gatewayForm = model.gatewayForm?.copy(owner = "two words")
        model.saveGateway()
        runCurrent()
        assertTrue(model.gatewayForm?.attempted == true)
        assertTrue(transport.sent("PUT /v1/message-gateways/discord").isEmpty())
    }

    @Test
    fun failedGatewaySaveKeepsEditsAndRestoresTheRecord() = runTest {
        val transport = scripted()
        val model = gatewayModel(transport)
        val gate = transport.hold("PUT /v1/message-gateways/discord")
        transport.answer("PUT /v1/message-gateways/discord", 500, """{"error":"Could not save gateway"}""")

        model.gatewayForm = model.gatewayForm?.copy(enabled = false, owner = "123")
        model.saveGateway()
        runCurrent()
        assertFalse(model.gateways.value!!.single().config.enabled)
        assertTrue(model.gatewayForm?.saving == true)

        gate.complete(Unit)
        runCurrent()
        assertTrue(model.gateways.value!!.single().config.enabled)
        assertEquals("123", model.gatewayForm?.owner)
        assertFalse(model.gatewayForm?.enabled == true)
        assertEquals("Could not save gateway", model.gatewayForm?.error)
        assertFalse(model.gatewayForm?.saving == true)
    }

    @Test
    fun aLateGatewayResponseKeepsNewerEdits() = runTest {
        val transport = scripted()
        val model = gatewayModel(transport)
        val gate = transport.hold("PUT /v1/message-gateways/discord")
        transport.answer("PUT /v1/message-gateways/discord", body = gatewayJson(owner = "123", status = "connecting"))

        model.gatewayForm = model.gatewayForm?.copy(owner = "123")
        model.saveGateway()
        runCurrent()
        model.gatewayForm = model.gatewayForm?.copy(owner = "456")
        transport.answer("GET /v1/message-gateways", body = """{"gateways":[${gatewayJson(owner = "123", status = "connecting")}]}""")

        gate.complete(Unit)
        runCurrent()
        assertEquals("456", model.gatewayForm?.owner)
        assertFalse(model.gatewayForm?.saving == true)
        assertFalse(model.gatewayForm?.saved == true)
        assertEquals("123", model.gateways.value!!.single().config.ownerId)
    }

    @Test
    fun aConfirmedGatewaySaveReportsSavedAndTheServerStatus() = runTest {
        val transport = scripted()
        val model = gatewayModel(transport)
        transport.answer("PUT /v1/message-gateways/discord", body = gatewayJson(owner = "123", status = "connecting"))
        transport.answer("GET /v1/message-gateways", body = """{"gateways":[${gatewayJson(owner = "123", status = "connecting")}]}""")

        model.gatewayForm = model.gatewayForm?.copy(owner = "123", channels = listOf(GatewayChannelRow("c1")))
        model.saveGateway()
        runCurrent()
        val body = bodyOf(transport.sent("PUT /v1/message-gateways/discord").single())
        assertEquals("""["c1"]""", body["allowedChannelIds"].toString())
        assertEquals("connecting", model.gateways.value!!.single().status)
        assertTrue(model.gatewayForm?.saved == true)
    }

    @Test
    fun aRefreshDoesNotReplaceAGatewayDraft() = runTest {
        val transport = scripted()
        val model = gatewayModel(transport)
        model.gatewayForm = model.gatewayForm?.copy(owner = "typed")
        transport.answer("GET /v1/message-gateways", body = """{"gateways":[${gatewayJson(status = "error")}]}""")
        model.loadGateways()
        runCurrent()
        assertEquals("typed", model.gatewayForm?.owner)
        assertEquals("error", model.gateways.value!!.single().status)
    }

    // MARK: GitHub

    @Test
    fun creatingAnAppPostsTheManifestAndCountsOnlyOnceConfirmed() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        transport.answer("POST /v1/github-apps/manifest", body = """{"url":"https://github.com/settings/apps/new?state=s","manifest":"{}"}""")
        val gate = transport.hold("POST /v1/github-apps/complete")
        transport.answer("POST /v1/github-apps/complete", body = appJson("app2"))

        model.openCreateApp()
        model.createAppForm = CreateGitHubAppForm(name = " frien-bot ", organization = " ")
        model.createGitHubApp()
        runCurrent()
        val body = bodyText(transport.sent("POST /v1/github-apps/manifest").single())
        assertEquals("""{"name":"frien-bot","origin":"https://crumble.example:8443"}""", body)
        assertNull(model.createAppForm)
        assertEquals("{}", model.githubFlow?.manifest)

        model.finishGitHubFlow("https://crumble.example:8443/github-app/callback?code=c&state=s")
        runCurrent()
        assertTrue(model.githubFlow?.finishing == true)
        assertEquals(listOf("app1"), model.githubApps.value?.map { it.id })
        model.finishGitHubFlow("https://crumble.example:8443/github-app/callback?code=c&state=s")

        gate.complete(Unit)
        runCurrent()
        assertNull(model.githubFlow)
        assertEquals(1, transport.sent("POST /v1/github-apps/complete").size)
        assertEquals("""{"code":"c","state":"s"}""", bodyText(transport.sent("POST /v1/github-apps/complete").single()))
    }

    @Test
    fun aRefusedSignInSaysSoAndSendsNothing() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        transport.answer("POST /v1/github-apps/app1/sign-in", body = """{"url":"https://github.com/login/oauth/authorize?x=1"}""")
        val app = model.githubApps.value!!.single()
        model.openGitHubApp(app)
        model.signInToGitHubApp(app)
        runCurrent()
        assertEquals("""{"origin":"https://crumble.example:8443"}""", bodyText(transport.sent("POST /v1/github-apps/app1/sign-in").single()))
        assertEquals(GitHubStep.authorized, model.githubFlow?.step)

        model.finishGitHubFlow("https://crumble.example:8443/github-app/authorized?error=access_denied")
        runCurrent()
        assertEquals("Sign-in was refused: access_denied", model.githubFlow?.error)
        assertTrue(transport.sent("POST /v1/github-apps/sign-in/complete").isEmpty())
    }

    @Test
    fun aFinishedInstallConnectsTheApp() = runTest {
        val transport = scripted()
        transport.answer("GET /v1/github-apps", body = """{"apps":[${appJson(installed = false)}]}""")
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        transport.answer("POST /v1/github-apps/app1/connect", body = appJson())
        transport.answer("GET /v1/github-apps", body = """{"apps":[${appJson()}]}""")

        model.installGitHubApp(model.githubApps.value!!.single())
        assertEquals(GitHubStep.installed, model.githubFlow?.step)
        model.finishGitHubFlow("https://crumble.example:8443/github-app/installed?installation_id=1")
        runCurrent()
        assertNull(model.githubFlow)
        assertTrue(model.githubApps.value!!.single().installed)
    }

    @Test
    fun appBotsApplyAtOnceAndReopenOnFailure() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        val gate = transport.hold("PUT /v1/github-apps/app1/bots")
        transport.answer("PUT /v1/github-apps/app1/bots", 500, """{"error":"nope"}""")

        model.openGitHubApp(model.githubApps.value!!.single())
        model.githubAppForm = model.githubAppForm?.copy(botIDs = listOf(BOT_A))
        model.saveGitHubAppBots()
        runCurrent()
        assertNull(model.githubAppForm)
        assertEquals(listOf(BOT_A), model.githubApps.value!!.single().botIds)

        gate.complete(Unit)
        runCurrent()
        assertEquals(emptyList<String>(), model.githubApps.value!!.single().botIds)
        assertEquals(listOf(BOT_A), model.githubAppForm?.botIDs)
        assertEquals("nope", model.githubAppForm?.error)
    }

    @Test
    fun removedAppsReturnToTheirPlaceOnFailure() = runTest {
        val transport = scripted()
        transport.answer("GET /v1/github-apps", body = """{"apps":[${appJson("a")},${appJson("b")},${appJson("c")}]}""")
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        val gate = transport.hold("DELETE /v1/github-apps/b")
        transport.answer("DELETE /v1/github-apps/b", 500, """{"error":"nope"}""")

        model.deleteGitHubApp(model.githubApps.value!![1])
        runCurrent()
        assertEquals(listOf("a", "c"), model.githubApps.value?.map { it.id })
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b", "c"), model.githubApps.value?.map { it.id })
    }

    @Test
    fun aMachineAccountAppearsOnlyWhenGitHubAcceptsItsToken() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        val gate = transport.hold("POST /v1/github-accounts")
        transport.answer("POST /v1/github-accounts", 400, """{"error":"Bad credentials"}""")

        model.openAddAccount()
        model.addAccountForm = model.addAccountForm?.copy(token = SecretDraft(text = SECRET))
        model.addGitHubAccount()
        runCurrent()
        assertTrue(model.addAccountForm?.saving == true)
        assertEquals(1, model.githubAccounts.value?.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals("Bad credentials", model.addAccountForm?.error)
        assertEquals(SECRET, model.addAccountForm?.token?.text)
        assertEquals(1, model.githubAccounts.value?.size)
    }

    @Test
    fun aSavedTokenIsNotSentAgainWhenTheBotsFailed() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        transport.answer("PUT /v1/github-accounts/acc1/token", body = accountJson())
        transport.answer("PUT /v1/github-accounts/acc1/bots", 500, """{"error":"nope"}""")

        model.openGitHubAccount(model.githubAccounts.value!!.single())
        model.githubAccountForm = model.githubAccountForm?.copy(botIDs = listOf(BOT_A), token = SecretDraft(text = SECRET))
        model.saveGitHubAccount()
        runCurrent()
        assertEquals("nope", model.githubAccountForm?.error)
        assertFalse(model.githubAccountForm?.token?.changed == true)
        assertEquals(listOf(BOT_A), model.githubAccountForm?.botIDs)

        transport.answer("PUT /v1/github-accounts/acc1/bots", body = accountJson(bots = "\"$BOT_A\""))
        transport.answer("GET /v1/github-accounts", body = """{"accounts":[${accountJson(bots = "\"$BOT_A\"")}]}""")
        model.saveGitHubAccount()
        runCurrent()
        assertNull(model.githubAccountForm)
        assertEquals(1, transport.sent("PUT /v1/github-accounts/acc1/token").size)
        assertEquals(listOf(BOT_A), model.githubAccounts.value!!.single().botIds)
    }

    // MARK: Workspaces

    @Test
    fun nonAdminsSeeNoServerSettings() = runTest {
        val transport = scripted()
        transport.answer("GET /v1/admin/settings", 403, """{"error":"Only admins can do this"}""")
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        assertEquals(false, model.isAdmin)
        assertEquals("", model.serverSettings.error)
        assertEquals("", model.errorText)
    }

    @Test
    fun serverSettingsApplyAtOnceAndReopenOnFailure() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        assertEquals(true, model.isAdmin)
        val gate = transport.hold("PATCH /v1/admin/settings")
        transport.answer("PATCH /v1/admin/settings", 400, """{"error":"Too small"}""")

        model.openServerSettings(WorkspaceField.size)
        model.serverSettingsForm = model.serverSettingsForm?.copy(cpus = "8", memory = "1.5")
        model.saveServerSettings()
        runCurrent()
        assertEquals("""{"workspaceCpus":8,"workspaceMemoryMib":1536}""", bodyText(transport.sent("PATCH /v1/admin/settings").single()))
        assertEquals(8L, model.serverSettings.value?.workspaceCpus)
        assertEquals("img", model.serverSettings.value?.codingImage)

        gate.complete(Unit)
        runCurrent()
        assertEquals(2L, model.serverSettings.value?.workspaceCpus)
        assertEquals("Too small", model.serverSettingsForm?.error)
        assertEquals("8", model.serverSettingsForm?.cpus)
    }

    @Test
    fun clearingTheImageSendsNull() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        transport.answer("PATCH /v1/admin/settings", body = settingsJson(image = null))
        model.openServerSettings(WorkspaceField.image)
        model.serverSettingsForm = model.serverSettingsForm?.copy(image = " ")
        model.saveServerSettings()
        assertNull(model.serverSettings.value?.codingImage)
        runCurrent()
        assertEquals("""{"codingImage":null}""", bodyText(transport.sent("PATCH /v1/admin/settings").single()))
    }

    // MARK: MCP servers

    private fun mcpJson(id: String = "m1", client: String? = null) =
        """{"id":"$id","name":"Linear","url":"https://mcp.linear.app","enabled":true,"agentId":null,"oauthClientId":${client?.let { "\"$it\"" }},"token":$NONE,"oauthClientSecret":${if (client != null) SAVED else NONE},"auth":"none","error":null,"authenticated":false}"""

    private fun plainMcpJson(id: String = "m1") =
        """{"id":"$id","name":"Linear","url":"https://mcp.linear.app","enabled":true,"agentId":null,"oauthClientId":null,"token":$NONE,"oauthClientSecret":$NONE}"""

    private fun TestScope.mcpModel(transport: ConnectionTransport, client: String? = null): AppModel {
        transport.answer("GET /v1/mcp-servers", body = "[${mcpJson(client = client)}]")
        val model = modelWith(transport, this)
        model.navigate(Route.Integrations)
        runCurrent()
        return model
    }

    @Test
    fun aMcpReferenceWaitsForThePasswordManager() = runTest {
        val transport = scripted()
        val model = mcpModel(transport)
        val gate = transport.hold("POST /v1/mcp-servers")
        transport.answer("POST /v1/mcp-servers", body = plainMcpJson("m2"))

        model.mcpDraft = MCPDraft(
            serverID = "", name = "Notion", url = "https://mcp.notion.com",
            token = SecretDraft(manager = "1password", text = "op://v/i/f"),
        )
        model.saveMCP()
        runCurrent()
        assertTrue(model.mcpDraft?.saving == true)
        assertEquals("none", model.mcpServers.last().auth)
        assertEquals(
            """{"passwordManager":"1password","reference":"op://v/i/f"}""",
            bodyOf(transport.sent("POST /v1/mcp-servers").single())["token"]!!.jsonObject["reference"].toString(),
        )

        gate.complete(Unit)
        runCurrent()
        assertNull(model.mcpDraft)
        assertEquals("m2", model.mcpServers.last().id)
    }

    @Test
    fun aFailedMcpReferenceKeepsTheSheet() = runTest {
        val transport = scripted()
        val model = mcpModel(transport)
        transport.answer("POST /v1/mcp-servers", 400, """{"error":"Can't read it"}""")

        model.mcpDraft = MCPDraft(serverID = "", name = "Notion", url = "https://mcp.notion.com", token = SecretDraft(manager = "1password", text = "op://x"))
        model.saveMCP()
        runCurrent()
        assertEquals("Notion", model.mcpDraft?.name)
        assertFalse(model.mcpDraft?.saving == true)
        assertEquals("Can't read it", model.errorText)
        assertEquals(listOf("m1"), model.mcpServers.map { it.id })
    }

    @Test
    fun clearingTheClientIdRemovesItsSecret() = runTest {
        val transport = scripted()
        val model = mcpModel(transport, client = "client-1")
        transport.answer("PATCH /v1/mcp-servers/m1", body = plainMcpJson())

        model.openMCPForm("m1")
        assertEquals("client-1", model.mcpDraft?.clientID)
        model.mcpDraft = model.mcpDraft?.copy(clientID = " ")
        model.saveMCP()
        runCurrent()
        val body = bodyOf(transport.sent("PATCH /v1/mcp-servers/m1").single())
        assertEquals("null", body["oauthClientId"].toString())
        assertEquals("null", body["oauthClientSecret"].toString())
    }

    @Test
    fun aNewClientSecretGoesWithItsClientId() = runTest {
        val transport = scripted()
        val model = mcpModel(transport)
        transport.answer("PATCH /v1/mcp-servers/m1", body = plainMcpJson())

        model.openMCPForm("m1")
        model.mcpDraft = model.mcpDraft?.copy(clientID = "client-1", clientSecret = SecretDraft(text = SECRET))
        model.saveMCP()
        assertNull(model.mcpDraft)
        runCurrent()
        val body = bodyOf(transport.sent("PATCH /v1/mcp-servers/m1").single())
        assertEquals("\"client-1\"", body["oauthClientId"].toString())
        assertEquals("""{"value":"$SECRET"}""", body["oauthClientSecret"].toString())
    }

    @Test
    fun typedConnectionSecretsAreRedactedAndLeaveWithTheScreen() = runTest {
        val transport = scripted()
        val model = modelWith(transport, this)
        model.navigate(Route.Coding)
        runCurrent()
        model.openAddAccount()
        model.addAccountForm = model.addAccountForm?.copy(token = SecretDraft(text = SECRET))
        assertFalse(model.addAccountForm.toString().contains(SECRET))
        assertFalse(MCPDraft(serverID = "", token = SecretDraft(text = SECRET)).toString().contains(SECRET))
        assertFalse(SecretInput.Value(SECRET).toString().contains(SECRET))
        model.navigate(Route.Memory)
        assertNull(model.addAccountForm)
    }
}
