package dev.crumble.android

import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.push.ChatAttention
import dev.crumble.android.push.ChatAttentionChat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAttentionTests {
    private val bot = ChatAttentionChat("bot", "a1")
    private val group = ChatAttentionChat("group", "g1")

    private class Recording : HttpTransport {
        val requests = mutableListOf<HttpRequestData>()
        var respond: suspend (HttpRequestData) -> Int = { 204 }
        override suspend fun execute(request: HttpRequestData): HttpResponseData {
            requests.add(request)
            return HttpResponseData(respond(request), ByteArray(0))
        }
        override suspend fun openStream(request: HttpRequestData): HttpStreamResponse = error("unexpected stream")
        fun api(token: String = "session") = APIClient("https://example.test", token, this)
        val seen get() = requests.filter { it.method == "POST" }
        val presence get() = requests.filter { it.method == "PUT" }
    }

    private fun HttpRequestData.json() = CrumbleJson.parseToJsonElement(body!!.decodeToString()) as JsonObject
    private fun HttpRequestData.ids() = (json().getValue("eventIds") as JsonArray).map { it.jsonPrimitive.content }

    @Test
    fun presenceNeedsForegroundMatchingMountButNoDeviceOrPermission() = runTest {
        val transport = Recording()
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        runCurrent()
        assertTrue(transport.requests.isEmpty())
        controller.setDisplayedChat(group)
        runCurrent()
        assertTrue(transport.requests.isEmpty())
        controller.setDisplayedChat(bot)
        runCurrent()
        assertEquals("{\"chat\":{\"kind\":\"bot\",\"id\":\"a1\"}}", transport.presence.single().body!!.decodeToString())
        controller.update(api, false, bot)
        runCurrent()
        assertEquals("{\"chat\":null}", transport.presence.last().body!!.decodeToString())
        controller.update(api, true, group)
        controller.setDisplayedChat(group)
        runCurrent()
        assertEquals("group", (transport.presence.last().json().getValue("chat") as JsonObject).getValue("kind").jsonPrimitive.content)
        assertTrue(transport.requests.all { it.pathWithQuery.startsWith("/v1/chat-attention/") })
        controller.update(transport.api(" "), true, bot)
        controller.setDisplayedChat(bot)
        runCurrent()
        assertEquals("{\"chat\":null}", transport.presence.last().body!!.decodeToString())
    }

    @Test
    fun heartbeatCollapsesPendingRefreshBehindSerializedClear() = runTest {
        val transport = Recording()
        val gate = CompletableDeferred<Unit>()
        transport.respond = { if (transport.requests.size == 1) gate.await(); 204 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, transport.presence.size)
        controller.update(api, false, bot)
        gate.complete(Unit)
        runCurrent()
        assertTrue(transport.presence.drop(1).all { it.body!!.decodeToString() == "{\"chat\":null}" })
        val count = transport.presence.size
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(count, transport.presence.size)
        controller.update(api, true, bot)
        runCurrent()
        val resumed = transport.presence.size
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(resumed + 1, transport.presence.size)
    }

    @Test
    fun rotationKeepsEarnedUnsentSeenButRejectsOldCallbacksAndAccounts() = runTest {
        val transport = Recording()
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        val old = controller.viewId
        controller.displayed(api, old, bot, listOf("earned"))
        controller.newView()
        assertNotEquals(old, controller.viewId)
        controller.setDisplayedChat(bot)
        controller.displayed(api, old, bot, listOf("stale"))
        controller.displayed(transport.api(), controller.viewId, bot, listOf("other-api"))
        controller.displayed(api, controller.viewId, group, listOf("other-chat"))
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("earned"), transport.seen.single().ids())
        val previous = controller.viewId
        val newApi = transport.api()
        controller.update(newApi, true, bot)
        controller.setDisplayedChat(bot)
        controller.displayed(api, previous, bot, listOf("old-account"))
        controller.displayed(newApi, controller.viewId, bot, listOf("new-account"))
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("new-account"), transport.seen.last().ids())
    }

    @Test
    fun batchesDeduplicateAndRetryOriginalChatAfterNavigationAndBackground() = runTest {
        val transport = Recording()
        var attempts = 0
        transport.respond = { if (it.method == "POST" && attempts++ == 0) 503 else 204 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        val ids = (1..205).map { "e$it" }
        controller.displayed(api, controller.viewId, bot, ids + ids)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(100, transport.seen.single().ids().size)
        controller.update(api, false, group)
        controller.setDisplayedChat(group)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(100, 100, 100, 5), transport.seen.map { it.ids().size })
        assertTrue(transport.seen.all { (it.json().getValue("chat") as JsonObject).getValue("id").jsonPrimitive.content == bot.id })
        assertEquals(ids, transport.seen.drop(1).flatMap { it.ids() })
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        controller.displayed(api, controller.viewId, bot, ids)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(4, transport.seen.size)
    }

    @Test
    fun atomicRejectionSplitsAndDropsOnlyInvalidSingles() = runTest {
        for (status in listOf(400, 404)) {
            val transport = Recording()
            transport.respond = { if (it.method == "POST" && "bad" in it.ids()) status else 204 }
            val api = transport.api()
            val controller = ChatAttention(backgroundScope)
            controller.update(api, true, bot)
            controller.setDisplayedChat(bot)
            controller.displayed(api, controller.viewId, bot, listOf("good", "bad", "also-good"))
            advanceTimeBy(101)
            runCurrent()
            assertEquals(listOf(listOf("good", "bad", "also-good"), listOf("good"), listOf("bad"), listOf("also-good")), transport.seen.map { it.ids() })
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(4, transport.seen.size)
        }
    }

    @Test
    fun viewRotationPreservesSubmittedRetryAndReconnectCancelsIt() = runTest {
        val transport = Recording()
        transport.respond = { if (it.method == "POST") 503 else 204 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        controller.displayed(api, controller.viewId, bot, listOf("earned"))
        advanceTimeBy(101)
        runCurrent()
        controller.newView()
        controller.update(api, false, group)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(listOf("earned"), listOf("earned")), transport.seen.map { it.ids() })
        controller.update(transport.api(), true, group)
        controller.setDisplayedChat(group)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, transport.seen.size)
    }

    @Test
    fun failedPresenceBackoffIsSupersededByClearAndCallbacksRequireMountAndForeground() = runTest {
        val transport = Recording()
        transport.respond = { if (it.method == "PUT" && transport.presence.size == 1) 503 else 204 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.displayed(api, controller.viewId, bot, listOf("unmounted"))
        controller.setDisplayedChat(bot)
        runCurrent()
        controller.update(api, false, bot)
        controller.displayed(api, controller.viewId, bot, listOf("background"))
        runCurrent()
        assertEquals(2, transport.presence.size)
        assertEquals("{\"chat\":null}", transport.presence.last().body!!.decodeToString())
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(transport.seen.isEmpty())
        assertEquals(2, transport.presence.size)
    }

    @Test
    fun unauthorizedStopsSeenRetries() = runTest {
        val transport = Recording()
        transport.respond = { if (it.method == "POST") 401 else 204 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        controller.displayed(api, controller.viewId, bot, listOf("e1"))
        advanceTimeBy(101)
        runCurrent()
        controller.displayed(api, controller.viewId, bot, listOf("e2"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, transport.seen.size)
    }

    @Test
    fun retiredOfflineViewsExpireAndDisconnectStopsEveryOldView() = runTest {
        val transport = Recording()
        transport.respond = { 503 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        runCurrent()
        val firstView = controller.viewId
        controller.newView()
        controller.setDisplayedChat(bot)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        val retiredCount = transport.presence.count { it.pathWithQuery.endsWith(firstView) }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(retiredCount, transport.presence.count { it.pathWithQuery.endsWith(firstView) })
        controller.newView()
        controller.setDisplayedChat(bot)
        runCurrent()
        val closing = launch { controller.disconnect(api) }
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertTrue(closing.isCompleted)
        val count = transport.requests.size
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(count, transport.requests.size)
    }

    @Test
    fun disconnectRejectsSameApiDuringClearAndAfterNullUntilDistinctConnection() = runTest {
        val transport = Recording()
        val clear = CompletableDeferred<Unit>()
        transport.respond = { if (it.method == "PUT" && it.body!!.decodeToString() == "{\"chat\":null}") clear.await(); 204 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        runCurrent()
        val closing = launch { controller.disconnect(api) }
        runCurrent()
        assertFalse(closing.isCompleted)
        val count = transport.requests.size
        controller.newView()
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        controller.displayed(api, controller.viewId, bot, listOf("during-signout"))
        advanceTimeBy(101)
        runCurrent()
        assertEquals(count, transport.requests.size)
        controller.update(null, false, null)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        controller.displayed(api, controller.viewId, bot, listOf("after-null"))
        advanceTimeBy(101)
        runCurrent()
        assertEquals(count, transport.requests.size)
        val replacement = transport.api()
        controller.update(replacement, true, group)
        controller.setDisplayedChat(group)
        controller.displayed(replacement, controller.viewId, group, listOf("replacement"))
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("replacement"), transport.seen.single().ids())
        assertEquals("group", (transport.presence.last().json().getValue("chat") as JsonObject).getValue("kind").jsonPrimitive.content)
        clear.complete(Unit)
        runCurrent()
        assertTrue(closing.isCompleted)
        val resumed = transport.presence.size
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(resumed + 1, transport.presence.size)
    }

    @Test
    fun disconnectSerializesOldClearInvalidatesImmediatelyAndBoundsWait() = runTest {
        val transport = Recording()
        val gate = CompletableDeferred<Unit>()
        transport.respond = { if (it.method == "PUT" && transport.presence.size == 1) gate.await(); if (it.method == "POST") 503 else 204 }
        val api = transport.api()
        val controller = ChatAttention(backgroundScope)
        controller.update(api, true, bot)
        controller.setDisplayedChat(bot)
        val old = controller.viewId
        controller.displayed(api, old, bot, listOf("e1"))
        advanceTimeBy(101)
        runCurrent()
        val closing = launch { controller.disconnect(api) }
        runCurrent()
        controller.displayed(api, old, bot, listOf("late"))
        assertFalse(closing.isCompleted)
        assertNotEquals(old, controller.viewId)
        gate.complete(Unit)
        runCurrent()
        assertTrue(closing.isCompleted)
        assertEquals("{\"chat\":null}", transport.presence.last().body!!.decodeToString())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, transport.seen.size)

        val blocked = Recording()
        blocked.respond = { CompletableDeferred<Unit>().await(); 204 }
        val blockedApi = blocked.api()
        controller.update(blockedApi, true, bot)
        controller.setDisplayedChat(bot)
        runCurrent()
        val timeout = launch { controller.disconnect(blockedApi) }
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertTrue(timeout.isCompleted)
    }
}
