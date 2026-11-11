package dev.crumble.android.network

import dev.crumble.android.generated.AssignSkillBotRequest
import dev.crumble.android.generated.BrowseSkillRepositoryRequest
import dev.crumble.android.generated.Capabilities
import dev.crumble.android.generated.CreateEventSourceRequest
import dev.crumble.android.generated.CreateEventSourceResponse
import dev.crumble.android.generated.CreateSkillRequest
import dev.crumble.android.generated.CreateSkillRequestDocumentsItem
import dev.crumble.android.generated.DesktopControl
import dev.crumble.android.generated.DesktopViewer
import dev.crumble.android.generated.GroupChat
import dev.crumble.android.generated.InstallSkillsRequest
import dev.crumble.android.generated.LearnedChange
import dev.crumble.android.generated.PendingSkillUpdate
import dev.crumble.android.generated.PutDesktopControlRequest
import dev.crumble.android.generated.ScriptRun
import dev.crumble.android.generated.SkillCheck
import dev.crumble.android.generated.SkillListing
import dev.crumble.android.generated.SkillPackage
import dev.crumble.android.model.Agent
import dev.crumble.android.model.ActivityFeedResponse
import dev.crumble.android.model.ChatHistoryResponse
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.CrumblePatchJson
import dev.crumble.android.model.Execution
import dev.crumble.android.model.GroupSnapshot
import dev.crumble.android.model.JobKind
import dev.crumble.android.model.JobRef
import dev.crumble.android.model.JobSummary
import dev.crumble.android.model.MCPServer
import dev.crumble.android.model.Memory
import dev.crumble.android.model.ModelSummary
import dev.crumble.android.model.OutgoingAttachment
import dev.crumble.android.generated.OptionalField
import dev.crumble.android.generated.PreviewReasoningRequest
import dev.crumble.android.generated.PreviewScheduleRequest
import dev.crumble.android.model.EventSource
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.SharedSkill
import dev.crumble.android.model.nilIfEmpty
import dev.crumble.android.push.PushInfo
import dev.crumble.android.push.PushPresence
import dev.crumble.android.push.PushRegistration
import dev.crumble.android.push.PushRegistrationResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

const val CHANGES_PAGE = 50
private const val SCRIPT_RUNS_PAGE = 20L
// The server reads GitHub while these run.
private const val GITHUB_TIMEOUT_MS = 60_000

data class APIError(
    val method: String,
    val path: String,
    val statusCode: Int,
    val body: String,
) : Exception("api $method $path ($statusCode): $body")

class SSEClosedError : Exception("server-sent event stream closed")

class SignInError(message: String) : Exception(message)

/** Who a session belongs to, from Better Auth rather than the /v1 contract. */
data class AccountSession(val id: String, val theme: String? = null)

const val INVITE_HEADER = "x-crumble-invite"

data class HttpRequestData(
    val method: String,
    val url: String,
    val pathWithQuery: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val timeoutMs: Int = 20_000,
)

data class HttpResponseData(
    val statusCode: Int,
    val body: ByteArray,
)

data class HttpStreamResponse(
    val statusCode: Int,
    val stream: InputStream,
    val onClose: () -> Unit = {},
)

interface HttpTransport {
    suspend fun execute(request: HttpRequestData): HttpResponseData
    suspend fun openStream(request: HttpRequestData): HttpStreamResponse
}

object UrlConnectionTransport : HttpTransport {
    override suspend fun execute(request: HttpRequestData): HttpResponseData = withContext(Dispatchers.IO) {
        val conn = openConnection(request)
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else (conn.errorStream ?: ByteArrayInputStream(ByteArray(0)))
            val bytes = stream.use { it.readBytes() }
            HttpResponseData(statusCode = status, body = bytes)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse = withContext(Dispatchers.IO) {
        val conn = openConnection(request)
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else (conn.errorStream ?: ByteArrayInputStream(ByteArray(0)))
        HttpStreamResponse(
            statusCode = status,
            stream = stream,
            onClose = {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                conn.disconnect()
            },
        )
    }

    private fun openConnection(request: HttpRequestData): HttpURLConnection {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = minOf(request.timeoutMs, 20_000)
            readTimeout = request.timeoutMs
            useCaches = false
            doInput = true
        }
        setMethod(conn, request.method)
        for ((k, v) in request.headers) {
            conn.setRequestProperty(k, v)
        }
        if (request.body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(request.body) }
        }
        return conn
    }

    private fun setMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.requestMethod = method
        } catch (_: ProtocolException) {
            try {
                val field = HttpURLConnection::class.java.getDeclaredField("method")
                field.isAccessible = true
                field.set(conn, method)
            } catch (_: Exception) {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", method)
            }
        }
    }
}

/** The Crumble server's HTTP API. Every request carries the bearer token when one is set. */
class APIClient(
    baseURL: String,
    val token: String,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    val baseURL: String = baseURL.trimEnd('/')

    private fun makeRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        timeoutMs: Int = 20_000,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpRequestData {
        val fullUrl = this.baseURL + path
        // Validate URL format
        val parsed = try {
            URI(fullUrl)
        } catch (e: Exception) {
            throw IllegalArgumentException("Bad URL: $fullUrl", e)
        }
        if (parsed.scheme.isNullOrEmpty() || parsed.host.isNullOrEmpty()) {
            throw IllegalArgumentException("Bad URL: $fullUrl")
        }
        val headers = LinkedHashMap(extraHeaders)
        if (token.isNotEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }
        if (body != null) {
            headers["Content-Type"] = "application/json"
        }
        return HttpRequestData(
            method = method,
            url = fullUrl,
            pathWithQuery = path,
            headers = headers,
            body = body,
            timeoutMs = timeoutMs,
        )
    }

    private suspend fun send(
        method: String,
        path: String,
        body: ByteArray? = null,
        timeoutMs: Int = 20_000,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val request = makeRequest(method, path, body, timeoutMs, extraHeaders)
        val response = transport.execute(request)
        if (response.statusCode !in 200..299) {
            val prefixLen = minOf(response.body.size, 8 shl 10)
            val text = String(response.body, 0, prefixLen, StandardCharsets.UTF_8).trim()
            throw APIError(method = method, path = path, statusCode = response.statusCode, body = text)
        }
        return response.body
    }

    companion object {
        fun escape(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%7E", "~")
    }

    private val operations = dev.crumble.android.generated.ContractOperations(object : dev.crumble.android.generated.ContractBoundary {
        override val json = CrumblePatchJson
        override fun escape(value: String) = APIClient.escape(value)
        override fun request(method: String, path: String, body: ByteArray?, timeoutMs: Int) = makeRequest(method, path, body, timeoutMs)
        override suspend fun send(method: String, path: String, body: ByteArray?, timeoutMs: Int) = this@APIClient.send(method, path, body, timeoutMs)
    })

    private inline fun <reified T> request(fields: Map<String, JsonElement>): T =
        CrumblePatchJson.decodeFromJsonElement(JsonObject(fields))

    private fun strings(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { (key, value) -> key to JsonPrimitive(value) }

    // Better Auth's routes, so they sit outside the /v1 contract.
    suspend fun signIn(email: String, password: String): String {
        val fields = strings("email" to email, "password" to password)
        val bytes = try {
            send("POST", "/api/auth/sign-in/email", CrumbleJson.encodeToString(JsonObject(fields)).toByteArray(StandardCharsets.UTF_8))
        } catch (e: APIError) {
            throw if (e.statusCode == 401) SignInError("Invalid email or password.") else e
        }
        return sessionToken(bytes) ?: throw SignInError("Sign-in returned no session.")
    }

    /** Creates the account; an invite lets it through while sign-up is closed. */
    suspend fun signUp(name: String, email: String, password: String, invite: String): String {
        val fields = strings("email" to email, "password" to password, "name" to name)
        val bytes = try {
            send(
                "POST",
                "/api/auth/sign-up/email",
                CrumbleJson.encodeToString(JsonObject(fields)).toByteArray(StandardCharsets.UTF_8),
                extraHeaders = if (invite.isEmpty()) emptyMap() else mapOf(INVITE_HEADER to invite),
            )
        } catch (e: APIError) {
            throw SignInError(authMessage(e) ?: friendlyError(e))
        }
        return sessionToken(bytes) ?: throw SignInError("Sign-up returned no session.")
    }

    /** Revokes this client's session on the server. */
    suspend fun signOut() {
        send("POST", "/api/auth/sign-out", "{}".toByteArray(StandardCharsets.UTF_8))
    }

    suspend fun accountSession(): AccountSession {
        val path = "/api/auth/get-session"
        val bytes = send("GET", path)
        val session = CrumbleJson.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
        val user = session?.get("user") as? JsonObject
        val id = (user?.get("id") as? JsonPrimitive)?.contentOrNull
            ?: throw APIError(method = "GET", path = path, statusCode = 401, body = "")
        return AccountSession(id, theme = (user["theme"] as? JsonPrimitive)?.contentOrNull)
    }

    suspend fun updateTheme(theme: String) {
        val body = CrumbleJson.encodeToString(JsonObject(strings("theme" to theme)))
        send("POST", "/api/auth/update-user", body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sessionToken(bytes: ByteArray): String? {
        val response = CrumbleJson.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
        return (response?.get("token") as? JsonPrimitive)?.contentOrNull
    }

    // Better Auth answers with `message`, where the /v1 routes use `error`.
    private fun authMessage(error: APIError): String? = try {
        val body = CrumbleJson.parseToJsonElement(error.body) as? JsonObject
        (body?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    suspend fun heldActions(agentID: String) = operations.listAgentHeldActions(agentID)
    suspend fun approveHeldAction(agentID: String, id: String) { operations.approveAgentHeldAction(agentID, id) }
    suspend fun declineHeldAction(agentID: String, id: String, reason: String) {
        operations.declineAgentHeldAction(request(if (reason.isEmpty()) emptyMap() else strings("reason" to reason)), agentID, id)
    }
    suspend fun previewReasoning(provider: String, model: String, thinkingLevel: String) = operations.previewReasoning(
        PreviewReasoningRequest(provider, model, OptionalField.Present(thinkingLevel)),
    )
    suspend fun previewSchedule(cron: String?, runAt: String?, eventSourceID: String?, timezone: String) = operations.previewSchedule(
        PreviewScheduleRequest(
            OptionalField.Present(cron),
            OptionalField.Present(runAt),
            OptionalField.Present(eventSourceID), timezone,
        ),
    )
    suspend fun codingJobs(agentID: String) = operations.listCodingJobs(agentID).jobs
    suspend fun answerCodingJob(agentID: String, job: String, questionID: String, text: String) {
        operations.answerCodingJob(request(strings("questionId" to questionID, "text" to text)), agentID, job)
    }
    suspend fun botRequests(agentID: String) = operations.listBotRequests(agentID)
    suspend fun jobs(agentID: String, kind: JobKind): List<JobSummary> = when (kind) {
        JobKind.coding -> operations.listCodingJobs(agentID).jobs.map { it.domain() }
        JobKind.desktop -> operations.listDesktopJobs(agentID).jobs.map { it.domain() }
    }
    suspend fun jobHistory(agentID: String, job: JobRef, before: String?, limit: Int): dev.crumble.android.model.JobHistoryPage = when (job.kind) {
        JobKind.coding -> operations.getCodingJobHistory(agentID, job.name, limit.toLong(), before)
        JobKind.desktop -> operations.getDesktopJobHistory(agentID, job.name, limit.toLong(), before)
    }.domain()
    suspend fun approveBotRequest(agentID: String, id: String, proposal: Map<String, JsonElement>? = null): Agent =
        operations.approveBotRequest(request(proposal?.let { mapOf("proposal" to JsonObject(it)) }.orEmpty()), agentID, id).agent.domain()
    suspend fun declineBotRequest(agentID: String, id: String) { operations.declineBotRequest(agentID, id) }

    suspend fun callRequests(agentID: String) = operations.listCallRequests(agentID)
    suspend fun approveCallRequest(agentID: String, id: String) = operations.approveCallRequest(agentID, id)
    suspend fun declineCallRequest(agentID: String, id: String) = operations.declineCallRequest(agentID, id)
    suspend fun callRequestTranscript(agentID: String, id: String, limit: Int): ChatHistoryResponse =
        operations.getCallRequestTranscript(agentID, id, limit.toLong()).domain()

    suspend fun agents(): List<Agent> = operations.listAgents().map { it.domain() }

    suspend fun createAgent(agent: Agent): Agent = operations.createAgent(agent.wire()).domain()

    suspend fun updateAgent(id: String, patch: Map<String, JsonElement>): Agent =
        requireNotNull(operations.updateAgent(request(patch), id)).domain()

    suspend fun deleteAgent(id: String) = operations.deleteAgent(id)
    suspend fun duplicateAgent(id: String): Agent = requireNotNull(operations.duplicateAgent(id)).domain()
    suspend fun chat(agentID: String, limit: Int? = null): ChatSnapshot = operations.getChat(agentID, limit?.toLong()).domain()
    suspend fun chatHistory(agentID: String, before: String, limit: Int): ChatHistoryResponse =
        operations.getChatHistory(agentID, before = before.nilIfEmpty, limit = limit.takeIf { it > 0 }?.toLong()).domain()

    private fun messageFields(content: String, requestID: String, replyTo: String, attachments: List<OutgoingAttachment>): Map<String, JsonElement> {
        val fields = strings("content" to content, "requestId" to requestID).toMutableMap()
        replyTo.nilIfEmpty?.let { fields["replyTo"] = JsonPrimitive(it) }
        if (attachments.isNotEmpty()) fields["attachments"] = CrumbleJson.encodeToJsonElement(ListSerializer(OutgoingAttachment.serializer()), attachments)
        return fields
    }

    suspend fun sendChatMessage(agentID: String, content: String, requestID: String, replyTo: String, attachments: List<OutgoingAttachment> = emptyList()) {
        val fields = messageFields(content, requestID, replyTo, attachments) + ("timezone" to JsonPrimitive(ZoneId.systemDefault().id))
        operations.sendMessage(request(fields), agentID, timeoutMs = if (attachments.isEmpty()) 20_000 else 120_000)
    }

    suspend fun attachment(agentID: String, entryID: String, index: Int): ByteArray = operations.getAttachment(agentID, entryID, index.toLong())
    suspend fun abortChat(agentID: String) = operations.abortChat(agentID)

    suspend fun chatEvents(agentID: String, limit: Int? = null, onEvent: suspend (ChatSnapshot) -> Unit): Nothing =
        events(operations.streamChatEvents(agentID, limit?.toLong(), timeoutMs = 24 * 60 * 60 * 1000), SSEParser()) {
            onEvent(CrumblePatchJson.decodeFromString<dev.crumble.android.generated.SnapshotEventPayload>(it).domain())
        }

    suspend fun groupChats(): List<GroupChat> = operations.listGroupChats()
    suspend fun groupChat(id: String): GroupChat = operations.getGroupChat(id)
    suspend fun createGroupChat(name: String, memberIDs: List<String>): GroupChat =
        operations.createGroupChat(dev.crumble.android.generated.CreateGroupChatRequest(name, memberIDs))
    suspend fun updateGroupChat(id: String, name: String, memberIDs: List<String>): GroupChat =
        operations.updateGroupChat(dev.crumble.android.generated.UpdateGroupChatRequest(OptionalField.Present(name), OptionalField.Present(memberIDs)), id)
    suspend fun deleteGroupChat(id: String) = operations.deleteGroupChat(id)
    suspend fun groupSnapshot(id: String): GroupSnapshot = operations.getGroupChatSnapshot(id).domain()
    suspend fun groupHistory(id: String, before: String, limit: Int): ChatHistoryResponse =
        operations.getGroupChatHistory(id, limit.toLong(), before).domain()
    suspend fun sendGroupMessage(id: String, content: String, requestID: String, replyTo: String, attachments: List<OutgoingAttachment> = emptyList()) {
        operations.sendGroupMessage(request(messageFields(content, requestID, replyTo, attachments)), id, timeoutMs = if (attachments.isEmpty()) 20_000 else 120_000)
    }
    suspend fun groupAttachment(id: String, entryID: String, index: Int): ByteArray = operations.getGroupAttachment(id, entryID, index.toLong())
    suspend fun stopGroupChat(id: String) = operations.stopGroupChat(id)

    suspend fun groupChatEvents(id: String, onEvent: suspend (GroupSnapshot) -> Unit): Nothing =
        events(operations.streamGroupChatEvents(id, timeoutMs = 24 * 60 * 60 * 1000), SSEParser(accepting = setOf("snapshot"), requiresAgent = false)) {
            onEvent(CrumblePatchJson.decodeFromString<dev.crumble.android.generated.GroupChatSnapshot>(it).domain())
        }

    suspend fun desktopRunning(agentID: String): Boolean = operations.getDesktopStatus(agentID).running
    suspend fun createDesktopViewer(agentID: String, passive: Boolean = false): DesktopViewer =
        operations.createDesktopViewer(agentID, if (passive) "true" else null)
    suspend fun setDesktopHolder(agentID: String, holder: String): DesktopControl =
        operations.putDesktopControl(PutDesktopControlRequest(holder), agentID)

    /** The viewer page, which needs no sign-in: its one-use link is the credential. */
    fun desktopViewerURL(viewer: DesktopViewer): String = URI(baseURL).resolve(viewer.path).toString()

    suspend fun desktopEvents(agentID: String, onEvent: suspend (DesktopControl) -> Unit): Nothing =
        events(operations.streamDesktopEvents(agentID, timeoutMs = 24 * 60 * 60 * 1000), SSEParser(accepting = setOf("control"), requiresAgent = false)) {
            onEvent(CrumbleJson.decodeFromString<DesktopControl>(it))
        }

    suspend fun activityEvents(onEvent: suspend (dev.crumble.android.model.ActivityEvent) -> Unit): Nothing =
        events(operations.streamActivityEvents(timeoutMs = 24 * 60 * 60 * 1000), SSEParser(accepting = setOf("activity"))) {
            onEvent(CrumblePatchJson.decodeFromString<dev.crumble.android.generated.ActivityEventPayload>(it).domain())
        }

    private suspend fun events(
        request: HttpRequestData,
        parser: SSEParser,
        onEvent: suspend (String) -> Unit,
    ): Nothing {
        val path = request.pathWithQuery
        val response = transport.openStream(request)
        try {
            if (response.statusCode !in 200..299) {
                val buf = ByteArrayOutputStream()
                val stream = response.stream
                var b = stream.read()
                while (b != -1 && buf.size() < (8 shl 10)) {
                    buf.write(b)
                    b = stream.read()
                }
                throw APIError(
                    method = "GET",
                    path = path,
                    statusCode = response.statusCode,
                    body = String(buf.toByteArray(), StandardCharsets.UTF_8).trim(),
                )
            }
            val line = ByteArrayOutputStream()
            val stream = response.stream
            while (true) {
                currentCoroutineContext().ensureActive()
                val byte = withContext(Dispatchers.IO) { stream.read() }
                if (byte == -1) break
                if (byte != '\n'.code) {
                    line.write(byte)
                    continue
                }
                val bytes = line.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                val lineStr = String(bytes, 0, len, StandardCharsets.UTF_8)
                line.reset()
                val payload = parser.feed(lineStr)
                if (payload != null) {
                    onEvent(payload)
                }
            }
            currentCoroutineContext().ensureActive()
            val remaining = String(line.toByteArray(), StandardCharsets.UTF_8)
            parser.feed(remaining)?.let { onEvent(it) }
            parser.feed("")?.let { onEvent(it) }
            throw SSEClosedError()
        } catch (e: CancellationException) {
            throw e
        } finally {
            response.onClose()
        }
    }

    suspend fun memories(agentID: String?): List<Memory> =
        (if (agentID == null) operations.listSharedMemories() else operations.listAgentMemories(agentID)).map { it.domain() }
    suspend fun addMemory(agentID: String?, content: String): Memory =
        (if (agentID == null) operations.createSharedMemory(request(strings("content" to content)))
        else operations.createAgentMemory(request(strings("content" to content)), agentID)).domain()
    suspend fun updateMemory(agentID: String?, memoryID: String, content: String): Memory =
        requireNotNull(if (agentID == null) operations.updateSharedMemory(request(strings("content" to content)), memoryID)
        else operations.updateAgentMemory(request(strings("content" to content)), agentID, memoryID)).domain()
    suspend fun deleteMemory(agentID: String?, memoryID: String) {
        if (agentID == null) operations.deleteSharedMemory(memoryID) else operations.deleteAgentMemory(agentID, memoryID)
    }

    suspend fun sharedSkills(): List<SharedSkill> = operations.listSkills().map { it.domain() }
    suspend fun createSharedSkill(name: String, instructions: String): SharedSkill = operations.createSkill(request(strings("name" to name, "instructions" to instructions))).domain()
    suspend fun updateSharedSkill(id: String, name: String, instructions: String): SharedSkill = requireNotNull(operations.updateSkill(request(strings("name" to name, "instructions" to instructions)), id)).domain()
    suspend fun deleteSharedSkill(id: String) = operations.deleteSkill(id)
    suspend fun skillPackage(id: String): SkillPackage = operations.getSkill(id)
    suspend fun copySharedSkill(full: SkillPackage): SharedSkill = operations.createSkill(
        CreateSkillRequest(
            name = "${full.name} copy".take(100),
            description = OptionalField.Present(full.description),
            instructions = full.instructions,
            documents = OptionalField.Present(full.documents.map { CreateSkillRequestDocumentsItem(it.path, it.content) }),
        ),
    ).domain().copy(description = full.description, source = "shared")
    suspend fun botSkills(agentID: String): List<SkillPackage> = operations.listAgentSkills(agentID)
    suspend fun createBotSkill(agentID: String, name: String, instructions: String): SkillPackage =
        operations.createAgentSkill(request(strings("name" to name, "instructions" to instructions)), agentID)
    suspend fun updateBotSkill(agentID: String, skillID: String, name: String, instructions: String): SkillPackage =
        requireNotNull(operations.updateAgentSkill(request(strings("name" to name, "instructions" to instructions)), agentID, skillID))
    suspend fun pinBotSkill(agentID: String, skillID: String, pinned: Boolean): SkillPackage =
        requireNotNull(operations.updateAgentSkill(request(mapOf("pinned" to JsonPrimitive(pinned))), agentID, skillID))
    suspend fun deleteBotSkill(agentID: String, skillID: String) = operations.deleteAgentSkill(agentID, skillID)
    /** Returns whether the bot already had the requested state. */
    suspend fun assignSkill(skillID: String, botID: String, assigned: Boolean): Boolean =
        operations.assignSkillBot(AssignSkillBotRequest(botID, assigned), skillID).skipped
    suspend fun browseSkills(location: String): SkillListing =
        operations.browseSkillRepository(BrowseSkillRepositoryRequest(location), timeoutMs = GITHUB_TIMEOUT_MS)
    suspend fun installSkills(listing: SkillListing, paths: Set<String>, botIDs: Set<String>): List<SkillPackage> = operations.installSkills(
        InstallSkillsRequest(listing.repository, listing.branch, listing.commit, paths.toList(), OptionalField.Present(botIDs.toList())),
        timeoutMs = GITHUB_TIMEOUT_MS,
    )
    suspend fun checkSkillUpdates(): SkillCheck = operations.checkSkillUpdates(timeoutMs = GITHUB_TIMEOUT_MS)
    suspend fun pendingSkillUpdate(id: String): PendingSkillUpdate = operations.getPendingSkillUpdate(id, timeoutMs = GITHUB_TIMEOUT_MS)
    suspend fun applySkillUpdate(id: String): SkillPackage = operations.applySkillUpdate(id, timeoutMs = GITHUB_TIMEOUT_MS)
    suspend fun changes(before: String? = null, limit: Int = CHANGES_PAGE): List<LearnedChange> = operations.listChanges(limit.toLong(), before)
    suspend fun revertChange(id: String): LearnedChange = operations.revertChange(id)
    suspend fun schedules(): List<Schedule> = operations.listSchedules().map { it.domain() }
    suspend fun createSchedule(payload: Map<String, JsonElement>): Schedule = operations.createSchedule(request(payload)).domain()
    suspend fun updateSchedule(id: String, patch: Map<String, JsonElement>): Schedule = requireNotNull(operations.updateSchedule(request(patch), id)).domain()
    suspend fun deleteSchedule(id: String) = operations.deleteSchedule(id)
    suspend fun runSchedule(id: String): Execution {
        val response = operations.runSchedule(id)
        check(response.admitted) { "Task was not admitted" }
        val result = checkNotNull(response.result) { "Task admission returned no execution" }
        return Execution(
            id = checkNotNull(result.id) { "Task admission returned no execution ID" },
            agentId = result.agentId, conversationId = result.conversationId, status = result.status,
            prompt = result.prompt, error = result.error, createdAt = result.createdAt, updatedAt = result.updatedAt,
        )
    }
    suspend fun executions(): List<Execution> = operations.listExecutions().map { it.domain() }
    suspend fun scriptRuns(scheduleID: String): List<ScriptRun> = operations.listScriptRuns(scheduleID, SCRIPT_RUNS_PAGE)
    suspend fun eventSources(): List<EventSource> = operations.listEventSources().sources
    suspend fun createEventSource(name: String): CreateEventSourceResponse = operations.createEventSource(CreateEventSourceRequest(name))
    suspend fun deleteEventSource(id: String) = operations.deleteEventSource(id)
    suspend fun capabilities(): Capabilities = operations.getCapabilities()
    suspend fun activityFeed(
        kind: String? = null,
        agentID: String? = null,
        cursor: String? = null,
        limit: Long? = null,
    ): ActivityFeedResponse = operations.getActivityFeed(agentID, limit, kind, cursor).domain()
    suspend fun pushInfo(): PushInfo = operations.getPushStatus().domain()

    suspend fun registerPushDevice(installationId: String, registration: PushRegistration): PushRegistrationResult =
        operations.putPushDevice(registration.wire(), pushInstallationPath(installationId)).domain()
    suspend fun unregisterPushDevice(installationId: String) = operations.deletePushDevice(pushInstallationPath(installationId))
    suspend fun updatePushPresence(installationId: String, presence: PushPresence) {
        operations.putPushPresence(dev.crumble.android.generated.PutPushPresenceRequest(presence.active, presence.agentId), pushInstallationPath(installationId))
    }
    private fun pushInstallationPath(installationId: String): String {
        require(installationId.isNotBlank()) { "installationId must not be blank" }
        return installationId
    }

    suspend fun models(provider: String): List<ModelSummary> = operations.listModels(provider).map { it.domain() }
    suspend fun providerAccounts() = operations.listProviders()
    suspend fun passwordManagers() = operations.listPasswordManagers().passwordManagers
    suspend fun credentials(agentID: String) = operations.listAgentCredentials(agentID)
    suspend fun saveCredential(agentID: String, name: String, fields: Map<String, JsonElement>) =
        operations.saveAgentCredential(request(fields), agentID, name)
    suspend fun declineCredential(agentID: String, name: String) = operations.declineAgentCredential(agentID, name)
    suspend fun logins(agentID: String) = operations.listAgentLogins(agentID)
    suspend fun saveLogin(agentID: String, name: String, fields: Map<String, JsonElement>) =
        operations.saveAgentLogin(request(fields), agentID, name)
    suspend fun declineLogin(agentID: String, name: String) = operations.declineAgentLogin(agentID, name)
    suspend fun mcpServers(): List<MCPServer> = operations.listMcpServers().map { it.domain() }
}

/**
 * Splits a server-sent event stream into snapshot payloads, line by line. Heartbeats and other
 * event types are skipped; a bot snapshot without an agentId is a protocol error.
 */
class SSEParser(
    private val accepting: Set<String> = setOf("snapshot", "message"),
    private val requiresAgent: Boolean = true,
) {
    private val data = mutableListOf<String>()
    private var eventType = "message"

    class InvalidPayload(val reason: String) : Exception(reason)

    fun feed(line: String): String? {
        when {
            line.startsWith("event:") -> {
                eventType = line.substring(6).trim()
            }
            line.startsWith("data:") -> {
                data.add(line.substring(5).trim())
            }
            line.isEmpty() -> {
                return dispatch()
            }
        }
        return null
    }

    private fun dispatch(): String? {
        val currentEvent = eventType
        eventType = "message"
        if (data.isEmpty()) return null
        val payload = data.joinToString("\n")
        data.clear()
        if (!accepting.contains(currentEvent)) return null
        val element = try {
            CrumbleJson.parseToJsonElement(payload) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw InvalidPayload("invalid SSE payload")
        if (!requiresAgent) return payload
        val agentID = (element["agentId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (agentID.isNullOrEmpty()) {
            throw InvalidPayload("snapshot event missing agentId")
        }
        return payload
    }
}

fun friendlyError(error: Throwable): String {
    if (error is APIError) {
        if (error.statusCode == 401) {
            return "Authentication failed. Sign in again in Settings."
        }
        try {
            val obj = CrumbleJson.parseToJsonElement(error.body) as? JsonObject
            val message = (obj?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (!message.isNullOrEmpty()) {
                val details = obj["details"] as? kotlinx.serialization.json.JsonArray
                val detail = (details?.firstOrNull() as? JsonObject)
                    ?.get("message")
                    ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
                return if (!detail.isNullOrEmpty()) "$message: $detail" else message
            }
        } catch (_: Exception) {
        }
    }
    return error.message ?: error.toString()
}
