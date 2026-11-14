package dev.crumble.android

import dev.crumble.android.model.AppModel
import dev.crumble.android.model.AttachmentInfo
import dev.crumble.android.model.Message
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.MessageDeliveryState
import dev.crumble.android.model.PendingAttachment
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.domain
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

private const val FILE = "33333333-3333-4333-8333-333333333333"
private const val OTHER_FILE = "44444444-4444-4444-8444-444444444444"
private const val GROUP = "11111111-1111-4111-8111-111111111111"
private const val BOT = "/v1/agents/a1"
private const val GROUP_PATH = "/v1/group-chats/$GROUP"

internal fun uploadedFileFixture(id: String = FILE) =
    """{"file":{"id":"$id","name":"image.png","mimeType":"image/png","size":4,"createdAt":"2026-11-14T00:00:00Z"},"existed":false}"""

private class FileTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    var uploadCount = 0
    var failUpload: Int? = null
    var sendStatus = 202
    var sendError = "busy"
    var heldUpload: CompletableDeferred<Unit>? = null
    var heldDownload: CompletableDeferred<Unit>? = null
    var snapshot = snapshotFixture(ChatSnapshot(agentId = "a1"))
    var groupMessages = "[]"
    val downloads = byteArrayOf(0, -1, 10, 127)

    fun sends() = requests.filter { it.method == "POST" && it.pathWithQuery.endsWith("/messages") }
    fun uploads() = requests.filter { it.method == "POST" && it.pathWithQuery.endsWith("/files") }

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        val path = request.pathWithQuery
        if (request.method == "POST" && path.endsWith("/files")) {
            uploadCount++
            val number = uploadCount
            heldUpload?.await()
            return if (number == failUpload) HttpResponseData(503, """{"error":"upload failed"}""".toByteArray())
            else HttpResponseData(201, uploadedFileFixture(if (number == 1) FILE else OTHER_FILE).toByteArray())
        }
        if (request.method == "GET" && "/files/" in path) {
            heldDownload?.await()
            return HttpResponseData(200, downloads)
        }
        val (status, body) = when {
            request.method == "POST" && path.endsWith("/messages") -> sendStatus to
                if (sendStatus >= 400) """{"error":"$sendError"}"""
                else if (path.startsWith("/v1/group-chats/")) """{"requestId":"r","entryId":"e"}""" else executionFixture
            path.startsWith("$BOT/chat") -> 200 to snapshot
            path == "$GROUP_PATH/chat" -> 200 to """{"messages":$groupMessages,"hasMore":false,"oldestEntryId":null,"groupChat":{"id":"$GROUP","name":"Team","memberIds":["a1","a2"],"createdAt":"t","updatedAt":"t"},"activity":{"paused":false,"botTurns":0,"members":[]},"updatedAt":1}"""
            path == "/v1/agents" -> 200 to "[${agentFixture("a1")}]"
            path.endsWith("/memories") || path == "/v1/group-chats" -> 200 to "[]"
            else -> 404 to ""
        }
        return HttpResponseData(status, body.toByteArray())
    }

    override suspend fun openStream(request: HttpRequestData) = HttpStreamResponse(503, ByteArrayInputStream(byteArrayOf()))
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileCompatibilityTests {
    private fun TestScope.open(transport: FileTransport): AppModel = AppModel(
        client = APIClient("https://crumble.test", "owner", transport),
        scope = backgroundScope,
        transport = transport,
    ).also {
        it.selectAgent("a1")
        runCurrent()
        it.applySnapshot(ChatSnapshot(agentId = "a1"))
    }

    private fun image(name: String = "image.png") = PendingAttachment(
        name = name, mimeType = "image/png", data = byteArrayOf(0, -1, 10, 127),
    )

    private fun body(request: HttpRequestData) = CrumbleJson.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun botSendUploadsRawBytesAndUsesFileIds() = runTest {
        val transport = FileTransport()
        val app = open(transport)
        app.draft = "Look"
        app.addAttachment(image("über %.png"))
        app.sendMessage()
        assertEquals(MessageDeliveryState.sending, app.messages.single().deliveryState)
        runCurrent()

        assertEquals(1, transport.uploads().size)
        val upload = transport.uploads().single()
        assertEquals("$BOT/files", upload.pathWithQuery)
        assertArrayEquals(byteArrayOf(0, -1, 10, 127), upload.body)
        assertEquals("image/png", upload.headers.entries.single { it.key.equals("content-type", true) }.value)
        assertEquals("%C3%BCber%20%25.png", upload.headers.entries.single { it.key.equals("x-file-name", true) }.value)
        assertEquals("Bearer owner", upload.headers["Authorization"])
        assertEquals(120_000, upload.timeoutMs)
        val sent = body(transport.sends().single())
        assertEquals(JsonArray(listOf(JsonPrimitive(FILE))), sent["files"])
        assertFalse(sent.containsKey("attachments"))
        assertEquals("Look", sent["content"]!!.jsonPrimitive.content)
        assertEquals("application/json", transport.sends().single().headers["Content-Type"])
    }

    @Test
    fun partialUploadFailureRetainsTheDraftAndReusesSuccessfulFiles() = runTest {
        val transport = FileTransport().apply { failUpload = 2 }
        val app = open(transport)
        app.draft = "Look"
        app.attachments = listOf(image("one.png"), image("two.png"))
        app.sendMessage()
        runCurrent()
        assertEquals(2, transport.uploads().size)
        assertTrue(transport.sends().isEmpty())
        val failed = app.messages.single()
        assertEquals(MessageDeliveryState.failed, failed.deliveryState)
        assertEquals("Look", app.draft)
        assertEquals(2, app.attachments.size)

        app.sendMessage()
        runCurrent()
        assertEquals(3, transport.uploads().size)
        val sent = body(transport.sends().single())
        assertEquals(failed.requestId, sent["requestId"]!!.jsonPrimitive.content)
        assertEquals(listOf(FILE, OTHER_FILE), sent["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, app.messages.size)
    }

    @Test
    fun connectionChangeDuringUploadCannotSendOrRestoreTheOldDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FileTransport().apply { heldUpload = gate }
        val app = open(transport)
        app.draft = "Old connection"
        app.addAttachment(image())
        app.sendMessage()
        runCurrent()
        assertEquals(1, transport.uploads().size)
        app.signOut()
        runCurrent()
        app.draft = "New draft"
        gate.complete(Unit)
        runCurrent()
        assertTrue(transport.sends().isEmpty())
        assertEquals("New draft", app.draft)
    }

    private fun fileSnapshot(files: String): String {
        val fields = CrumbleJson.parseToJsonElement(snapshotFixture(ChatSnapshot(agentId = "a1"))).jsonObject.toMutableMap()
        fields["messages"] = CrumbleJson.parseToJsonElement("""[{"id":"m1","entryId":"a1:1","role":"user","content":"Look","replyReferences":[],"files":$files}]""")
        return JsonObject(fields).toString()
    }

    @Test
    fun transcriptFilesDecodeFromHttpAndEventsAndFetchById() = runTest {
        val transport = FileTransport().apply {
            snapshot = fileSnapshot("""[{"id":"$FILE","name":"image.png","mimeType":"image/png","size":4},{"id":"$OTHER_FILE","deleted":true}]""")
        }
        val api = APIClient("https://crumble.test", "owner", transport)
        val message = api.chat("a1").messages!!.single()
        assertEquals(2, message.attachments.orEmpty().size)
        assertTrue(message.attachments!![0].isImage)
        assertFalse(message.attachments[1].isImage)
        assertEquals(FILE, message.attachments[0].id)
        assertTrue(message.attachments[1].deleted)
        val event = CrumbleJson.decodeFromString<dev.crumble.android.generated.SnapshotEventPayload>(transport.snapshot).domain()
        assertEquals(message.attachments, event.messages!!.single().attachments)
        val app = open(transport)
        app.applySnapshot(api.chat("a1"))
        app.loadAttachmentImage("a1:1", 0)
        assertEquals("$BOT/files/$FILE", transport.requests.last().pathWithQuery)
        assertArrayEquals(transport.downloads, app.attachmentImageBytes(message, 0))
        val count = transport.requests.size
        app.loadAttachmentImage("a1:1", 1)
        assertEquals(count, transport.requests.size)
    }

    @Test
    fun ambiguousSendFailureRetriesWithTheSameFilesAndRequestId() = runTest {
        val transport = FileTransport().apply { sendStatus = 503 }
        val app = open(transport)
        app.draft = "Look"
        app.addAttachment(image())
        app.sendMessage()
        runCurrent()
        val failed = app.messages.single()
        assertEquals(MessageDeliveryState.failed, failed.deliveryState)
        transport.sendStatus = 202
        app.retryMessage(failed)
        runCurrent()
        assertEquals(1, transport.uploads().size)
        assertEquals(2, transport.sends().size)
        assertEquals(body(transport.sends()[0]), body(transport.sends()[1]))
    }

    @Test
    fun definitelyDeletedUploadIsReuploadedOnExplicitRetry() = runTest {
        val transport = FileTransport().apply {
            sendStatus = 400
            sendError = "image.png was deleted from the library"
        }
        val app = open(transport)
        app.draft = "Look"
        app.addAttachment(image())
        app.sendMessage()
        runCurrent()
        val failed = app.messages.single()
        transport.sendStatus = 202
        app.retryMessage(failed)
        runCurrent()
        assertEquals(2, transport.uploads().size)
        val sent = transport.sends().map(::body)
        assertEquals(sent[0]["requestId"], sent[1]["requestId"])
        assertEquals(JsonArray(listOf(JsonPrimitive(FILE))), sent[0]["files"])
        assertEquals(JsonArray(listOf(JsonPrimitive(OTHER_FILE))), sent[1]["files"])
    }

    @Test
    fun navigatingDuringUploadKeepsTheSendWithItsOriginalBot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FileTransport().apply { heldUpload = gate }
        val app = open(transport)
        app.draft = "For a1"
        app.addAttachment(image())
        app.sendMessage()
        runCurrent()
        app.selectAgent("a2")
        app.draft = "For a2"
        gate.complete(Unit)
        runCurrent()
        assertEquals("$BOT/messages", transport.sends().single().pathWithQuery)
        assertEquals("For a2", app.draft)
        assertEquals("a2", app.selectedAgentID)
    }

    @Test
    fun deletionHidesCachedImagesAndDoesNotFetchAgain() = runTest {
        val transport = FileTransport().apply {
            snapshot = fileSnapshot("""[{"id":"$FILE","name":"image.png","mimeType":"image/png","size":4}]""")
        }
        val api = APIClient("https://crumble.test", "owner", transport)
        val app = open(transport)
        app.applySnapshot(api.chat("a1"))
        app.loadAttachmentImage("a1:1", 0)
        assertArrayEquals(transport.downloads, app.attachmentImageBytes(app.messages.single(), 0))
        transport.snapshot = fileSnapshot("""[{"id":"$FILE","deleted":true}]""")
        app.applySnapshot(api.chat("a1"))
        assertNull(app.attachmentImageBytes(app.messages.single(), 0))
        val count = transport.requests.size
        app.loadAttachmentImage("a1:1", 0)
        assertEquals(count, transport.requests.size)
    }

    @Test
    fun deletionDuringDownloadDiscardsItsLateBytes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FileTransport().apply {
            snapshot = fileSnapshot("""[{"id":"$FILE","name":"image.png","mimeType":"image/png","size":4}]""")
            heldDownload = gate
        }
        val api = APIClient("https://crumble.test", "owner", transport)
        val app = open(transport)
        app.applySnapshot(api.chat("a1"))
        val download = backgroundScope.launch { app.loadAttachmentImage("a1:1", 0) }
        runCurrent()
        assertEquals("$BOT/files/$FILE", transport.requests.last().pathWithQuery)
        transport.snapshot = fileSnapshot("""[{"id":"$FILE","deleted":true}]""")
        app.applySnapshot(api.chat("a1"))
        gate.complete(Unit)
        download.join()
        assertTrue(app.attachmentBytes.isEmpty())
        assertNull(app.attachmentImageBytes(app.messages.single(), 0))
    }

    private fun sharedFileMessage(id: String, deleted: Boolean = false) = Message(
        id = id, entryId = id, role = "user", content = JsonPrimitive("Look"),
        attachments = listOf(if (deleted) AttachmentInfo(id = FILE, deleted = true)
            else AttachmentInfo(id = FILE, name = "image.png", mimeType = "image/png", size = 4)),
    )

    @Test
    fun partialDeletionInvalidatesEveryRetainedReferenceToTheFile() = runTest {
        val transport = FileTransport()
        val app = open(transport)
        app.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(sharedFileMessage("old"), sharedFileMessage("recent"))))
        app.loadAttachmentImage("old", 0)
        assertArrayEquals(transport.downloads, app.attachmentImageBytes(app.messages.first(), 0))
        app.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(sharedFileMessage("recent", deleted = true))))
        assertEquals(2, app.messages.size)
        assertTrue(app.messages.all { it.attachments!!.single().deleted })
        assertTrue(app.attachmentBytes.isEmpty())
        assertNull(app.attachmentImageBytes(app.messages.first(), 0))
        val count = transport.requests.size
        app.loadAttachmentImage("old", 0)
        assertEquals(count, transport.requests.size)
    }

    @Test
    fun partialDeletionDiscardsAnOlderReferencesInflightDownload() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FileTransport().apply { heldDownload = gate }
        val app = open(transport)
        app.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(sharedFileMessage("old"), sharedFileMessage("recent"))))
        val download = backgroundScope.launch { app.loadAttachmentImage("old", 0) }
        runCurrent()
        app.applySnapshot(ChatSnapshot(agentId = "a1", messages = listOf(sharedFileMessage("recent", deleted = true))))
        gate.complete(Unit)
        download.join()
        assertTrue(app.attachmentBytes.isEmpty())
        assertNull(app.attachmentImageBytes(app.messages.first(), 0))
    }

    @Test
    fun groupUploadsRetainFilesAndRequestIdAcrossRetry() = runTest {
        val transport = FileTransport().apply { failUpload = 2 }
        val app = open(transport)
        app.openGroupChat(GROUP)
        runCurrent()
        assertNotNull(app.groupConversation?.group)
        app.updateGroupComposer(GROUP) { it.copy(draft = "Look", attachments = listOf(image("one.png"), image("two.png"))) }
        app.sendGroupMessage()
        runCurrent()
        assertEquals(2, transport.uploads().size)
        assertTrue(transport.sends().isEmpty())
        val failed = app.groupOutgoing[GROUP]!!.single()
        assertEquals(MessageDeliveryState.failed, failed.deliveryState)
        assertArrayEquals(image().data, app.groupAttachmentImageBytes(failed, 0))
        app.retryGroupMessage(failed)
        runCurrent()
        assertEquals(3, transport.uploads().size)
        assertTrue(transport.uploads().all { it.pathWithQuery == "$GROUP_PATH/files" })
        val send = transport.sends().single()
        assertEquals("$GROUP_PATH/messages", send.pathWithQuery)
        assertEquals(failed.requestId, body(send)["requestId"]!!.jsonPrimitive.content)
        assertEquals(listOf(FILE, OTHER_FILE), body(send)["files"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun groupHistoryFetchesByFileIdAndSkipsDeletedFiles() = runTest {
        val transport = FileTransport().apply {
            groupMessages = """[{"id":"m1","entryId":"g:1","role":"user","content":"Look","replyReferences":[],"files":[{"id":"$FILE","name":"image.png","mimeType":"image/png","size":4},{"id":"$OTHER_FILE","deleted":true}]}]"""
        }
        val app = open(transport)
        app.openGroupChat(GROUP)
        runCurrent()
        val message = app.groupConversation!!.messages.single()
        assertEquals(listOf(FILE, OTHER_FILE), message.attachments!!.map { it.id })
        app.loadGroupAttachmentImage("g:1", 0)
        assertEquals("$GROUP_PATH/files/$FILE", transport.requests.last().pathWithQuery)
        assertArrayEquals(transport.downloads, app.groupAttachmentImageBytes(message, 0))
        val count = transport.requests.size
        app.loadGroupAttachmentImage("g:1", 1)
        assertEquals(count, transport.requests.size)
    }
}
