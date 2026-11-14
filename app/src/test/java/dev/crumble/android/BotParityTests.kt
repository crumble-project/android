package dev.crumble.android

import dev.crumble.android.generated.BotRequest
import dev.crumble.android.generated.Capabilities
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AgentDraft
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChartInk
import dev.crumble.android.model.ChartShape
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.DisplayCard
import dev.crumble.android.model.DisplayCell
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageOrigin
import dev.crumble.android.model.ModelChoice
import dev.crumble.android.model.ProviderStatus
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.WEB_SEARCH_OPTIONS
import dev.crumble.android.model.automationsOf
import dev.crumble.android.model.botListLine
import dev.crumble.android.model.botReactions
import dev.crumble.android.model.chartInk
import dev.crumble.android.model.chartLayoutFor
import dev.crumble.android.model.chartLayouts
import dev.crumble.android.model.desktopModelHint
import dev.crumble.android.model.desktopProviderOptions
import dev.crumble.android.model.displayOf
import dev.crumble.android.model.fitColumns
import dev.crumble.android.model.groupReactions
import dev.crumble.android.model.isDesktopScreenshot
import dev.crumble.android.model.loadAttachment
import dev.crumble.android.model.nearestChartPoint
import dev.crumble.android.model.numericColumns
import dev.crumble.android.model.shownCapabilities
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.edited
import dev.crumble.android.network.wire
import dev.crumble.android.ui.BotState
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER = "https://crumble.example"

private fun json(text: String) = CrumbleJson.parseToJsonElement(text)

private fun chatJson(status: String) = snapshotFixture(ChatSnapshot(agentId = "a", status = status))

private class Replies(
    private val handler: (HttpRequestData) -> Pair<Int, String>,
) : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        held.entries.firstOrNull { request.pathWithQuery.startsWith(it.key) }?.value?.await()
        val (status, body) = handler(request)
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun patch(): JsonObject =
        json(String(requests.single { it.method == "PATCH" }.body!!, StandardCharsets.UTF_8)).jsonObject

    fun post(): JsonObject =
        json(String(requests.single { it.method == "POST" }.body!!, StandardCharsets.UTF_8)).jsonObject
}

private fun show(details: String, isError: Boolean? = null) =
    Message(id = "m", role = "toolResult", toolName = "show", isError = isError, toolDetails = json(details))

@OptIn(ExperimentalCoroutinesApi::class)
class BotParityTests {
    // MARK: Attachments

    @Test
    fun everyFileTheWebsitePickerOffersCanBeAttached() {
        val extensions = listOf("json", "xml", "md", "csv", "ts", "tsx", "js", "py", "swift", "go", "rs", "java", "kt", "rb", "sh", "yaml", "yml", "toml")
        for (extension in extensions) {
            for (hint in listOf(null, "application/octet-stream")) {
                val attachment = loadAttachment("file.$extension", hint, "hello".toByteArray())
                assertEquals("file.$extension", attachment.name)
            }
        }
    }

    @Test
    fun aRubyFileReportedUnderItsApplicationTypeCanBeAttached() {
        assertEquals("text/plain", loadAttachment("build.rb", "application/x-ruby", "puts 1".toByteArray()).mimeType)
    }

    @Test
    fun aTypeScriptFileReportedAsVideoCanBeAttached() {
        assertEquals("text/plain", loadAttachment("app.ts", "video/mp2t", "let a = 1".toByteArray()).mimeType)
    }

    // MARK: Bot editor

    private val bot = Agent(
        id = "a1",
        name = "Kev",
        provider = "openai",
        model = "gpt",
        tools = listOf("memory", "web", "desktop"),
        webSearchProvider = "exa",
        desktopModel = ModelChoice("openai", "gpt"),
    )

    @Test
    fun codingAndDesktopShowOnlyWhenTheServerOffersThemOrTheBotHasThem() {
        fun tools(server: Capabilities?, initial: Set<String> = emptySet()) =
            shownCapabilities(server, initial).map { it.tool }

        val always = listOf("memory", "web", "delegate", "create_bots")
        assertEquals(always, tools(null))
        assertEquals(always, tools(Capabilities(coding = false, desktop = false, jobKinds = emptyList())))
        assertEquals(
            listOf("memory", "web", "delegate", "coding", "desktop", "create_bots"),
            tools(Capabilities(coding = true, desktop = true, jobKinds = emptyList())),
        )
        assertEquals(listOf("memory", "web", "delegate", "desktop", "create_bots"), tools(null, setOf("desktop")))
    }

    @Test
    fun theDesktopProviderListKeepsAChoiceThatLostItsKey() {
        val providers = listOf(ProviderStatus("openai", "OpenAI", true), ProviderStatus("melious", "Melious", false))

        assertEquals(listOf("", "openai"), desktopProviderOptions(providers, "").map { it.value })
        assertEquals("Same as the bot", desktopProviderOptions(providers, "")[0].label)
        assertEquals(
            listOf("Same as the bot", "Melious (not set up)", "OpenAI"),
            desktopProviderOptions(providers, "melious").map { it.label },
        )
        assertEquals("Add an API key or sign in for this provider in the web app to load its models.", desktopModelHint(providers, "melious", 0))
        assertEquals("3 models available.", desktopModelHint(providers, "openai", 3))
        assertEquals("Type a model ID.", desktopModelHint(providers, "openai", 0))
    }

    @Test
    fun webSearchChoicesMatchTheWebsite() {
        assertEquals(listOf("Account default", "Mixed", "Exa"), WEB_SEARCH_OPTIONS.map { it.label })
    }

    @Test
    fun aNewBotStartsWithTheWebsitesTools() {
        val model = AppModel(client = null, scope = kotlinx.coroutines.test.TestScope())
        model.createAgent()
        val draft = model.agentDraft!!
        assertTrue(draft.toolsMemory)
        assertTrue(draft.toolsDelegate)
        assertEquals(setOf("web"), draft.otherTools)
    }

    @Test
    fun savingSendsTheWebSearchProviderAndDesktopModel() = runTest {
        val transport = Replies { 200 to agentFixture("a1") }
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(bot)
        model.openEditor("a1")
        val draft = model.agentDraft!!
        assertEquals("exa", draft.webSearchProvider)
        assertEquals("openai", draft.desktopProvider)
        assertEquals("gpt", draft.desktopModel)
        model.agentDraft = draft.copy(webSearchProvider = "mixed", desktopModel = " sonnet ")
        model.saveAgentEditor()
        assertEquals("mixed", model.agents.single().webSearchProvider)
        assertEquals(ModelChoice("openai", "sonnet"), model.agents.single().desktopModel)
        runCurrent()

        val patch = transport.patch()
        assertEquals("mixed", patch.getValue("webSearchProvider").jsonPrimitive.content)
        assertEquals("sonnet", patch.getValue("desktopModel").jsonObject.getValue("model").jsonPrimitive.content)
        assertFalse(patch.containsKey("skills"))
        assertFalse(patch.containsKey("sharedSkills"))
    }

    @Test
    fun clearingSendsNullSoTheServerDropsTheOldChoices() = runTest {
        val transport = Replies { 200 to agentFixture("a1") }
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(bot)
        model.openEditor("a1")
        model.agentDraft = model.agentDraft!!.copy(webSearchProvider = null, desktopProvider = "", desktopModel = "")
        model.saveAgentEditor()
        runCurrent()

        val patch = transport.patch()
        assertEquals(JsonNull, patch["webSearchProvider"])
        assertEquals(JsonNull, patch["desktopModel"])
    }

    @Test
    fun aProviderWithoutAModelDoesNotPickADesktopModel() {
        val model = AppModel(client = null, scope = kotlinx.coroutines.test.TestScope())
        model.createAgent()
        assertNull(model.agentDraft!!.copy(desktopProvider = "openai", desktopModel = "  ").chosenDesktopModel)
    }

    @Test
    fun aFailedSaveRestoresTheOldChoicesAndKeepsTheDraft() = runTest {
        val transport = Replies { 500 to """{"error":"no"}""" }
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(bot)
        model.openEditor("a1")
        model.agentDraft = model.agentDraft!!.copy(webSearchProvider = "mixed")
        model.saveAgentEditor()
        assertNull(model.agentDraft)
        assertEquals("mixed", model.agents.single().webSearchProvider)
        runCurrent()

        assertEquals("exa", model.agents.single().webSearchProvider)
        assertEquals(ModelChoice("openai", "gpt"), model.agents.single().desktopModel)
        assertEquals("mixed", model.agentDraft!!.webSearchProvider)
    }

    @Test
    fun reasoningPreviewIgnoresAnOlderDraftAndUsesServerOptions() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = Replies { request ->
            if (request.pathWithQuery == "/v1/reasoning/preview") {
                200 to """{"options":[{"value":"off","label":"Disabled"},{"value":"high","label":"Deep"}],"effectiveLevel":"high"}"""
            } else 200 to "[]"
        }
        transport.held["/v1/reasoning/preview"] = gate
        val api = APIClient(SERVER, "session", transport)
        val model = AppModel(client = api, scope = this)
        val original = AgentDraft(isNew = true, agentID = "", provider = "openai", model = "first", thinking = "low")
        model.agentDraft = original
        val old = launch { model.refreshEditorReasoning(api, original) }
        runCurrent()
        model.agentDraft = original.copy(model = "second", thinking = "high")
        gate.complete(Unit)
        old.join()
        assertNull(model.editorReasoning)
        transport.held.clear()
        model.refreshEditorReasoning(api, model.agentDraft!!)
        assertEquals("high", model.editorLevel)
        assertEquals(listOf("Disabled", "Deep"), model.editorReasoning!!.options.map { it.label })
        assertEquals("high", model.agentDraft!!.thinking)
    }

    @Test
    fun reasoningPreviewFailurePreservesDraftForRetry() = runTest {
        var failing = true
        val transport = Replies { if (failing) 503 to "" else 200 to """{"options":[{"value":"off","label":"Off"}],"effectiveLevel":"off"}""" }
        val api = APIClient(SERVER, "session", transport)
        val model = AppModel(client = api, scope = this)
        val draft = AgentDraft(isNew = true, agentID = "", provider = "openai", model = "first", thinking = "high")
        model.agentDraft = draft
        assertTrue(runCatching { model.refreshEditorReasoning(api, draft) }.isFailure)
        assertEquals("high", model.agentDraft!!.thinking)
        assertNull(model.editorReasoning)
        failing = false
        model.refreshEditorReasoning(api, draft)
        assertEquals("off", model.editorReasoning!!.effectiveLevel)
        assertEquals("high", model.agentDraft!!.thinking)
    }

    @Test
    fun aLateModelListDoesNotReplaceTheNewerProviders() = runTest {
        val transport = Replies { request ->
            200 to if ("/models?provider=google" in request.pathWithQuery) {
                """[{"provider":"google","id":"g1","name":"G","contextWindow":1,"reasoning":false,"thinkingLevels":[]}]"""
            } else {
                "[]"
            }
        }
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        val gate = CompletableDeferred<Unit>()
        transport.held["/v1/models?provider=openai"] = gate
        model.createAgent()
        runCurrent()
        model.changeEditorDesktopProvider("openai")
        runCurrent()
        model.changeEditorDesktopProvider("google")
        runCurrent()
        assertEquals(listOf("g1"), model.desktopModels.map { it.id })

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("g1"), model.desktopModels.map { it.id })
    }

    @Test
    fun aProposalsChoicesCanBeChangedOrCleared() {
        val proposal = CrumbleJson.decodeFromString<BotRequest>(botRequestFixture()).proposal

        val changed = proposal.edited(Agent(name = "Scout", webSearchProvider = "mixed", desktopModel = ModelChoice("google", "g1")))
        assertEquals("mixed", changed.getValue("webSearchProvider").jsonPrimitive.content)
        assertEquals("g1", changed.getValue("desktopModel").jsonObject.getValue("model").jsonPrimitive.content)

        val cleared = proposal.edited(Agent(name = "Scout"))
        assertEquals(JsonNull, cleared["webSearchProvider"])
        assertEquals(JsonNull, cleared["desktopModel"])
    }

    @Test
    fun aNewBotSendsItsChoicesAndLeavesOutUnsetOnes() = runTest {
        val transport = Replies { 200 to agentFixture("created") }
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = backgroundScope)
        model.createAgent()
        model.agentDraft = model.agentDraft!!.copy(name = "New", provider = "openai", model = "gpt", webSearchProvider = "exa", desktopProvider = "openai", desktopModel = "gpt")
        model.saveAgentEditor()
        runCurrent()

        val body = transport.post()
        assertEquals("exa", body.getValue("webSearchProvider").jsonPrimitive.content)
        assertEquals("gpt", body.getValue("desktopModel").jsonObject.getValue("model").jsonPrimitive.content)
        assertTrue(body.getValue("tools").toString().contains("web"))

        assertFalse(CrumbleJson.encodeToJsonElement(Agent(name = "New").wire()).jsonObject.containsKey("desktopModel"))
    }

    // MARK: Display cards

    @Test
    fun statsParseAndStayHiddenWhenTheyFailTheSchema() {
        val card = displayOf(show("""{"kind":"stats","title":"Sales","items":[{"label":"Total","value":"12","change":"+3","trend":"up","caption":"week"}]}"""))
        val stats = card as DisplayCard.Stats
        assertEquals("Sales", stats.title)
        assertEquals("up", stats.items.single().trend)

        assertNull(displayOf(show("""{"kind":"stats","items":[{"label":"Total"}]}""")))
        assertNull(displayOf(show("""{"kind":"stats","items":[{"label":"T","value":"1","trend":"sideways"}]}""")))
        assertNull(displayOf(show("""{"kind":"other"}""")))
        assertNull(displayOf(show("""{"kind":"stats","items":[]}""", isError = true)))
        assertNull(displayOf(show("""{"kind":"stats","items":[]}""").copy(toolName = "other")))
    }

    @Test
    fun tableColumnsAreNumericOnlyWhenEveryCellIs() {
        val table = displayOf(show("""{"kind":"table","columns":["Name","Count","Mix"],"rows":[["a",1,"x"],["b",2.5,3]]}""")) as DisplayCard.Table
        assertEquals(DisplayCell("a"), table.rows[0][0])
        assertEquals(2.5, table.rows[1][1].number!!, 0.0)
        assertEquals(listOf(false, true, false), numericColumns(3, table.rows))
        assertNull(displayOf(show("""{"kind":"table","columns":["A"],"rows":[[true]]}""")))
        assertEquals(listOf(50f, 50f), fitColumns(listOf(40f, 40f), 100f))
        assertEquals(listOf(70f, 70f), fitColumns(listOf(70f, 70f), 100f))
    }

    private val chart = """{"kind":"chart","title":"Trend","layouts":[
        {"width":600,"height":300,"nodes":[],"points":[]},
        {"width":320,"height":200,"nodes":[
            {"kind":"line","x1":0,"y1":1,"x2":2,"y2":3,"stroke":"series-1","dash":[4,2]},
            {"kind":"path","commands":[["M",0,0],["L",1,1],["Q",1,2,3,4],["C",1,2,3,4,5,6],["Z"],["L",1]],"evenOdd":true},
            {"kind":"circle","x":5,"y":6,"radius":2,"fill":"#2a78d6"},
            {"kind":"rect","x":1,"y":2,"width":3,"height":4,"radius":1,"fill":"none","clip":[0,0,10,10]},
            {"kind":"text","x":1,"y":2,"text":"Jan","anchor":"middle","baseline":"hanging","rotate":0,"size":12,"weight":500},
            {"kind":"unknown"}
        ],"points":[
            {"x":10,"y":10,"text":"Note","rows":[]},
            {"x":20,"y":10,"title":"Feb","rows":[{"label":"Sales","value":"12"}]}
        ]}
    ]}"""

    @Test
    fun chartsParseEveryPrimitiveAndKeepTheChartWhenOneIsBad() {
        val card = displayOf(show(chart)) as DisplayCard.Chart
        val layouts = chartLayouts(card.layouts)
        assertEquals(listOf(320f, 600f), layouts.map { it.width })

        val nodes = layouts[0].nodes
        assertEquals(5, nodes.size)
        assertEquals(ChartInk.None, nodes[0].fill)
        assertEquals(ChartInk.Foreground, nodes[1].fill)
        assertEquals(5, (nodes[1].shape as ChartShape.Curve).commands.size)
        assertTrue((nodes[1].shape as ChartShape.Curve).evenOdd)
        assertEquals(ChartInk.Literal(0xFF2A78D6), nodes[2].fill)
        assertEquals(ChartInk.None, nodes[3].fill)
        assertEquals("Jan", (nodes[4].shape as ChartShape.Label).text)
        assertEquals(10f, nodes[3].clip!!.width)

        assertEquals("Feb", layouts[0].points[1].title)
        assertEquals("Note", layouts[0].points[0].text)
        assertNull(displayOf(show("""{"kind":"chart","layouts":[{"nodes":[]}]}""")))
    }

    @Test
    fun chartLayoutsMatchTheAvailableWidthAndColorsMatchTheWebsite() {
        val layouts = chartLayouts((displayOf(show(chart)) as DisplayCard.Chart).layouts)
        assertEquals(320f, chartLayoutFor(layouts, 100f)!!.width)
        assertEquals(320f, chartLayoutFor(layouts, 599f)!!.width)
        assertEquals(600f, chartLayoutFor(layouts, 600f)!!.width)
        assertNull(chartLayoutFor(emptyList(), 600f))

        assertEquals(ChartInk.Literal(0xFF737373), chartInk("series-1", ChartInk.None))
        assertEquals(ChartInk.Literal(0xFFD4D4D4), chartInk("series-5", ChartInk.None))
        assertEquals(ChartInk.Literal(0xFF737373), chartInk("series-6", ChartInk.None))
        assertEquals(ChartInk.Literal(0xFFFFAA00), chartInk("#fa0", ChartInk.None))
        assertEquals(ChartInk.Literal(0x80112233), chartInk("#11223380", ChartInk.None))
        assertEquals(ChartInk.Background, chartInk("background", ChartInk.None))
        assertEquals(ChartInk.Foreground, chartInk(null, ChartInk.Foreground))
    }

    @Test
    fun aTouchFindsTheNearestPointWithinReach() {
        val points = chartLayouts((displayOf(show(chart)) as DisplayCard.Chart).layouts)[0].points
        assertEquals("Feb", nearestChartPoint(points, 18f, 10f, 32f)?.title)
        assertEquals("Note", nearestChartPoint(points, 11f, 10f, 32f)?.text)
        assertNull(nearestChartPoint(points, 100f, 100f, 32f))
    }

    @Test
    fun cardsAndScreenshotsShowInTheChatButOtherToolsDoNot() {
        val screenshot = Message(id = "s", entryId = "e1", role = "toolResult", toolName = "desktop_screenshot")
        assertTrue(isDesktopScreenshot(screenshot))
        assertFalse(isDesktopScreenshot(screenshot.copy(isError = true)))
        assertFalse(isDesktopScreenshot(screenshot.copy(entryId = null)))

        val messages = listOf(
            show("""{"kind":"stats","items":[]}"""),
            screenshot,
            screenshot.copy(id = "failed", isError = true),
            Message(id = "o", role = "toolResult", toolName = "web_search"),
        )
        val rows = ChatRow.rows(messages, null, hasMore = false).filterIsInstance<ChatRow.MessageRow>()
        assertEquals(listOf("m", "s"), rows.map { it.message.id })
    }

    // MARK: Reactions

    private fun react(emoji: String, replyTo: String? = null, origin: String? = null) = Message(
        id = "r$emoji",
        role = "toolResult",
        toolName = "react",
        replyTo = replyTo,
        origin = origin?.let { MessageOrigin(kind = "bot", name = it) },
        toolDetails = json("""{"emoji":"$emoji"}"""),
    )

    @Test
    fun aBotsReactionLandsOnTheOwnersLastMessage() {
        val messages = listOf(
            Message(id = "1", entryId = "u1", role = "user"),
            react("A"),
            Message(id = "2", entryId = "a1", role = "assistant"),
            Message(id = "3", entryId = "u2", role = "user"),
            react("B"),
            react("C", origin = "Kev"),
            react("D").copy(isError = true),
        )
        assertEquals(mapOf("u1" to listOf("A"), "u2" to listOf("B", "Kev C")), botReactions(messages))
    }

    @Test
    fun aReactionBeforeAnyOwnerMessageIsDropped() {
        assertTrue(botReactions(listOf(react("A"))).isEmpty())
    }

    @Test
    fun groupReactionsStillUseTheRecordedTarget() {
        val messages = listOf(Message(id = "1", entryId = "u1", role = "user"), react("A", replyTo = "other", origin = "Kev"))
        assertEquals(mapOf("other" to listOf("Kev A")), groupReactions(messages))
    }

    // MARK: Live status

    private fun chats(vararg statuses: Pair<String, String>): Replies {
        val byBot = statuses.toMap().toMutableMap()
        return Replies { request ->
            val id = request.pathWithQuery.removePrefix("/v1/agents/").substringBefore('/')
            when (val status = byBot[id]) {
                null -> 404 to """{"error":"gone"}"""
                else -> 200 to chatJson(status)
            }
        }
    }

    private val a = Agent(id = "a", name = "A", enabled = true)
    private val b = Agent(id = "b", name = "B", enabled = true)
    private val off = Agent(id = "off", name = "Off", enabled = false)

    @Test
    fun everyEnabledBotReportsWhetherItIsWorking() = runTest {
        val transport = chats("a" to "idle", "b" to "running")
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(a, b, off, Agent(id = "pending:1", name = "New", enabled = true))
        model.refreshBotStatuses()

        assertEquals(setOf("b"), model.workingBots)
        assertEquals(BotState.working, model.botState(b))
        assertEquals(BotState.idle, model.botState(a))
        assertEquals(BotState.sleeping, model.botState(off))
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.all { it.pathWithQuery.endsWith("/chat?limit=1") })
    }

    @Test
    fun aFailedReadKeepsTheLastKnownStatusAndAFinishedTurnClearsIt() = runTest {
        var status = 500
        var body = """{"error":"down"}"""
        val transport = Replies { status to body }
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(a)
        model.workingBots = setOf("a")
        model.refreshBotStatuses()
        assertEquals(setOf("a"), model.workingBots)

        status = 200
        body = chatJson("idle")
        model.refreshBotStatuses()
        assertTrue(model.workingBots.isEmpty())
    }

    @Test
    fun aDeletedBotLeavesTheWorkingSet() = runTest {
        val transport = chats("a" to "idle")
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(a)
        model.workingBots = setOf("gone")
        model.refreshBotStatuses()
        assertTrue(model.workingBots.isEmpty())
    }

    @Test
    fun theOpenChatsStreamBeatsTheLastStatusCheck() = runTest {
        val model = AppModel(client = APIClient(SERVER, "session", chats("a" to "idle", "b" to "idle")), scope = backgroundScope)
        model.agents = listOf(a, b)
        model.selectAgent("a")
        model.workingBots = setOf("a")
        model.snapshot = ChatSnapshot(status = "idle")
        assertFalse(model.isWorking("a"))
        model.snapshot = ChatSnapshot(status = "running", working = "thinking")
        model.workingBots = emptySet()
        assertTrue(model.isWorking("a"))
    }

    @Test
    fun leavingARunningBotKeepsItWorkingInTheList() = runTest {
        val transport = chats("a" to "running", "b" to "idle")
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = backgroundScope)
        model.agents = listOf(a, b)
        model.selectAgent("a")
        model.snapshot = ChatSnapshot(status = "running", working = "thinking")
        model.selectAgent("b")
        runCurrent()

        assertTrue(model.isWorking("a"))

        model.selectAgent("a")
        model.snapshot = ChatSnapshot(status = "idle")
        model.selectAgent("b")
        runCurrent()
        assertFalse(model.isWorking("a"))
    }

    @Test
    fun aLateStatusFromAnOldSessionIsDropped() = runTest {
        val transport = chats("a" to "running")
        val gate = CompletableDeferred<Unit>()
        transport.held["/v1/agents/a/chat"] = gate
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this, transport = transport)
        model.agents = listOf(a)
        val read = launch { model.refreshBotStatuses() }
        runCurrent()
        model.signOut()
        runCurrent()
        gate.complete(Unit)
        read.join()

        assertTrue(model.workingBots.isEmpty())
    }

    @Test
    fun followingPollsUntilTheListCloses() = runTest {
        val transport = chats("a" to "idle")
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(a)
        val follow = launch { model.followBotStatuses() }
        runCurrent()
        assertEquals(1, transport.requests.size)
        advanceTimeBy(5_001)
        assertEquals(2, transport.requests.size)

        model.appActive = false
        advanceTimeBy(5_001)
        assertEquals(2, transport.requests.size)

        follow.cancel()
    }

    @Test
    fun theListLineSaysWhenABotIsWorking() {
        assertEquals("Working…", botListLine(a.copy(lastMessage = "hi"), true))
        assertEquals("hi", botListLine(a.copy(lastMessage = "hi"), false))
        assertEquals("No messages yet", botListLine(a, false))
    }

    // MARK: Details

    private fun schedule(id: String, agentId: String = "a", enabled: Boolean = true, cron: String? = "0 9 * * *", nextRunAt: String? = null) =
        Schedule(id = id, agentId = agentId, name = id, cron = cron, enabled = enabled, nextRunAt = nextRunAt, prompt = "p", timezone = "UTC")

    @Test
    fun detailsListThisBotsTasksThatCanStillRun() {
        val list = listOf(schedule("mine"), schedule("other", agentId = "b"), schedule("done", cron = null), schedule("next", cron = null, nextRunAt = "2030-01-01T00:00:00Z"))
        assertEquals(listOf("mine", "next"), automationsOf("a", list).map { it.id })
    }

    @Test
    fun openingDetailsLoadsTasksAndReportsAFailure() = runTest {
        var ok = false
        val transport = Replies { request ->
            if (request.pathWithQuery.startsWith("/v1/schedules") && !ok) 500 to """{"error":"Down"}""" else 200 to "[]"
        }
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.openBotDetails()
        assertTrue(model.showingBotDetails)
        assertFalse(model.schedulesLoaded)
        runCurrent()
        assertFalse(model.schedulesLoaded)
        assertTrue(model.schedulesError.isNotEmpty())

        ok = true
        model.openBotDetails()
        assertEquals("", model.schedulesError)
        runCurrent()
        assertTrue(model.schedulesLoaded)
    }

    @Test
    fun aFailedToggleInDetailsRollsBackAndADelayedOneWaits() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = Replies { 500 to """{"error":"no"}""" }
        transport.held["/v1/schedules/mine"] = gate
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.schedules = listOf(schedule("mine"))
        model.toggleSchedule("mine")
        assertFalse(model.schedules.single().enabled)
        assertTrue(model.schedulePending("mine"))
        runCurrent()
        assertFalse(model.schedules.single().enabled)

        gate.complete(Unit)
        runCurrent()
        assertTrue(model.schedules.single().enabled)
        assertFalse(model.schedulePending("mine"))
    }

    @Test
    fun choosingAnotherBotClosesDetails() = runTest {
        val model = AppModel(client = APIClient(SERVER, "session", chats("a" to "idle", "b" to "idle")), scope = backgroundScope)
        model.agents = listOf(a, b)
        model.selectAgent("a")
        model.showingBotDetails = true
        model.selectAgent("a")
        assertTrue(model.showingBotDetails)
        model.selectAgent("b")
        assertFalse(model.showingBotDetails)
    }
}
