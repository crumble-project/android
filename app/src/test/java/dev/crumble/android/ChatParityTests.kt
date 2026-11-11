package dev.crumble.android

import dev.crumble.android.model.ActivityRecord
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.BotRequestRef
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.Inference
import dev.crumble.android.model.JobItem
import dev.crumble.android.model.JobKind
import dev.crumble.android.model.JobRef
import dev.crumble.android.model.Message
import dev.crumble.android.model.ModelSummary
import dev.crumble.android.model.SourceChip
import dev.crumble.android.model.clampLevel
import dev.crumble.android.model.costLine
import dev.crumble.android.model.headline
import dev.crumble.android.model.jobMarkdown
import dev.crumble.android.model.jobOf
import dev.crumble.android.model.levelsOf
import dev.crumble.android.model.mergeJobPages
import dev.crumble.android.model.siteOf
import dev.crumble.android.model.sourceKey
import dev.crumble.android.model.sourcesOf
import dev.crumble.android.model.splitSources
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun json(text: String) = CrumbleJson.parseToJsonElement(text)

private fun bodyOf(request: HttpRequestData) = json(request.body!!.toString(StandardCharsets.UTF_8)).jsonObject

/** Answers from `handler`, holding back any path listed in `held` until its gate opens. */
private class ScriptedTransport(
    private val handler: (HttpRequestData) -> Pair<Int, String>,
) : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        held[request.pathWithQuery]?.await()
        val (status, body) = handler(request)
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")
}

private const val AVATAR = """{"definition":{"version":1,"id":"box","name":"Box","body":{"path":"M20 20H80V80H20Z"},"eyes":{"x":50,"y":46,"spacing":15,"scale":1}},"color":"#38bdf8","eyeColor":"#151515"}"""

internal fun botRequestFixture(status: String = "pending") = """{"id":"r1","agentId":"a1","reason":"Needs a scout","status":"$status","createdAgentId":null,"requestedAt":"2026-11-05T10:00:00Z","decidedAt":null,"proposal":{"name":"Scout","avatar":$AVATAR,"description":"Finds things","instructions":"Look around","provider":"openai","model":"gpt","webSearchProvider":"exa","enabled":true,"tools":["memory","web","coding"],"skills":[{"name":"Map","instructions":"Draw"}],"sharedSkills":["s1"],"sharedMemory":true,"mcpServers":["m1"],"delegates":["a2"],"inference":{"thinkingLevel":"high","temperature":0.5},"review":{"enabled":true},"reviewModel":{"provider":"openai","model":"mini"},"desktopModel":{"provider":"openai","model":"desk"}}}"""

private fun approvedFixture() = """{"request":${botRequestFixture("approved")},"agent":${agentFixture("a9", name = "Scout Prime")}}"""

private val botRequestMessage = Message(id = "t1", entryId = "t", role = "toolResult", botRequest = BotRequestRef("r1", "Scout"))

private fun jobResult(tool: String = "coding", name: String = "fix-login", status: String = "working") = Message(
    id = "t1", entryId = "t", role = "toolResult", toolName = tool,
    toolDetails = buildJsonObject { put("name", name); put("status", status) },
)

private fun jobFixture(name: String, status: String, usage: String = """{"models":{"a/one":{"cost":{"total":1.5}}}}""") =
    """{"name":"$name","conversationId":"c","status":"$status","model":{"provider":"openai","model":"gpt"},"usage":$usage}"""

private fun history(ids: List<String>, hasMore: Boolean = false, oldest: String? = null) = buildString {
    append("""{"messages":[""")
    append(ids.joinToString(",") { """{"id":"$it","entryId":"$it","role":"user","content":"task $it"}""" })
    append("""],"items":[""")
    append(ids.joinToString(",") { """{"kind":"task","id":"$it","text":"task $it"}""" })
    append("""],"hasMore":$hasMore,"oldestEntryId":${oldest?.let { "\"$it\"" } ?: "null"}}""")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatParityTests {
    private fun web(tool: String, vararg pages: String) = Message(
        id = "tool", role = "toolResult", toolName = tool,
        toolDetails = JsonArray(pages.map { json(it) }),
    )

    @Test
    fun sourceKeyIgnoresFragmentTrailingSlashAndHostCase() {
        assertEquals("example.com/a?b=1", sourceKey("https://EXAMPLE.com/a/?b=1#top"))
        assertEquals("example.com", sourceKey("http://example.com/"))
        assertEquals("example.com", sourceKey("https://example.com:443"))
        assertEquals("example.com:8443/a", sourceKey("https://example.com:8443/a"))
        assertEquals("example.com/a?q=x|y", sourceKey("https://example.com/a?q=x|y"))
        assertNull(sourceKey("ftp://example.com/a"))
        assertNull(sourceKey("not a url"))
    }

    @Test
    fun sourcesBelongToTheTurnThatReadThem() {
        val first = Message(id = "r1", role = "assistant")
        val second = Message(id = "r2", role = "assistant")
        val page = """{"url":"https://a.com/x","title":"A"}"""
        val failed = web("web_extract", """{"url":"https://c.com","title":"C"}""").copy(isError = true)
        val messages = listOf(
            web("web_search", page, """{"url":"https://b.com","title":"B","error":"blocked"}"""),
            failed,
            first,
            Message(id = "u", role = "user"),
            second,
        )
        val sources = sourcesOf(messages)
        assertEquals(mapOf("r1" to mapOf("a.com/x" to "A")), sources)
    }

    @Test
    fun splitSourcesMovesCitationsToChipsAndLeavesCodeAlone() {
        val sources = mapOf("a.com/x" to "A", "b.com" to "B")
        val reply = splitSources(
            """
            Fact one ([source](https://a.com/x/), [source](https://b.com)). Again [source](https://a.com/x).
            Unread [source](https://z.com) stays, `[source](https://a.com/x)` too.
            ```
            [source](https://a.com/x)
            ```
            """.trimIndent(),
            sources,
        )
        assertEquals(listOf(SourceChip("https://a.com/x/", "A"), SourceChip("https://b.com", "B")), reply.chips)
        assertEquals(
            """
            Fact one. Again.
            Unread [source](https://z.com) stays, `[source](https://a.com/x)` too.
            ```
            [source](https://a.com/x)
            ```
            """.trimIndent(),
            reply.text,
        )
    }

    @Test
    fun siteOfDropsWwwAndPort() {
        assertEquals("example.com", siteOf("https://www.Example.com:8443/a"))
    }

    @Test
    fun costLineSumsModelsAndHidesZero() {
        assertEquals("$3.0000", costLine(json("""{"models":{"a/one":{"cost":{"total":1.5}},"b/two":{"cost":{"total":1.5}}}}""")))
        assertNull(costLine(json("""{"models":{"claude-code/one":{"cost":{"total":0}}}}""")))
        assertNull(costLine(json("""{"models":{}}""")))
        assertNull(costLine(null))
    }

    @Test
    fun jobResultsAreChatRows() {
        assertEquals(JobRef(JobKind.desktop, "browse", "idle"), jobOf(jobResult("desktop", "browse", "idle")))
        assertNull(jobOf(jobResult().copy(isError = true)))
        assertNull(jobOf(jobResult("web_search")))
        assertTrue(ChatRow.shownInChat(jobResult()))
    }

    @Test
    fun jobMarkdownFencesToolOutputAgainstBackticks() {
        val messages = listOf(
            Message(id = "m1", role = "user", content = JsonPrimitive("Fix it")),
            Message(id = "m2", role = "toolResult", isError = true, content = JsonPrimitive("a ```` b")),
        )
        assertEquals(
            "# fix\n\nCoding job\n\n## Task\n\nFix it\n\nFailed:\n\n`````\na ```` b\n`````\n",
            jobMarkdown(JobRef(JobKind.coding, "fix"), messages),
        )
    }

    @Test
    fun mergeJobPagesKeepsRowsThePageMovedPast() {
        fun rows(vararg ids: String) = ids.map { Message(id = it) }
        assertEquals(rows("1", "2", "3", "4"), mergeJobPages(rows("1", "2", "3"), rows("2", "3", "4")))
        assertEquals(rows("1"), mergeJobPages(rows("1"), emptyList()))
    }

    @Test
    fun clampLevelPrefersTheNearestHigherLevel() {
        assertEquals("high", clampLevel("medium", listOf("off", "high")))
        assertEquals("low", clampLevel("max", listOf("off", "low")))
        assertEquals("off", clampLevel("high", emptyList()))
        assertEquals(7, levelsOf(null).size)
        assertEquals(listOf("off"), levelsOf(ModelSummary(thinkingLevels = listOf("off"))))
    }

    @Test
    fun changeRowsReadLikeTheWebHeadline() {
        val skill = ActivityRecord(
            kind = "change", agentName = "Kev",
            details = json("""{"actor":"review","target":"skill","targetId":"s1","action":"update","agentId":"a1","after":{"name":"Triage"},"before":null}"""),
        )
        assertEquals("Kev's review changed a skill · Triage", skill.headline().run { "$lead $verb $target" })
        val memory = ActivityRecord(
            kind = "change",
            details = json("""{"actor":"owner","target":"memory","targetId":"m1","action":"delete","before":{"content":"First\nSecond"}}"""),
        )
        assertEquals("You deleted a shared memory · First", memory.headline().run { "$lead $verb $target" })
    }

    private fun modelWith(transport: ScriptedTransport, scope: TestScope, agents: List<Agent> = emptyList()): AppModel {
        val client = APIClient(baseURL = "http://crumble.test/", token = "", transport = transport)
        return AppModel(client = client, scope = scope).also { it.agents = agents }
    }

    @Test
    fun plainApprovalSendsNoProposal() = runTest {
        val transport = ScriptedTransport { 200 to approvedFixture() }
        val client = APIClient(baseURL = "http://crumble.test/", token = "", transport = transport)
        client.approveBotRequest("a1", "r1")
        assertEquals(JsonObject(emptyMap()), bodyOf(transport.requests.single()))
    }

    @Test
    fun editedApprovalKeepsFieldsTheEditorHidesAndWaitsForTheServer() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val approve = "/v1/agents/a1/bot-requests/r1/approve"
        var refuse = true
        val transport = ScriptedTransport { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/bot-requests" -> 200 to "[${botRequestFixture(if (refuse) "pending" else "approved")}]"
                approve -> if (refuse) 503 to """{"error":"busy"}""" else 200 to approvedFixture()
                else -> 503 to ""
            }
        }
        val gate = CompletableDeferred<Unit>().also { transport.held[approve] = it }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(botRequestMessage)))
        testScheduler.runCurrent()

        model.openBotRequestEditor("r1")
        val draft = model.agentDraft!!
        assertEquals(setOf("web", "coding"), draft.otherTools)
        assertEquals("Look around", draft.instructions)
        model.agentDraft = draft.copy(name = "Scout Prime", description = "", otherTools = setOf("web"))
        model.saveAgentEditor()
        testScheduler.runCurrent()

        assertTrue("r1" in model.decidingIDs)
        assertEquals("Scout Prime", model.agentDraft?.name)
        assertTrue(model.agents.isEmpty())

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(model.decisionErrors["r1"].orEmpty().contains("busy"))
        assertEquals("Scout Prime", model.agentDraft?.name)
        assertTrue(model.agents.isEmpty())

        refuse = false
        model.saveAgentEditor()
        testScheduler.runCurrent()
        assertNull(model.agentDraft)
        assertEquals(listOf("a9"), model.agents.map { it.id })
        assertEquals("approved", model.botRequests.single().status)

        val sent = bodyOf(transport.requests.last { it.pathWithQuery == approve })["proposal"]!!.jsonObject
        assertEquals("Scout Prime", sent["name"]!!.jsonPrimitive.content)
        assertEquals("", sent["description"]!!.jsonPrimitive.content)
        assertEquals("exa", sent["webSearchProvider"]!!.jsonPrimitive.content)
        assertEquals(json("""{"provider":"openai","model":"desk"}"""), sent["desktopModel"])
        assertEquals(json("""{"provider":"openai","model":"mini"}"""), sent["reviewModel"])
        assertEquals(json("""{"enabled":true}"""), sent["review"])
        assertEquals(json("""["memory","web"]"""), sent["tools"])
        assertEquals(json("""["s1"]"""), sent["sharedSkills"])
        assertEquals(json("""["m1"]"""), sent["mcpServers"])
        assertEquals(json("""{"thinkingLevel":"high","temperature":0.5}"""), sent["inference"])
        model.cancelStream()
    }

    @Test
    fun editorClosesWhenTheRequestSettlesElsewhere() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var status = "pending"
        val transport = ScriptedTransport { req ->
            if (req.pathWithQuery == "/v1/agents/a1/bot-requests") 200 to "[${botRequestFixture(status)}]" else 503 to ""
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(botRequestMessage)))
        testScheduler.runCurrent()
        model.openBotRequestEditor("r1")
        status = "declined"
        testScheduler.advanceTimeBy(5_100)
        testScheduler.runCurrent()
        assertNull(model.agentDraft)
        model.cancelStream()
    }

    @Test
    fun chatJobsReloadWhenAJobMessageArrives() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = ScriptedTransport { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/coding-jobs" -> 200 to """{"jobs":[${jobFixture("fix-login", "idle")}]}"""
                "/v1/agents/a1/desktop-jobs" -> 200 to """{"jobs":[{"name":"browse","conversationId":"c","status":"working","question":{"id":"q1","text":"Which?","askedAt":1}}]}"""
                else -> 503 to ""
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(jobResult())))
        testScheduler.runCurrent()
        assertEquals("gpt · $1.5000", model.jobs.first().subtitle)
        assertEquals("q1", model.jobs.last().question?.id)

        val reads = transport.requests.size
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(jobResult())))
        testScheduler.runCurrent()
        assertEquals(reads, transport.requests.size)

        val reply = Message(id = "x:9:0", role = "user", origin = dev.crumble.android.model.MessageOrigin(kind = "coding"))
        model.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(jobResult(), reply)))
        testScheduler.runCurrent()
        assertEquals(reads + 2, transport.requests.size)
        model.cancelStream()
    }

    @Test
    fun newestJobStepRefreshesAfterSlidingToAnOlderPage() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var shifted = false
        val transport = ScriptedTransport { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/coding-jobs" -> 200 to """{"jobs":[${jobFixture("fix", "working")}]}"""
                "/v1/agents/a1/desktop-jobs" -> 200 to """{"jobs":[]}"""
                "/v1/agents/a1/coding-jobs/fix/history?limit=100" -> if (shifted) {
                    200 to history(listOf("2"), hasMore = true, oldest = "2")
                } else {
                    200 to """{"messages":[{"id":"1","entryId":"1","role":"assistant","content":""}],"items":[{"kind":"step","id":"call","tool":"bash","label":"Ran","icon":"terminal"}],"hasMore":false,"oldestEntryId":"1"}"""
                }
                "/v1/agents/a1/coding-jobs/fix/history?limit=100&before=2" -> 200 to """{"messages":[{"id":"1","entryId":"1","role":"assistant","content":""}],"items":[{"kind":"step","id":"call","tool":"bash","label":"Ran","icon":"terminal","result":{"id":"r","entryId":"r","role":"toolResult","content":"done"}}],"hasMore":false,"oldestEntryId":"1"}"""
                else -> 503 to ""
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.coding, "fix", "working"))
        testScheduler.runCurrent()
        assertNull((model.jobPanel!!.items.first() as JobItem.Step).result)
        shifted = true
        testScheduler.advanceTimeBy(3_100)
        testScheduler.runCurrent()
        val result = (model.jobPanel!!.items.first() as JobItem.Step).result?.text
        model.closeJob()
        assertEquals("done", result)
    }

    @Test
    fun loadedEarlierJobStepRefreshesWhenItsResultArrives() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var finished = false
        val transport = ScriptedTransport { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/coding-jobs" -> 200 to """{"jobs":[${jobFixture("fix", "working")}]}"""
                "/v1/agents/a1/desktop-jobs" -> 200 to """{"jobs":[]}"""
                "/v1/agents/a1/coding-jobs/fix/history?limit=100" -> 200 to history(listOf("2"), hasMore = true, oldest = "2")
                "/v1/agents/a1/coding-jobs/fix/history?limit=100&before=2" -> {
                    val result = if (finished) ""","result":{"id":"r","entryId":"r","role":"toolResult","content":"done"}""" else ""
                    200 to """{"messages":[{"id":"1","entryId":"1","role":"assistant","content":""}],"items":[{"kind":"step","id":"call","tool":"bash","label":"Ran","icon":"terminal"$result}],"hasMore":false,"oldestEntryId":"1"}"""
                }
                else -> 503 to ""
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.coding, "fix", "working"))
        testScheduler.runCurrent()
        model.loadEarlierJobActivity()
        testScheduler.runCurrent()
        assertNull((model.jobPanel!!.items.first() as JobItem.Step).result)
        finished = true
        testScheduler.advanceTimeBy(3_100)
        testScheduler.runCurrent()
        assertEquals("done", (model.jobPanel!!.items.first() as JobItem.Step).result?.text)
        model.closeJob()
    }

    @Test
    fun jobPanelFollowsAWorkingJobThenStopsPollingWhenIdle() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var status = "working"
        var page = history(listOf("1", "2"), hasMore = true, oldest = "1")
        val transport = ScriptedTransport { req ->
            val path = req.pathWithQuery
            when {
                path == "/v1/agents/a1/coding-jobs" -> 200 to """{"jobs":[${jobFixture("fix login", status)}]}"""
                path == "/v1/agents/a1/desktop-jobs" -> 200 to """{"jobs":[]}"""
                path == "/v1/agents/a1/coding-jobs/fix%20login/history?limit=100" -> 200 to page
                path == "/v1/agents/a1/coding-jobs/fix%20login/history?limit=100&before=1" -> 200 to history(listOf("0"))
                else -> 503 to ""
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.coding, "fix login", "working"))
        testScheduler.runCurrent()
        assertEquals(JobRef(JobKind.coding, "fix login"), model.jobPanel?.job)
        assertEquals(listOf("1", "2"), model.jobPanel?.messages?.map { it.id })
        assertEquals(true, model.jobPanel?.hasMore)

        page = history(listOf("2", "3"), hasMore = true, oldest = "2")
        testScheduler.advanceTimeBy(3_100)
        testScheduler.runCurrent()
        assertEquals(listOf("1", "2", "3"), model.jobPanel?.messages?.map { it.id })
        assertEquals("1", model.jobPanel?.oldestEntryID)

        model.loadEarlierJobActivity()
        testScheduler.runCurrent()
        assertEquals(listOf("0", "1", "2", "3"), model.jobPanel?.messages?.map { it.id })
        assertEquals(false, model.jobPanel?.hasMore)

        status = "idle"
        page = history(listOf("3", "4"))
        testScheduler.advanceTimeBy(3_100)
        testScheduler.runCurrent()
        assertEquals("4", model.jobPanel?.messages?.last()?.id)

        val reads = transport.requests.count { it.pathWithQuery.contains("/history") }
        testScheduler.advanceTimeBy(14_000)
        testScheduler.runCurrent()
        assertEquals(reads, transport.requests.count { it.pathWithQuery.contains("/history") })
        model.closeJob()
        assertNull(model.jobPanel)
    }

    @Test
    fun aLateHistoryPageIsDroppedAfterTheBotChanges() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val path = "/v1/agents/a1/desktop-jobs/browse/history?limit=100"
        val transport = ScriptedTransport { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/coding-jobs", "/v1/agents/a1/desktop-jobs" -> 200 to """{"jobs":[]}"""
                path -> 200 to history(listOf("1"))
                else -> 503 to ""
            }
        }
        val gate = CompletableDeferred<Unit>().also { transport.held[path] = it }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.desktop, "browse"))
        testScheduler.runCurrent()
        assertTrue(transport.requests.any { it.pathWithQuery == path })

        model.selectAgent("a2")
        model.openJob(JobRef(JobKind.desktop, "browse"))
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(emptyList<Message>(), model.jobPanel?.messages)
        model.cancelStream()
    }

    @Test
    fun jobHistoryFailureOffersRetryAndKeepsThePanelOpen() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var fail = true
        val transport = ScriptedTransport { req ->
            when {
                req.pathWithQuery.endsWith("-jobs") -> 200 to """{"jobs":[${jobFixture("fix", "idle")}]}""".takeIf { "coding" in req.pathWithQuery }.orEmpty().ifEmpty { """{"jobs":[]}""" }
                fail -> 503 to ""
                else -> 200 to history(listOf("1"))
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.coding, "fix"))
        testScheduler.runCurrent()
        assertEquals(true, model.jobPanel?.failed)
        assertEquals(false, model.jobPanel?.loaded)

        fail = false
        model.retryJobHistory()
        testScheduler.runCurrent()
        assertEquals(false, model.jobPanel?.failed)
        assertEquals(listOf("1"), model.jobPanel?.messages?.map { it.id })
        model.cancelStream()
    }

    @Test
    fun answeringFromThePanelRefreshesItsQuestion() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var waiting = true
        val transport = ScriptedTransport { req ->
            val question = if (waiting) ""","question":{"id":"q1","text":"Which?","askedAt":1}""" else ""
            when (req.pathWithQuery) {
                "/v1/agents/a1/coding-jobs" -> 200 to """{"jobs":[{"name":"fix","conversationId":"c","status":"idle"$question}]}"""
                "/v1/agents/a1/desktop-jobs" -> 200 to """{"jobs":[]}"""
                "/v1/agents/a1/coding-jobs/fix/answer" -> { waiting = false; 200 to """{"answered":true}""" }
                else -> 200 to history(emptyList())
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.coding, "fix"))
        testScheduler.runCurrent()
        assertEquals("q1", model.jobs.single().question?.id)
        model.answerJobQuestion("fix", "q1", "That one")
        testScheduler.runCurrent()
        assertNull(model.jobs.single().question)
        model.cancelStream()
    }

    @Test
    fun jobListLoadsBothKindsAndRecoversFromFailure() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var fail = true
        val transport = ScriptedTransport { req ->
            when {
                fail -> 503 to ""
                "coding" in req.pathWithQuery -> 200 to """{"jobs":[${jobFixture("fix", "idle")}]}"""
                else -> 200 to """{"jobs":[${jobFixture("browse", "idle")}]}"""
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJobList()
        testScheduler.runCurrent()
        assertTrue(model.jobsFailed)

        fail = false
        model.openJobList()
        testScheduler.runCurrent()
        assertFalse(model.jobsFailed)
        assertEquals(listOf(JobKind.coding, JobKind.desktop), model.jobs.map { it.kind })
        model.closeJobList()
        val reads = transport.requests.size
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()
        assertEquals(reads, transport.requests.size)
    }

    @Test
    fun transcriptDownloadReadsEveryPageOldestFirst() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = ScriptedTransport { req ->
            when (req.pathWithQuery) {
                "/v1/agents/a1/coding-jobs/fix/history?limit=200" -> 200 to history(listOf("2"), hasMore = true, oldest = "2")
                "/v1/agents/a1/coding-jobs/fix/history?limit=200&before=2" -> 200 to history(listOf("1"))
                "/v1/agents/a1/coding-jobs/fix/history?limit=100" -> 200 to history(listOf("2"))
                else -> 200 to """{"jobs":[]}"""
            }
        }
        val model = modelWith(transport, scope)
        model.selectAgent("a1")
        model.openJob(JobRef(JobKind.coding, "fix"))
        testScheduler.runCurrent()
        var written = ""
        model.downloadJobTranscript { written = it }
        assertEquals(true, model.jobPanel?.downloading)
        testScheduler.runCurrent()
        assertEquals("# fix\n\nCoding job\n\n## Task\n\ntask 1\n\n## Task\n\ntask 2\n", written)
        assertEquals(false, model.jobPanel?.downloading)

        model.downloadJobTranscript { error("disk full") }
        testScheduler.runCurrent()
        assertEquals(true, model.jobPanel?.downloadFailed)
        model.cancelStream()
    }
}
