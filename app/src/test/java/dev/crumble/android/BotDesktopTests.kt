package dev.crumble.android

import dev.crumble.android.generated.DesktopControl
import dev.crumble.android.generated.DesktopViewer
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.DESKTOP_BOT
import dev.crumble.android.model.DESKTOP_HAND_BACK_FAILED
import dev.crumble.android.model.DESKTOP_OWNER
import dev.crumble.android.model.DESKTOP_UNAVAILABLE
import dev.crumble.android.model.DesktopKey
import dev.crumble.android.model.DesktopLink
import dev.crumble.android.model.DesktopPress
import dev.crumble.android.model.desktopKeyScript
import dev.crumble.android.model.desktopPresses
import dev.crumble.android.model.newerControl
import dev.crumble.android.model.press
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STATUS = "/v1/agents/a1/desktop/status"
private const val CONTROL = "/v1/agents/a1/desktop/control"
private const val VIEWER = "/v1/agents/a1/desktop/viewer"
private const val PASSIVE = "$VIEWER?passive=true"

private fun control(holder: String, changes: Long) =
    """event: control
data: {"holder":"$holder","changes":$changes,"updatedAt":"2026-11-05T10:00:00Z"}

"""

private fun controlBody(holder: String, changes: Long) =
    """{"holder":"$holder","changes":$changes,"updatedAt":"2026-11-05T10:00:00Z"}"""

/** Serves one desktop event stream per connection, and answers requests from the fields below. */
private class DesktopTransport(streams: List<String> = listOf(control(DESKTOP_BOT, 1))) : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    var running = false
    var statusCode = 200
    var controlCode = 200
    var viewerCode = 201
    private val remaining = streams.toMutableList()
    private var tickets = 0
    var opened = 0

    fun methods(path: String) = requests.filter { it.pathWithQuery == path }.map { it.method }

    fun holders() = requests.filter { it.pathWithQuery == CONTROL && it.method == "PUT" }.map {
        it.body!!.toString(StandardCharsets.UTF_8).substringAfter("holder\":\"").substringBefore("\"")
    }

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        held[request.pathWithQuery]?.await()
        val body = when (request.pathWithQuery) {
            STATUS -> statusCode to """{"running":$running}"""
            CONTROL -> {
                val holder = if (request.body!!.toString(StandardCharsets.UTF_8).contains(DESKTOP_OWNER)) DESKTOP_OWNER else DESKTOP_BOT
                controlCode to controlBody(holder, requests.size.toLong() + 1)
            }
            VIEWER, PASSIVE -> {
                tickets += 1
                viewerCode to """{"path":"/desktop-viewer/t$tickets","expiresAt":"2026-11-05T10:01:00Z"}"""
            }
            else -> 503 to ""
        }
        return HttpResponseData(body.first, body.second.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse {
        val desktop = request.pathWithQuery == "/v1/agents/a1/desktop/events"
        if (desktop) opened += 1
        val text = if (desktop && remaining.isNotEmpty()) remaining.removeAt(0) else ""
        return HttpStreamResponse(200, ByteArrayInputStream(text.toByteArray()))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BotDesktopTests {
    private fun modelWith(transport: DesktopTransport, scope: TestScope) =
        AppModel(client = APIClient(baseURL = "http://crumble.test/", token = "", transport = transport), scope = scope)

    // The stream reads on the IO dispatcher, so events land off the test scheduler.
    private fun TestScope.settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            testScheduler.runCurrent()
            Thread.sleep(2)
        }
    }

    @Test
    fun newerControlKeepsTheLatestChange() {
        val first = DesktopControl(DESKTOP_OWNER, 4, "a")
        val stale = DesktopControl(DESKTOP_BOT, 3, "b")
        val same = DesktopControl(DESKTOP_BOT, 4, "c")
        assertEquals(stale, newerControl(null, stale))
        assertEquals(first, newerControl(first, stale))
        assertEquals(same, newerControl(first, same))
    }

    @Test
    fun viewerUrlIsResolvedAgainstTheServer() {
        val api = APIClient("https://crumble.test/", "", DesktopTransport())
        assertEquals("https://crumble.test/desktop-viewer/a.b", api.desktopViewerURL(DesktopViewer("/desktop-viewer/a.b", "t")))
    }

    @Test
    fun textBecomesKeyPresses() {
        assertEquals(
            listOf(DesktopPress("a"), DesktopKey.Enter.press(), DesktopKey.Tab.press(), DesktopPress("é")),
            desktopPresses("a\r\n\té😀"),
        )
    }

    @Test
    fun keyScriptEscapesTextAndHoldsCtrlAroundThePresses() {
        val script = desktopKeyScript(listOf(DesktopPress("\"'\\"), DesktopPress("c")), ctrl = true)
        assertTrue(script, script.contains("""k('keydown',"\"'\\","")"""))
        val down = script.indexOf("k('keydown','Control','ControlLeft')")
        val press = script.indexOf("""k('keydown',"c","")""")
        val up = script.indexOf("k('keyup','Control','ControlLeft')")
        assertTrue(down in 0 until press && press < up)
        assertFalse(desktopKeyScript(listOf(DesktopPress("c"))).contains("Control"))
    }

    @Test
    fun eventsSetControlAndAStaleOneIsIgnored() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport(listOf(control(DESKTOP_OWNER, 5) + "event: heartbeat\ndata: {}\n\n" + control(DESKTOP_BOT, 2)))
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        settle { transport.opened == 1 && model.botDesktop?.control != null }
        testScheduler.runCurrent()
        assertTrue(model.botDesktop!!.owner)
        assertEquals(5L, model.botDesktop!!.control!!.changes)
        model.closeBotDesktop()
    }

    @Test
    fun aDroppedEventStreamReconnectsAndCatchesUp() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport(listOf(control(DESKTOP_BOT, 1), control(DESKTOP_OWNER, 2)))
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        settle { model.botDesktop?.control != null }
        testScheduler.advanceTimeBy(600)
        settle { model.botDesktop?.owner == true }
        assertEquals(2, transport.opened)
        model.closeBotDesktop()
    }

    @Test
    fun takingControlStaysPendingUntilConfirmed() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport()
        val gate = CompletableDeferred<Unit>().also { transport.held[CONTROL] = it }
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        settle { model.botDesktop?.control != null }

        model.setDesktopHolder(DESKTOP_OWNER)
        testScheduler.runCurrent()
        assertEquals(DESKTOP_OWNER, model.botDesktop!!.taking)
        assertFalse(model.botDesktop!!.owner)

        model.setDesktopHolder(DESKTOP_OWNER)
        testScheduler.runCurrent()
        assertEquals(listOf(DESKTOP_OWNER), transport.holders())

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(model.botDesktop!!.taking)
        assertTrue(model.botDesktop!!.owner)

        model.setDesktopHolder(DESKTOP_BOT)
        testScheduler.runCurrent()
        assertFalse(model.botDesktop!!.owner)
        model.closeBotDesktop()
        testScheduler.runCurrent()
        assertEquals(listOf(DESKTOP_OWNER, DESKTOP_BOT), transport.holders())
    }

    @Test
    fun aFailedTakeKeepsTheHolderAndCanBeRetried() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport().also { it.controlCode = 500 }
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        settle { model.botDesktop?.control != null }

        model.setDesktopHolder(DESKTOP_OWNER)
        testScheduler.runCurrent()
        assertFalse(model.botDesktop!!.owner)
        assertNull(model.botDesktop!!.taking)
        assertTrue(model.botDesktop!!.takeError.isNotEmpty())

        transport.controlCode = 200
        model.setDesktopHolder(DESKTOP_OWNER)
        testScheduler.runCurrent()
        assertTrue(model.botDesktop!!.owner)
        assertEquals("", model.botDesktop!!.takeError)
        model.closeBotDesktop()
    }

    @Test
    fun takingControlWaitsForTheFirstEvent() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport(emptyList())
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        model.setDesktopHolder(DESKTOP_OWNER)
        testScheduler.runCurrent()
        assertEquals(emptyList<String>(), transport.holders())
        model.closeBotDesktop()
    }

    @Test
    fun leavingHandsControlBackOnlyWhenTheOwnerHoldsIt() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val watching = DesktopTransport()
        val watcher = modelWith(watching, scope)
        watcher.openBotDesktop("a1")
        settle { watcher.botDesktop?.control != null }
        watcher.closeBotDesktop()
        testScheduler.runCurrent()
        assertEquals(emptyList<String>(), watching.holders())

        val holding = DesktopTransport(listOf(control(DESKTOP_OWNER, 3)))
        val holder = modelWith(holding, scope)
        holder.openBotDesktop("a1")
        settle { holder.botDesktop?.owner == true }
        holder.closeBotDesktop()
        testScheduler.runCurrent()
        assertNull(holder.botDesktop)
        assertEquals(listOf(DESKTOP_BOT), holding.holders())
    }

    @Test
    fun leavingDuringATakeHandsBackAfterItLands() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport()
        val gate = CompletableDeferred<Unit>().also { transport.held[CONTROL] = it }
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        settle { model.botDesktop?.control != null }
        model.setDesktopHolder(DESKTOP_OWNER)
        testScheduler.runCurrent()

        model.closeBotDesktop()
        testScheduler.runCurrent()
        assertEquals(listOf(DESKTOP_OWNER), transport.holders())

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(DESKTOP_OWNER, DESKTOP_BOT), transport.holders())
        assertNull(model.botDesktop)
    }

    @Test
    fun aFailedHandBackRetriesThenSaysSo() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport(listOf(control(DESKTOP_OWNER, 3))).also { it.controlCode = 503 }
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        settle { model.botDesktop?.owner == true }
        model.closeBotDesktop()
        testScheduler.advanceUntilIdle()
        assertEquals(3, transport.holders().size)
        assertEquals(DESKTOP_HAND_BACK_FAILED, model.errorText)
    }

    @Test
    fun refreshingTheSameBotKeepsTheDesktopAndSwitchingBotsLeavesIt() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport(listOf(control(DESKTOP_OWNER, 3)))
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        settle { model.botDesktop?.owner == true }

        model.selectAgent("a1")
        testScheduler.runCurrent()
        assertTrue(model.botDesktop!!.owner)
        assertEquals(emptyList<String>(), transport.holders())

        model.selectAgent("a2")
        testScheduler.runCurrent()
        assertNull(model.botDesktop)
        assertEquals(listOf(DESKTOP_BOT), transport.holders())
        model.cancelStream()
    }

    @Test
    fun everyConnectionGetsAFreshLinkAndAFailureCanReconnect() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport()
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        assertTrue(model.botDesktop!!.viewer.connecting)
        testScheduler.runCurrent()
        assertEquals("http://crumble.test/desktop-viewer/t1", model.botDesktop!!.viewer.url)

        model.reconnectDesktopViewer()
        assertEquals(DesktopLink(connecting = true), model.botDesktop!!.viewer)
        testScheduler.runCurrent()
        assertEquals("http://crumble.test/desktop-viewer/t2", model.botDesktop!!.viewer.url)

        transport.viewerCode = 409
        model.reconnectDesktopViewer()
        testScheduler.runCurrent()
        assertNull(model.botDesktop!!.viewer.url)
        assertTrue(model.botDesktop!!.viewer.error.isNotEmpty())

        transport.viewerCode = 201
        model.reconnectDesktopViewer()
        testScheduler.runCurrent()
        assertEquals("http://crumble.test/desktop-viewer/t4", model.botDesktop!!.viewer.url)
        assertEquals(4, transport.methods(VIEWER).size)
        model.closeBotDesktop()
    }

    @Test
    fun aLostPageAsksForReconnectUnlessItsLinkWasReplaced() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport()
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        testScheduler.runCurrent()
        val first = model.botDesktop!!.viewer.url!!
        model.reconnectDesktopViewer()
        testScheduler.runCurrent()

        model.desktopViewerLost(first)
        assertEquals("http://crumble.test/desktop-viewer/t2", model.botDesktop!!.viewer.url)

        model.desktopViewerLost(model.botDesktop!!.viewer.url!!)
        assertEquals(DesktopLink(error = DESKTOP_UNAVAILABLE), model.botDesktop!!.viewer)
        model.closeBotDesktop()
    }

    @Test
    fun aLateViewerResponseIsDroppedAfterLeaving() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport()
        val gate = CompletableDeferred<Unit>().also { transport.held[VIEWER] = it }
        val model = modelWith(transport, scope)
        model.openBotDesktop("a1")
        testScheduler.runCurrent()
        model.closeBotDesktop()
        transport.held.remove(VIEWER)
        model.openBotDesktop("a1")
        testScheduler.runCurrent()
        assertEquals("http://crumble.test/desktop-viewer/t1", model.botDesktop!!.viewer.url)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("http://crumble.test/desktop-viewer/t1", model.botDesktop!!.viewer.url)
        model.closeBotDesktop()
    }

    @Test
    fun aSleepingPreviewShowsNoViewerAndWakingBootsTheMachine() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport()
        val model = modelWith(transport, scope)
        model.watchDesktopPreview("a1")
        assertNull(model.desktopPreview!!.running)
        testScheduler.runCurrent()
        assertEquals(false, model.desktopPreview!!.running)
        assertFalse(model.desktopPreview!!.awake)
        assertEquals(emptyList<String>(), transport.methods(VIEWER) + transport.methods(PASSIVE))

        model.wakeDesktop()
        testScheduler.runCurrent()
        assertEquals(listOf("POST"), transport.methods(VIEWER))
        assertEquals("http://crumble.test/desktop-viewer/t1", model.desktopPreview!!.viewer.url)
        model.stopDesktopPreview()
    }

    @Test
    fun aRunningMachineIsWatchedPassivelyAndStoppingEndsThePreview() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport().also { it.running = true }
        val model = modelWith(transport, scope)
        model.watchDesktopPreview("a1")
        testScheduler.runCurrent()
        assertEquals(listOf("POST"), transport.methods(PASSIVE))
        assertEquals("http://crumble.test/desktop-viewer/t1", model.desktopPreview!!.viewer.url)

        testScheduler.advanceTimeBy(10_001)
        assertEquals(listOf("POST"), transport.methods(PASSIVE))

        transport.running = false
        testScheduler.advanceTimeBy(10_001)
        assertEquals(DesktopLink(), model.desktopPreview!!.viewer)
        assertFalse(model.desktopPreview!!.awake)

        model.stopDesktopPreview()
        val reads = transport.methods(STATUS).size
        testScheduler.advanceTimeBy(30_000)
        assertEquals(reads, transport.methods(STATUS).size)
    }

    @Test
    fun aFailedStatusReadKeepsTheLastAnswer() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport().also { it.running = true }
        val model = modelWith(transport, scope)
        model.watchDesktopPreview("a1")
        testScheduler.runCurrent()
        transport.statusCode = 503
        testScheduler.advanceTimeBy(10_001)
        assertEquals(true, model.desktopPreview!!.running)
        assertEquals("http://crumble.test/desktop-viewer/t1", model.desktopPreview!!.viewer.url)
        model.stopDesktopPreview()
    }

    @Test
    fun theOpenDesktopReplacesThePreviewUntilItCloses() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport().also { it.running = true }
        val model = modelWith(transport, scope)
        model.watchDesktopPreview("a1")
        testScheduler.runCurrent()

        model.openBotDesktop("a1")
        testScheduler.runCurrent()
        assertEquals(DesktopLink(), model.desktopPreview!!.viewer)

        model.closeBotDesktop()
        testScheduler.runCurrent()
        assertEquals(2, transport.methods(PASSIVE).size)
        assertTrue(model.desktopPreview!!.viewer.url != null)
        model.stopDesktopPreview()
    }

    @Test
    fun aLatePreviewLinkIsDroppedOnceStopped() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val transport = DesktopTransport().also { it.running = true }
        val gate = CompletableDeferred<Unit>().also { transport.held[PASSIVE] = it }
        val model = modelWith(transport, scope)
        model.watchDesktopPreview("a1")
        testScheduler.runCurrent()
        model.stopDesktopPreview()
        transport.held.remove(PASSIVE)
        model.watchDesktopPreview("a1")
        testScheduler.runCurrent()
        assertEquals("http://crumble.test/desktop-viewer/t1", model.desktopPreview!!.viewer.url)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("http://crumble.test/desktop-viewer/t1", model.desktopPreview!!.viewer.url)
        model.stopDesktopPreview()
    }

    @Test
    fun anExpiredSessionStopsFollowingEvents() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var opened = 0
        val transport = object : HttpTransport by DesktopTransport() {
            override suspend fun openStream(request: HttpRequestData): HttpStreamResponse {
                opened += 1
                return HttpStreamResponse(401, ByteArrayInputStream(ByteArray(0)))
            }
        }
        val model = AppModel(client = APIClient("http://crumble.test", "", transport), scope = scope)
        model.openBotDesktop("a1")
        settle { model.botDesktop?.takeError?.isNotEmpty() == true }
        testScheduler.advanceTimeBy(30_000)
        assertEquals(1, opened)
        model.closeBotDesktop()
    }
}
