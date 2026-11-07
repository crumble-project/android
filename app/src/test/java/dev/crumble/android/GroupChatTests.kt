package dev.crumble.android

import dev.crumble.android.generated.GroupActivity
import dev.crumble.android.generated.GroupChat
import dev.crumble.android.generated.GroupMemberActivity
import dev.crumble.android.model.ActivityEvent
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatHistoryResponse
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.GroupConversation
import dev.crumble.android.model.GroupDraft
import dev.crumble.android.model.GroupSnapshot
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageDeliveryState
import dev.crumble.android.model.MessageOrigin
import dev.crumble.android.model.MessageSource
import dev.crumble.android.model.NotificationContext
import dev.crumble.android.model.PendingAttachment
import dev.crumble.android.model.Route
import dev.crumble.android.model.groupReactions
import dev.crumble.android.model.groupReplyLabel
import dev.crumble.android.model.groupSourcesOf
import dev.crumble.android.model.groupStatusLine
import dev.crumble.android.model.isGroupRunning
import dev.crumble.android.model.isValidGroup
import dev.crumble.android.model.memberStatusLabel
import dev.crumble.android.model.membersOf
import dev.crumble.android.model.notification
import dev.crumble.android.model.shownInGroup
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.push.parsePushReplyData
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val G1 = "11111111-1111-4111-8111-111111111111"
private const val G2 = "22222222-2222-4222-8222-222222222222"
private const val BASE = "/v1/group-chats"
private const val RECONNECTING = "Reconnecting…"

private fun groupJson(id: String = G1, name: String = "Team", members: List<String> = listOf("a1", "a2")) =
    """{"id":"$id","name":"$name","memberIds":[${members.joinToString(",") { "\"$it\"" }}],"createdAt":"t","updatedAt":"t"}"""

private fun row(entry: Int, group: String = G1, role: String = "user", text: String = "m$entry", requestId: String? = null) =
    """{"id":"$group:$entry:0","entryId":"$group:$entry","role":"$role","content":"$text"${requestId?.let { ",\"requestId\":\"$it\"" } ?: ""}}"""

private fun snapshotJson(
    rows: List<String> = emptyList(),
    group: String = groupJson(),
    hasMore: Boolean = false,
    oldest: String? = null,
    members: String = "",
) = """{"messages":[${rows.joinToString(",")}],"hasMore":$hasMore,"oldestEntryId":${oldest?.let { "\"$it\"" } ?: "null"},"groupChat":$group,"activity":{"paused":false,"botTurns":0,"members":[$members]},"updatedAt":1}"""

private fun bodyOf(request: HttpRequestData) =
    CrumbleJson.parseToJsonElement(request.body!!.toString(StandardCharsets.UTF_8)).jsonObject

/** Answers by "METHOD path", holding back any request whose key is in `held`; streams end at once. */
private class GroupTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    val responses = mutableMapOf<String, Pair<Int, String>>(
        "GET $BASE" to (200 to "[${groupJson()}]"),
        "GET $BASE/$G1/chat" to (200 to snapshotJson()),
        "POST $BASE/$G1/messages" to (201 to """{"requestId":"r","entryId":"e"}"""),
        "POST $BASE/$G1/stop" to (204 to ""),
        "DELETE $BASE/$G1" to (204 to ""),
    )
    var bytes = ByteArray(0)
    var stream: Pair<Int, String> = 200 to ""
    val streams = mutableListOf<String>()
    var closedStreams = 0

    private fun key(request: HttpRequestData) = "${request.method} ${request.pathWithQuery}"

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        val key = key(request)
        held[key]?.await()
        if (bytes.isNotEmpty() && "/attachments/" in key) return HttpResponseData(200, bytes)
        val (status, body) = responses[key] ?: (500 to """{"error":"unscripted $key"}""")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse {
        streams.add(request.pathWithQuery)
        return HttpStreamResponse(stream.first, ByteArrayInputStream(stream.second.toByteArray(StandardCharsets.UTF_8))) { closedStreams += 1 }
    }

    fun sent(key: String) = requests.filter { key(it) == key }
}

private val bots = listOf(Agent(id = "a1", name = "Ada", enabled = true), Agent(id = "a2", name = "Kev", enabled = true))

private fun activity(vararg members: Pair<String, String>) =
    GroupActivity(false, 0.0, members.map { GroupMemberActivity(it.first, it.second, null) })

private fun snapshot(vararg ids: String, hasMore: Boolean = false, oldest: String? = null) = GroupSnapshot(
    ids.map { Message(id = it, entryId = it, role = "user") }, hasMore, oldest,
    GroupChat(G1, "Team", listOf("a1", "a2"), "t", "t"), activity(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatTests {
    private fun model(scope: TestScope, transport: GroupTransport) = AppModel(
        client = APIClient("http://example.com", "token", transport),
        scope = scope.backgroundScope,
    ).also { it.agents = bots }

    // Streams read on the IO dispatcher. Blocking here matters: a suspended test body lets virtual time run on.
    private fun TestScope.until(condition: () -> Boolean) {
        repeat(5_000) {
            runCurrent()
            if (condition()) return
            Thread.sleep(1)
        }
        fail("condition never held")
    }

    /** Opens G1, then waits out one stream: it sends the same snapshot and ends, so the model waits to reconnect. */
    private fun TestScope.opened(transport: GroupTransport, rows: List<String> = emptyList()): AppModel {
        transport.responses["GET $BASE/$G1/chat"] = 200 to snapshotJson(rows)
        transport.stream = 200 to "event: snapshot\ndata: ${snapshotJson(rows)}\n\n"
        val app = model(this, transport)
        app.groupChats = listOf(GroupChat(G1, "Team", listOf("a1", "a2"), "t", "t"))
        app.openGroupChat(G1)
        until { app.groupConversation?.streamStatus == RECONNECTING }
        return app
    }

    /** Lets the model reconnect once and receive `snapshot`. */
    private fun TestScope.nextStream(app: AppModel, transport: GroupTransport, snapshot: String) {
        val closed = transport.closedStreams
        transport.stream = 200 to "event: snapshot\ndata: $snapshot\n\n"
        testScheduler.advanceTimeBy(600)
        until { transport.closedStreams > closed && app.groupConversation?.streamStatus == RECONNECTING }
    }

    @Test
    fun everyOperationUsesItsContractPath() = runTest {
        val transport = GroupTransport().apply {
            responses["POST $BASE"] = 201 to groupJson()
            responses["GET $BASE/$G1"] = 200 to groupJson()
            responses["PATCH $BASE/$G1"] = 200 to groupJson(name = "New")
            responses["GET $BASE/$G1/history?limit=100&before=e%3A1"] = 200 to """{"messages":[],"hasMore":false,"oldestEntryId":null}"""
            stream = 404 to """{"error":"gone"}"""
            bytes = byteArrayOf(1, 2)
        }
        val api = APIClient("http://example.com", "token", transport)

        assertEquals(listOf(G1), api.groupChats().map { it.id })
        assertEquals(G1, api.groupChat(G1).id)
        api.createGroupChat("Team", listOf("a1", "a2"))
        assertEquals(setOf("name", "memberIds"), bodyOf(transport.sent("POST $BASE").single()).keys)
        assertEquals("New", api.updateGroupChat(G1, "New", listOf("a1", "a2")).name)
        assertEquals(setOf("name", "memberIds"), bodyOf(transport.sent("PATCH $BASE/$G1").single()).keys)
        api.deleteGroupChat(G1)
        assertEquals(G1, api.groupSnapshot(G1).groupChat.id)
        assertEquals(false, api.groupHistory(G1, "e:1", 100).hasMore)
        api.sendGroupMessage(G1, "hi", "r1", "e1")
        val sent = bodyOf(transport.sent("POST $BASE/$G1/messages").single())
        assertEquals("r1", sent["requestId"]!!.jsonPrimitive.content)
        assertEquals("e1", sent["replyTo"]!!.jsonPrimitive.content)
        assertArrayEquals(byteArrayOf(1, 2), api.groupAttachment(G1, "e:1", 0))
        assertEquals("/v1/group-chats/$G1/attachments/e%3A1/0", transport.requests.last().pathWithQuery)
        api.stopGroupChat(G1)
        val failure = runCatching { api.groupChatEvents(G1) {} }.exceptionOrNull()
        assertEquals(404, (failure as dev.crumble.android.network.APIError).statusCode)
        assertEquals(listOf("$BASE/$G1/events"), transport.streams)
    }

    @Test
    fun streamDeliversSnapshotsWithoutAnAgentAndSkipsHeartbeats() = runTest {
        val transport = GroupTransport().apply {
            stream = 200 to "event: heartbeat\ndata: {}\n\nevent: snapshot\ndata: ${snapshotJson(listOf(row(1)))}\n\n"
        }
        val received = mutableListOf<GroupSnapshot>()
        runCatching { APIClient("http://example.com", "token", transport).groupChatEvents(G1) { received.add(it) } }
        assertEquals(listOf("$G1:1:0"), received.single().messages.map { it.id })
    }

    @Test
    fun liveSnapshotsReachTheOpenChat() = runTest {
        val transport = GroupTransport()
        val app = opened(transport, listOf(row(1)))
        assertEquals(Route.GroupChat(G1), app.route)
        assertEquals(listOf("$G1:1:0"), app.groupConversation?.messages?.map { it.id })

        nextStream(app, transport, snapshotJson(listOf(row(1), row(2)), members = """{"agentId":"a1","status":"running","error":null}"""))

        assertEquals(listOf("$G1:1:0", "$G1:2:0"), app.groupConversation?.messages?.map { it.id })
        assertTrue(isGroupRunning(app.groupConversation?.activity))
    }

    @Test
    fun refreshOnGroupRouteDoesNotOpenABotChat() = runTest {
        val transport = GroupTransport()
        transport.responses["GET /v1/agents"] = 200 to """[${agentFixture("a1")}]"""
        val app = opened(transport)

        app.refresh()
        runCurrent()

        assertEquals(Route.GroupChat(G1), app.route)
        assertNotNull(app.groupConversation)
    }

    @Test
    fun aDeletedGroupLeavesTheChat() = runTest {
        val transport = GroupTransport().apply { responses["GET $BASE/$G1/chat"] = 404 to """{"error":"gone"}""" }
        val app = model(this, transport)
        app.agents = emptyList()
        app.groupChats = listOf(GroupChat(G1, "Team", listOf("a1", "a2"), "t", "t"))
        app.updateGroupComposer(G1) { it.copy(draft = "kept") }
        app.openGroupChat(G1)
        runCurrent()

        assertNull(app.route)
        assertNull(app.groupConversation)
        assertTrue(app.groupChats.isEmpty())
        assertEquals("", app.groupComposer(G1).draft)
    }

    @Test
    fun aStreamThatEndsReconnectsAndReportsIt() = runTest {
        val transport = GroupTransport()
        val app = opened(transport)
        assertEquals(RECONNECTING, app.liveStatus)
    }

    @Test
    fun anExpiredSessionStopsFollowing() = runTest {
        val transport = GroupTransport()
        val app = opened(transport)
        transport.stream = 401 to """{"error":"no"}"""
        testScheduler.advanceTimeBy(1_100)
        until { app.groupConversation?.streamStatus == "Disconnected" }
        assertEquals("Authentication failed. Sign in again in Settings.", app.errorText)
    }

    @Test
    fun aGroupDeletedElsewhereEndsTheStreamAndLeaves() = runTest {
        val transport = GroupTransport()
        val app = opened(transport)
        app.agents = emptyList()
        transport.stream = 404 to """{"error":"gone"}"""
        testScheduler.advanceTimeBy(1_100)
        until { app.groupConversation == null }
        assertNull(app.route)
        assertTrue(app.groupChats.isEmpty())
    }

    @Test
    fun openingFailureShowsTheErrorAndTheStreamStillRecovers() = runTest {
        val transport = GroupTransport().apply {
            responses["GET $BASE/$G1/chat"] = 500 to """{"error":"boom"}"""
            stream = 200 to "event: snapshot\ndata: ${snapshotJson()}\n\n"
        }
        val app = model(this, transport)
        app.openGroupChat(G1)
        until { app.groupConversation?.group != null }
        assertNull(app.groupConversation?.loadError)
        assertEquals("Team", app.groupConversation?.group?.name)
    }

    @Test
    fun aLateSnapshotReadIsDroppedAfterLeaving() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport().apply { held["GET $BASE/$G1/chat"] = gate }
        val app = model(this, transport)
        app.openGroupChat(G1)
        runCurrent()
        app.openPanel(Route.Tasks)
        gate.complete(Unit)
        runCurrent()

        assertEquals(Route.Tasks, app.route)
        assertNull(app.groupConversation)
        assertTrue(transport.streams.isEmpty())
    }

    @Test
    fun createShowsTheGroupAtOnceAndSwapsInTheServerRow() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport().apply {
            held["POST $BASE"] = gate
            responses["POST $BASE"] = 201 to groupJson()
            stream = 200 to "event: snapshot\ndata: ${snapshotJson()}\n\n"
        }
        val app = model(this, transport)
        app.groupDraft = GroupDraft(name = " Team ", memberIDs = listOf("a1", "a2"))
        app.saveGroup()

        assertNull(app.groupDraft)
        assertTrue(app.groupChats.single().id.startsWith("pending:"))
        assertEquals("Team", app.groupChats.single().name)

        app.refresh()
        runCurrent()
        assertTrue("a refresh must not drop the pending row", app.groupChats.any { it.id.startsWith("pending:") })

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(G1), app.groupChats.map { it.id })
        assertEquals(Route.GroupChat(G1), app.route)
    }

    @Test
    fun aLateCreateKeepsNewerNavigation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport().apply {
            held["POST $BASE"] = gate
            responses["POST $BASE"] = 201 to groupJson()
        }
        val app = model(this, transport)
        app.groupDraft = GroupDraft(name = "Team", memberIDs = listOf("a1", "a2"))
        app.saveGroup()
        app.openPanel(Route.Activity)

        gate.complete(Unit)
        runCurrent()

        assertEquals(Route.Activity, app.route)
        assertEquals(listOf(G1), app.groupChats.map { it.id })
    }

    @Test
    fun aFailedCreateRemovesTheRowAndRestoresTheForm() = runTest {
        val transport = GroupTransport().apply { responses["POST $BASE"] = 400 to """{"error":"List each bot once"}""" }
        val app = model(this, transport)
        val draft = GroupDraft(name = "Team", memberIDs = listOf("a1", "a2"))
        app.groupDraft = draft
        app.saveGroup()
        runCurrent()

        assertTrue(app.groupChats.isEmpty())
        assertEquals(draft, app.groupDraft)
        assertEquals("List each bot once", app.errorText)
    }

    @Test
    fun anInvalidFormDoesNotSave() = runTest {
        val transport = GroupTransport()
        val app = model(this, transport)
        app.groupDraft = GroupDraft(name = "  ", memberIDs = listOf("a1", "a2"))
        app.saveGroup()
        app.groupDraft = GroupDraft(name = "Team", memberIDs = listOf("a1"))
        app.saveGroup()
        runCurrent()
        assertTrue(transport.requests.isEmpty())
        assertTrue(app.groupChats.isEmpty())
    }

    @Test
    fun editAppliesAtOnceAndASnapshotCannotRevertIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport()
        val app = opened(transport)
        transport.held["PATCH $BASE/$G1"] = gate
        transport.responses["PATCH $BASE/$G1"] = 200 to groupJson(name = "Saved", members = listOf("a1", "a2"))
        app.groupDraft = GroupDraft(groupID = G1, name = "Renamed", memberIDs = listOf("a1", "a2"))
        app.saveGroup()

        assertEquals("Renamed", app.groupChats.single().name)
        assertEquals("Renamed", app.groupConversation?.group?.name)

        nextStream(app, transport, snapshotJson())
        assertEquals(2, transport.streams.size)
        assertEquals("Renamed", app.groupConversation?.group?.name)

        gate.complete(Unit)
        runCurrent()
        assertEquals("Saved", app.groupChats.single().name)
        assertEquals("Saved", app.groupConversation?.group?.name)
    }

    @Test
    fun aFailedEditRestoresTheGroupAndTheForm() = runTest {
        val transport = GroupTransport()
        val app = opened(transport)
        transport.responses["PATCH $BASE/$G1"] = 500 to """{"error":"nope"}"""
        val draft = GroupDraft(groupID = G1, name = "Renamed", memberIDs = listOf("a1", "a2"))
        app.groupDraft = draft
        app.saveGroup()
        runCurrent()

        assertEquals("Team", app.groupChats.single().name)
        assertEquals("Team", app.groupConversation?.group?.name)
        assertEquals(draft, app.groupDraft)
        assertEquals("nope", app.errorText)
    }

    @Test
    fun theEditorMovesToTheServerVersionUnlessTheOwnerTyped() = runTest {
        val transport = GroupTransport()
        val app = opened(transport)
        transport.responses["GET $BASE/$G1"] = 200 to groupJson(name = "Elsewhere", members = listOf("a1", "a2", "a3"))

        app.openGroupEditor(G1)
        assertEquals("Team", app.groupDraft?.name)
        runCurrent()
        assertEquals("Elsewhere", app.groupDraft?.name)
        assertEquals(listOf("a1", "a2", "a3"), app.groupDraft?.memberIDs)

        val gate = CompletableDeferred<Unit>()
        transport.held["GET $BASE/$G1"] = gate
        app.openGroupEditor(G1)
        app.groupDraft = app.groupDraft!!.copy(name = "Mine")
        gate.complete(Unit)
        runCurrent()
        assertEquals("Mine", app.groupDraft?.name)
    }

    @Test
    fun memberTogglesStopAtTheLimit() = runTest {
        val app = model(this, GroupTransport())
        app.groupDraft = GroupDraft(memberIDs = listOf("1", "2", "3", "4", "5", "6"))
        app.toggleGroupMember("7", true)
        assertEquals(6, app.groupDraft!!.memberIDs.size)
        app.toggleGroupMember("1", false)
        app.toggleGroupMember("7", true)
        assertEquals(listOf("2", "3", "4", "5", "6", "7"), app.groupDraft!!.memberIDs)
    }

    @Test
    fun deleteLeavesAtOnceAndFailureBringsItBack() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport()
        val app = opened(transport)
        transport.held["DELETE $BASE/$G1"] = gate
        transport.responses["DELETE $BASE/$G1"] = 500 to """{"error":"nope"}"""
        app.agents = emptyList()

        app.deleteGroup(G1)
        assertTrue(app.groupChats.isEmpty())
        assertNull(app.route)
        assertNull(app.groupConversation)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(G1), app.groupChats.map { it.id })
        assertEquals(Route.GroupChat(G1), app.route)
        assertNotNull(app.groupConversation)
        assertEquals("nope", app.errorText)
    }

    @Test
    fun aConfirmedDeleteForgetsTheGroupsLocalState() = runTest {
        val transport = GroupTransport()
        val app = opened(transport)
        app.agents = emptyList()
        app.updateGroupComposer(G1) { it.copy(draft = "unsent") }
        app.deleteGroup(G1)
        runCurrent()
        assertTrue(app.groupChats.isEmpty())
        assertEquals("", app.groupComposer(G1).draft)
    }

    @Test
    fun aSentMessageWaitsForItsSnapshotWithoutDuplicates() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport()
        val app = opened(transport, listOf(row(1)))
        val reply = app.groupConversation!!.messages.single()
        transport.held["POST $BASE/$G1/messages"] = gate
        app.updateGroupComposer(G1) { it.copy(draft = " hello ", replyTo = reply) }
        app.sendGroupMessage()
        runCurrent()

        assertEquals("", app.groupComposer(G1).draft)
        assertNull(app.groupComposer(G1).replyTo)
        val pending = app.groupOutgoing[G1]!!.single()
        assertEquals("hello", pending.text)
        assertEquals(MessageDeliveryState.sending, pending.deliveryState)
        assertEquals("m1", pending.replyPreview)
        val requestID = pending.requestId!!
        assertEquals(reply.entryId, bodyOf(transport.sent("POST $BASE/$G1/messages").single())["replyTo"]!!.jsonPrimitive.content)

        // The confirming snapshot can beat the POST's response.
        nextStream(app, transport, snapshotJson(listOf(row(1), row(2, requestId = requestID))))
        assertTrue(app.groupOutgoing[G1].orEmpty().isEmpty())
        assertEquals(2, app.groupConversation!!.messages.size)

        gate.complete(Unit)
        runCurrent()
        assertTrue(app.groupOutgoing[G1].orEmpty().isEmpty())
        assertNull(app.groupConversation?.sendError)
    }

    @Test
    fun aTimedOutPostDoesNotReportAMessageTheSnapshotConfirmed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport()
        val app = opened(transport)
        transport.held["POST $BASE/$G1/messages"] = gate
        transport.responses["POST $BASE/$G1/messages"] = 500 to """{"error":"timed out"}"""
        app.updateGroupComposer(G1) { it.copy(draft = "hello") }
        app.sendGroupMessage()
        runCurrent()
        val requestID = app.groupOutgoing[G1]!!.single().requestId!!

        nextStream(app, transport, snapshotJson(listOf(row(1, requestId = requestID))))
        gate.complete(Unit)
        runCurrent()

        assertNull(app.groupConversation?.sendError)
        assertTrue(app.groupOutgoing[G1].orEmpty().isEmpty())
    }

    @Test
    fun aFailedSendCanBeRetriedWithTheSameRequestAndFiles() = runTest {
        val transport = GroupTransport()
        val app = opened(transport)
        transport.responses["POST $BASE/$G1/messages"] = 500 to """{"error":"down"}"""
        val file = PendingAttachment(name = "a.png", mimeType = "image/png", data = byteArrayOf(1))
        app.updateGroupComposer(G1) { it.copy(draft = "hello") }
        app.addGroupAttachment(G1, file)
        app.sendGroupMessage()
        runCurrent()

        val failed = app.groupOutgoing[G1]!!.single()
        assertEquals(MessageDeliveryState.failed, failed.deliveryState)
        assertEquals("Message was not confirmed: down", app.groupConversation?.sendError)
        assertArrayEquals(byteArrayOf(1), app.groupAttachmentImageBytes(failed, 0))

        transport.responses["POST $BASE/$G1/messages"] = 201 to """{"requestId":"r","entryId":"e"}"""
        app.retryGroupMessage(failed)
        assertEquals(MessageDeliveryState.sending, app.groupOutgoing[G1]!!.single().deliveryState)
        assertNull(app.groupConversation?.sendError)
        runCurrent()

        val sends = transport.sent("POST $BASE/$G1/messages").map { bodyOf(it) }
        assertEquals(2, sends.size)
        assertEquals(sends[0]["requestId"], sends[1]["requestId"])
        assertEquals("a.png", sends[1]["attachments"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun outgoingMessagesAndDraftsStayScopedToTheirGroup() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport().apply {
            held["POST $BASE/$G1/messages"] = gate
            responses["GET $BASE/$G2/chat"] = 200 to snapshotJson(group = groupJson(id = G2, name = "Other"))
        }
        val app = opened(transport)
        app.updateGroupComposer(G1) { it.copy(draft = "to one") }
        app.sendGroupMessage()
        app.updateGroupComposer(G1) { it.copy(draft = "half written") }
        runCurrent()

        transport.stream = 200 to "event: snapshot\ndata: ${snapshotJson(group = groupJson(id = G2, name = "Other"))}\n\n"
        app.openGroupChat(G2)
        until { app.groupConversation?.group != null }
        assertEquals("Other", app.groupConversation?.group?.name)
        assertTrue(app.groupOutgoing[G2].orEmpty().isEmpty())
        assertEquals("", app.groupComposer(G2).draft)

        transport.responses["POST $BASE/$G1/messages"] = 500 to """{"error":"down"}"""
        gate.complete(Unit)
        runCurrent()
        assertEquals(MessageDeliveryState.failed, app.groupOutgoing[G1]!!.single().deliveryState)
        assertNull(app.groupConversation?.sendError)

        assertEquals("half written", app.groupComposer(G1).draft)
    }

    @Test
    fun stopShowsPendingAndReportsFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = GroupTransport()
        val app = opened(transport)
        transport.held["POST $BASE/$G1/stop"] = gate
        transport.responses["POST $BASE/$G1/stop"] = 500 to """{"error":"nope"}"""

        app.stopGroupChat()
        assertTrue("group-stop:$G1" in app.pendingMutations)
        assertEquals("Stopping…", app.notice)
        app.stopGroupChat()
        runCurrent()
        assertEquals("a pending stop is not sent twice", 1, transport.sent("POST $BASE/$G1/stop").size)

        gate.complete(Unit)
        runCurrent()
        assertFalse("group-stop:$G1" in app.pendingMutations)
        assertEquals("nope", app.errorText)
    }

    private fun TestScope.openedWithOlderRows(transport: GroupTransport): AppModel {
        val live = snapshotJson(listOf(row(5)), hasMore = true, oldest = "$G1:5")
        transport.responses["GET $BASE/$G1/chat"] = 200 to live
        transport.stream = 200 to "event: snapshot\ndata: $live\n\n"
        val app = model(this, transport)
        app.openGroupChat(G1)
        until { app.groupConversation?.streamStatus == RECONNECTING }
        return app
    }

    private val historyPage = "GET $BASE/$G1/history?limit=100&before=11111111-1111-4111-8111-111111111111%3A5"

    @Test
    fun historyPrependsOlderRowsOnce() = runTest {
        val transport = GroupTransport()
        val app = openedWithOlderRows(transport)
        transport.responses[historyPage] = 200 to """{"messages":[${row(4)}],"hasMore":false,"oldestEntryId":"$G1:4"}"""
        val gate = CompletableDeferred<Unit>()
        transport.held[historyPage] = gate

        app.loadEarlierGroupMessages()
        assertTrue(app.groupConversation!!.loadingOlder)
        app.loadEarlierGroupMessages()
        runCurrent()
        assertEquals(1, transport.sent(historyPage).size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("$G1:4:0", "$G1:5:0"), app.groupConversation!!.messages.map { it.id })
        assertFalse(app.groupConversation!!.hasMore)
    }

    @Test
    fun aLateHistoryPageIsDroppedAfterLeaving() = runTest {
        val transport = GroupTransport()
        val app = openedWithOlderRows(transport)
        transport.responses[historyPage] = 200 to """{"messages":[${row(4)}],"hasMore":false,"oldestEntryId":"$G1:4"}"""
        val gate = CompletableDeferred<Unit>()
        transport.held[historyPage] = gate
        transport.stream = 200 to "event: snapshot\ndata: ${snapshotJson(listOf(row(5)), hasMore = true, oldest = "$G1:5")}\n\n"

        app.loadEarlierGroupMessages()
        runCurrent()
        app.openGroupChat(G1)
        runCurrent()
        gate.complete(Unit)
        until { app.groupConversation?.group != null }

        val conversation = app.groupConversation!!
        assertEquals(listOf("$G1:5:0"), conversation.messages.map { it.id })
        assertFalse(conversation.loadingOlder)
    }

    @Test
    fun aFailedHistoryPageCanBeLoadedAgain() = runTest {
        val transport = GroupTransport()
        val app = openedWithOlderRows(transport)

        app.loadEarlierGroupMessages()
        runCurrent()
        assertFalse(app.groupConversation!!.loadingOlder)
        assertTrue(app.errorText.isNotEmpty())
        assertTrue(app.groupConversation!!.hasMore)
    }

    @Test
    fun snapshotsKeepOlderPagesAndTheirCursor() {
        val paged = GroupConversation(G1).merging(snapshot("g:5:0", hasMore = true, oldest = "g:5"))
            .adding(ChatHistoryResponse(listOf(Message(id = "g:4:0", role = "user")), true, "g:4"))
        val next = paged.merging(snapshot("g:5:0", "g:6:0", hasMore = true, oldest = "g:5"))
        assertEquals(listOf("g:4:0", "g:5:0", "g:6:0"), next.messages.map { it.id })
        assertEquals("g:4", next.oldestEntryID)
    }

    @Test
    fun aSnapshotBeyondAGapRestartsFromTheLivePage() {
        val paged = GroupConversation(G1).merging(snapshot("g:5:0", hasMore = true, oldest = "g:5"))
            .adding(ChatHistoryResponse(listOf(Message(id = "g:4:0", role = "user")), true, "g:4"))
        val resumed = paged.merging(snapshot("g:90:0", hasMore = true, oldest = "g:90"))
        assertEquals(listOf("g:90:0"), resumed.messages.map { it.id })
        assertEquals("g:90", resumed.oldestEntryID)
        assertFalse(resumed.olderLoaded)
    }

    @Test
    fun groupImagesComeFromTheGroupAttachmentRoute() = runTest {
        val transport = GroupTransport().apply { bytes = byteArrayOf(9) }
        val app = opened(transport)
        app.loadGroupAttachmentImage("$G1:1", 0)
        assertEquals("/v1/group-chats/$G1/attachments/11111111-1111-4111-8111-111111111111%3A1/0", transport.requests.last().pathWithQuery)
        assertArrayEquals(byteArrayOf(9), app.groupAttachmentImageBytes(Message(entryId = "$G1:1"), 0))
    }

    @Test
    fun aFailedListKeepsItsRowsAndSaysSo() = runTest {
        val transport = GroupTransport()
        val app = model(this, transport)
        app.loadGroupChats()
        runCurrent()
        assertEquals(listOf(G1), app.groupChats.map { it.id })

        transport.responses["GET $BASE"] = 503 to """{"error":"Group chats are unavailable"}"""
        app.loadGroupChats()
        runCurrent()
        assertTrue(app.groupsFailed)
        assertEquals(listOf(G1), app.groupChats.map { it.id })
        assertEquals("", app.errorText)
    }

    @Test
    fun botMessagesWithoutBlocksShowWhileToolActivityDoesNot() {
        val bot = Message(id = "g:1:0", entryId = "g:1", role = "assistant", content = JsonPrimitive("hi"))
        val tool = Message(id = "g:2:0", role = "toolResult", content = JsonPrimitive("Reacted"))
        val file = Message(id = "g:3:0", role = "user", content = JsonPrimitive(""), attachments = listOf(dev.crumble.android.model.AttachmentInfo(name = "a")))
        assertFalse("the bot chat filter needs blocks", ChatRow.shownInChat(bot))
        assertTrue(shownInGroup(bot))
        assertFalse(shownInGroup(tool))
        assertTrue(shownInGroup(file))
        val rows = ChatRow.rows(listOf(bot, tool, file), null, false, ::shownInGroup)
        assertEquals(listOf("g:1:0", "g:3:0"), rows.map { it.id })
    }

    @Test
    fun reactionsSourcesAndLabelsFollowTheWeb() {
        val emoji = buildJsonObject { put("emoji", "👍") }
        val reactions = groupReactions(
            listOf(
                Message(role = "toolResult", toolName = "react", replyTo = "e1", origin = MessageOrigin("bot", "Ada", "a1"), toolDetails = emoji),
                Message(role = "toolResult", toolName = "react", replyTo = "e1", toolDetails = emoji),
                Message(role = "toolResult", toolName = "react", replyTo = "e2", isError = true, toolDetails = emoji),
                Message(role = "toolResult", toolName = "react", toolDetails = emoji),
            ),
        )
        assertEquals(mapOf("e1" to listOf("Ada 👍", "👍")), reactions)

        val sources = groupSourcesOf(Message(sources = listOf(MessageSource("https://example.com/a/", "A"), MessageSource("ftp://x", "X"))))
        assertEquals(mapOf("example.com/a" to "A"), sources)
        assertNull(groupSourcesOf(Message()))

        assertEquals("You", groupReplyLabel(Message(role = "user")))
        assertEquals("Ada", groupReplyLabel(Message(role = "assistant", origin = MessageOrigin("bot", "Ada"))))
        assertEquals("a bot", groupReplyLabel(Message(role = "assistant")))
    }

    @Test
    fun theHeaderSaysWhoIsDoingWhat() {
        val group = GroupChat(G1, "Team", listOf("a1", "gone", "a2"), "t", "t")
        assertEquals(bots, membersOf(group, bots))
        assertEquals("Ada, Kev", groupStatusLine(bots, null))
        assertEquals("Ada is answering · Kev answers next", groupStatusLine(bots, activity("a1" to "running", "a2" to "queued")))
        assertEquals("Ada, Kev", groupStatusLine(bots, activity("a1" to "disabled", "a2" to "idle")))
        assertEquals("Ada is disabled and skipped", memberStatusLabel(bots[0], "disabled"))
        assertEquals("Ada is here", memberStatusLabel(bots[0], "idle"))
        assertTrue(isGroupRunning(activity("a1" to "queued")))
        assertFalse(isGroupRunning(activity("a1" to "idle")))
        assertFalse(isGroupRunning(null))
    }

    @Test
    fun formValidityMatchesTheServerLimits() {
        assertTrue(isValidGroup("Team", listOf("1", "2")))
        assertTrue(isValidGroup("Team", List(6) { "$it" }))
        assertFalse(isValidGroup("Team", listOf("1")))
        assertFalse(isValidGroup("Team", List(7) { "$it" }))
        assertFalse(isValidGroup("   ", listOf("1", "2")))
    }

    @Test
    fun groupRepliesNameTheGroupAndStayQuietOnlyInThatGroup() {
        val reply = ActivityEvent(type = "reply", agentId = "a1", agentName = "Ada", origin = "group", preview = "hi", groupId = G1, groupName = "Team")
        assertEquals("Ada in Team", notification(reply, NotificationContext(true, null))?.title)
        assertEquals("Ada in a group", notification(reply.copy(groupName = ""), NotificationContext(true, null))?.title)
        assertNull(notification(reply, NotificationContext(true, Route.GroupChat(G1))))
        assertNotNull("the bot's own chat is not the group", notification(reply, NotificationContext(true, Route.AgentChat("a1"))))
        assertNotNull(notification(reply, NotificationContext(false, Route.GroupChat(G1))))
    }

    @Test
    fun pushRepliesCarryTheirGroup() {
        val event = parsePushReplyData(
            mapOf("type" to "reply", "eventId" to "e", "serverId" to "s", "agentId" to "a1", "agentName" to "Ada", "preview" to "hi", "groupId" to G1, "groupName" to "Team"),
        )
        assertEquals(G1, event?.groupId)
        assertEquals("Team", event?.groupName)
        val direct = parsePushReplyData(mapOf("type" to "reply", "eventId" to "e", "serverId" to "s", "agentId" to "a1", "agentName" to "Ada", "preview" to "hi"))
        assertNull(direct?.groupId)
    }
}
