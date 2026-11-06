package dev.crumble.android

import dev.crumble.android.model.ACTIVITY_PAGE_SIZE
import dev.crumble.android.model.ActivityFeedResponse
import dev.crumble.android.model.ActivityFeedState
import dev.crumble.android.model.ActivityRecord
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.Route
import dev.crumble.android.model.activityDays
import dev.crumble.android.model.clock
import dev.crumble.android.model.detailText
import dev.crumble.android.model.failed
import dev.crumble.android.model.headline
import dev.crumble.android.model.preview
import dev.crumble.android.model.withFirstPage
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun event(id: String, kind: String = "tool-call", details: String = "{}", timestamp: Long = 1_720_000_000_000) =
    """{"id":"$id","agentId":"a1","agentName":"Atlas","entryId":"r-$id","timestamp":$timestamp,"kind":"$kind","details":$details}"""

private fun feed(vararg events: String, cursor: String? = null) =
    """{"activities":[${events.joinToString(",")}],"nextCursor":${cursor?.let { "\"$it\"" } ?: "null"},"hasMore":${cursor != null}}"""

private fun record(kind: String, details: String, agentName: String = "Atlas", timestamp: Long? = 1_720_000_000_000) =
    ActivityRecord(id = "e", agentId = "a1", agentName = agentName, kind = kind, timestamp = timestamp, details = CrumbleJson.parseToJsonElement(details))

private class FeedTransport(private val handler: (String) -> Pair<Int, String>) : HttpTransport {
    val paths = mutableListOf<String>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        paths.add(request.pathWithQuery)
        held[request.pathWithQuery]?.await()
        val (status, body) = handler(request.pathWithQuery)
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")
}

private const val FIRST = "/v1/activity/feed?limit=50"
private const val SECOND = "/v1/activity/feed?limit=50&cursor=c1"
private const val TOOLS = "/v1/activity/feed?limit=50&kind=tool-call"

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityTests {
    private fun modelWith(transport: FeedTransport, scope: CoroutineScope) =
        AppModel(client = APIClient(baseURL = "https://example.test", token = "secret", transport = transport), scope = scope)

    @Test
    fun signingOutDropsTheOldServersActivity() = runTest {
        val transport = FeedTransport { 200 to feed(event("e1")) }
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()
        assertEquals(1, model.activities.size)
        model.signOut()
        runCurrent()
        assertTrue(model.activities.isEmpty())
    }

    @Test
    fun showsLoadingThenThePageAndItsCursor() = runTest {
        val transport = FeedTransport { 200 to feed(event("e1"), cursor = "c1") }
        val gate = CompletableDeferred<Unit>()
        transport.held[FIRST] = gate
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()
        assertTrue(model.activityFeed.loading)
        gate.complete(Unit)
        runCurrent()
        assertFalse(model.activityFeed.loading)
        assertEquals("c1", model.activityFeed.nextCursor)
        assertEquals(listOf(FIRST), transport.paths)
        assertEquals(50L, ACTIVITY_PAGE_SIZE)
    }

    @Test
    fun filtersGoToTheServerAndAResponseForTheOldFilterIsDropped() = runTest {
        val transport = FeedTransport { path ->
            200 to when {
                "kind=tool-call" in path && "agentId=a2" in path -> feed(event("bot-tool"))
                "kind=tool-call" in path -> feed(event("tool"))
                else -> feed(event("everything"))
            }
        }
        val slow = CompletableDeferred<Unit>()
        transport.held[FIRST] = slow
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()

        model.filterActivity(kind = "tool-call")
        runCurrent()
        assertEquals(listOf("tool"), model.activities.map { it.id })
        slow.complete(Unit)
        runCurrent()
        assertEquals(listOf("tool"), model.activities.map { it.id })

        model.filterActivity(botID = "a2")
        assertTrue("A new filter must not show the old filter's rows", model.activities.isEmpty())
        runCurrent()
        assertEquals("/v1/activity/feed?agentId=a2&limit=50&kind=tool-call", transport.paths.last())
        assertEquals(listOf("bot-tool"), model.activities.map { it.id })
    }

    @Test
    fun refreshKeepsFiltersAndLeavingThePageClearsThem() = runTest {
        val transport = FeedTransport { 200 to feed(event("e1")) }
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()
        model.filterActivity(kind = "tool-call")
        runCurrent()

        model.openPanel(Route.Activity)
        runCurrent()
        assertEquals(TOOLS, transport.paths.last())

        model.openPanel(Route.Memory)
        model.openPanel(Route.Activity)
        runCurrent()
        assertEquals(FIRST, transport.paths.last())
        assertNull(model.activityFeed.kind)
    }

    @Test
    fun olderPagesAppendWithoutDuplicatesAndFilterCarriesOver() = runTest {
        val transport = FeedTransport { path ->
            200 to when (path) {
                FIRST -> feed(event("e1"), event("e2"), cursor = "c1")
                else -> feed(event("e2"), event("e3"))
            }
        }
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()
        model.loadOlderActivity()
        runCurrent()
        assertEquals(SECOND, transport.paths.last())
        assertEquals(listOf("e1", "e2", "e3"), model.activities.map { it.id })
        assertNull(model.activityFeed.nextCursor)
        model.loadOlderActivity()
        runCurrent()
        assertEquals(2, transport.paths.size)
    }

    @Test
    fun aFailedOlderPageKeepsTheListAndCanBeRetried() = runTest {
        var failing = true
        val transport = FeedTransport { path ->
            when {
                path == FIRST -> 200 to feed(event("e1"), cursor = "c1")
                failing -> 500 to """{"error":"down"}"""
                else -> 200 to feed(event("e2"))
            }
        }
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()
        model.loadOlderActivity()
        assertTrue(model.activityFeed.loadingOlder)
        runCurrent()
        assertFalse(model.activityFeed.loadingOlder)
        assertTrue(model.activityFeed.olderError.isNotEmpty())
        assertEquals(listOf("e1"), model.activities.map { it.id })
        assertEquals("c1", model.activityFeed.nextCursor)

        failing = false
        model.loadOlderActivity()
        runCurrent()
        assertEquals(listOf("e1", "e2"), model.activities.map { it.id })
        assertEquals("", model.activityFeed.olderError)
    }

    @Test
    fun aFailedFirstLoadStaysOnThePageAndRefreshRecovers() = runTest {
        var failing = true
        val transport = FeedTransport { if (failing) 500 to """{"error":"down"}""" else 200 to feed(event("e1")) }
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()
        assertTrue(model.activityFeed.error.isNotEmpty())
        assertFalse(model.activityFeed.loading)

        failing = false
        model.openPanel(Route.Activity)
        runCurrent()
        assertEquals("", model.activityFeed.error)
        assertEquals(listOf("e1"), model.activities.map { it.id })
    }

    @Test
    fun aLateOlderPageNeverLandsInANewFilter() = runTest {
        val transport = FeedTransport { path -> 200 to if (path == SECOND) feed(event("old")) else feed(event("e1"), cursor = "c1") }
        val gate = CompletableDeferred<Unit>()
        transport.held[SECOND] = gate
        val model = modelWith(transport, this)
        model.openPanel(Route.Activity)
        runCurrent()
        model.loadOlderActivity()
        runCurrent()
        model.filterActivity(kind = "tool-call")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("e1"), model.activities.map { it.id })
        assertFalse(model.activityFeed.loadingOlder)
    }

    @Test
    fun aRefreshAfterARevertKeepsOlderPagesWhenTheFirstPageStillJoinsThem() {
        fun records(vararg ids: String) = ids.map { ActivityRecord(id = it) }
        val state = ActivityFeedState(items = records("e2", "e3", "e4"), nextCursor = "older")
        val joined = state.withFirstPage(ActivityFeedResponse(records("e1", "e2"), nextCursor = "c1"), keepOlder = true)
        assertEquals(listOf("e1", "e2", "e3", "e4"), joined.items.map { it.id })
        assertEquals("older", joined.nextCursor)

        val apart = state.withFirstPage(ActivityFeedResponse(records("x1"), nextCursor = "c1"), keepOlder = true)
        assertEquals(listOf("x1"), apart.items.map { it.id })
        assertEquals("c1", apart.nextCursor)
    }

    @Test
    fun sentencesMatchTheWeb() {
        fun line(record: ActivityRecord) = record.headline().run { listOf(lead, verb, target).joinToString(" ") }
        assertEquals("Atlas used search", line(record("tool-call", """{"toolName":"search","name":"ignored"}""")))
        assertEquals("Atlas used a tool", line(record("tool-call", "{}")))
        assertEquals("Atlas got a result from search", line(record("tool-result", """{"name":"search"}""")))
        assertEquals("Atlas got an error from search", line(record("tool-result", """{"toolName":"search","isError":true}""")))
        assertEquals("Atlas called linear_find", line(record("mcp-call", """{"name":"linear_find"}""")))
        assertEquals("Atlas called an MCP tool", line(record("mcp-call", "{}")))
        assertEquals("Scout messaged Kev", line(record("bot-message", """{"communication":{"fromName":"Scout","toName":"Kev"}}""")))
        assertEquals("Atlas messaged a bot", line(record("bot-message", "{}")))
        assertTrue(record("tool-call", "{}").headline().code)
        assertFalse(record("bot-message", "{}").headline().code)
    }

    @Test
    fun previewsAreReadableAndNeverRawDetails() {
        assertEquals("a b c", record("bot-message", """{"content":"a\n  b\tc"}""").preview())
        assertEquals("x".repeat(240), record("tool-result", """{"content":"${"x".repeat(300)}"}""").preview())
        assertEquals("""[{"type":"text"}]""", record("tool-result", """{"content":[{"type":"text"}]}""").preview())
        assertEquals("boom", record("tool-call", """{"error":"boom","args":"a","arguments":{"q":1}}""").preview())
        assertEquals("a", record("tool-call", """{"args":"a","arguments":{"q":1}}""").preview())
        assertEquals("""{"q":1}""", record("mcp-call", """{"arguments":{"q":1}}""").preview())
        assertEquals("", record("tool-call", """{"content":"hidden"}""").preview())
        assertEquals("", record("change", """{"content":"hidden"}""").preview())
    }

    @Test
    fun detailsShowMessagesAsTextAndEverythingElseAsIndentedJson() {
        assertEquals("Hello", record("bot-message", """{"content":"Hello"}""").detailText())
        assertEquals("{\n  \"toolName\": \"search\"\n}", record("tool-call", """{"toolName":"search"}""").detailText())
        assertEquals("{\n  \"content\": \"\"\n}", record("bot-message", """{"content":""}""").detailText())
        assertEquals("No details", ActivityRecord(kind = "tool-call").detailText())
    }

    @Test
    fun failuresAreFlagged() {
        assertTrue(record("tool-result", """{"isError":true}""").failed)
        assertTrue(record("mcp-call", """{"error":"boom"}""").failed)
        assertFalse(record("tool-result", """{"isError":false,"error":""}""").failed)
        assertFalse(record("change", """{"isError":true}""").failed)
    }

    @Test
    fun rowsGroupByTheViewersLocalDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val today = LocalDate.of(2026, 11, 5)
        fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(
            ActivityRecord(id = "late", timestamp = at(today, 23)),
            ActivityRecord(id = "early", timestamp = at(today, 0)),
            ActivityRecord(id = "yesterday", timestamp = at(today.minusDays(1), 12)),
            ActivityRecord(id = "week", timestamp = at(today.minusDays(3), 12)),
            ActivityRecord(id = "lastYear", timestamp = at(LocalDate.of(2025, 12, 31), 12)),
            ActivityRecord(id = "undated"),
        )
        val days = activityDays(rows, today, zone, Locale.US)
        assertEquals(
            listOf("Today", "Yesterday", "Monday, November 2", "Wednesday, December 31, 2025", "Earlier"),
            days.map { it.label },
        )
        assertEquals(listOf("late", "early"), days[0].items.map { it.id })
    }

    @Test
    fun rowsShowTheLocalClockTime() {
        val stamp = Instant.parse("2026-11-05T08:30:00Z").toEpochMilli()
        assertEquals("08:30", ActivityRecord(timestamp = stamp).clock(ZoneOffset.UTC, Locale.UK))
        assertEquals("", ActivityRecord().clock(ZoneOffset.UTC, Locale.UK))
    }

    @Test
    fun theBotFilterListsConnectedBots() = runTest {
        val transport = FeedTransport { 200 to feed() }
        val model = modelWith(transport, this)
        model.agents = listOf(Agent(id = "a1", name = "Atlas"))
        model.openPanel(Route.Activity)
        runCurrent()
        model.filterActivity(botID = "a1")
        runCurrent()
        assertEquals("/v1/activity/feed?agentId=a1&limit=50", transport.paths.last())
        model.filterActivity(botID = null)
        runCurrent()
        assertEquals(FIRST, transport.paths.last())
    }
}
