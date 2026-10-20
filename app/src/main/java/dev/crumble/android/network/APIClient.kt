package dev.crumble.android.network

import dev.crumble.android.model.Agent
import dev.crumble.android.model.ActivityFeedResponse
import dev.crumble.android.model.ChatHistoryResponse
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.CrumblePatchJson
import dev.crumble.android.model.Execution
import dev.crumble.android.model.MCPServer
import dev.crumble.android.model.Memory
import dev.crumble.android.model.ModelSummary
import dev.crumble.android.model.OAuthStart
import dev.crumble.android.model.OutgoingAttachment
import dev.crumble.android.model.ProviderStatus
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

data class APIError(
    val method: String,
    val path: String,
    val statusCode: Int,
    val body: String,
) : Exception("api $method $path ($statusCode): $body")

class SSEClosedError : Exception("server-sent event stream closed")

class SignInError(message: String) : Exception(message)

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
        val headers = LinkedHashMap<String, String>()
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
    ): ByteArray {
        val request = makeRequest(method, path, body, timeoutMs)
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

    suspend fun agents(): List<Agent> = operations.listAgents().map { it.domain() }

    suspend fun createAgent(agent: Agent): Agent = operations.createAgent(agent.wire()).domain()

    suspend fun updateAgent(id: String, patch: Map<String, JsonElement>): Agent =
        requireNotNull(operations.updateAgent(request(patch), id)).domain()

    suspend fun deleteAgent(id: String) = operations.deleteAgent(id)
    suspend fun duplicateAgent(id: String): Agent = requireNotNull(operations.duplicateAgent(id)).domain()
    suspend fun chat(agentID: String, limit: Int? = null): ChatSnapshot = operations.getChat(agentID, limit?.toLong()).domain()
    suspend fun chatHistory(agentID: String, before: String, limit: Int): ChatHistoryResponse =
        operations.getChatHistory(agentID, before = before.nilIfEmpty, limit = limit.takeIf { it > 0 }?.toLong()).domain()

    suspend fun sendChatMessage(agentID: String, content: String, requestID: String, replyTo: String, attachments: List<OutgoingAttachment> = emptyList()) {
        val fields = strings("content" to content, "requestId" to requestID).toMutableMap()
        replyTo.nilIfEmpty?.let { fields["replyTo"] = JsonPrimitive(it) }
        if (attachments.isNotEmpty()) fields["attachments"] = CrumbleJson.encodeToJsonElement(ListSerializer(OutgoingAttachment.serializer()), attachments)
        operations.sendMessage(request(fields), agentID, timeoutMs = if (attachments.isEmpty()) 20_000 else 120_000)
    }

    suspend fun attachment(agentID: String, entryID: String, index: Int): ByteArray = operations.getAttachment(agentID, entryID, index.toLong())
    suspend fun abortChat(agentID: String) = operations.abortChat(agentID)
    suspend fun compactChat(agentID: String) { operations.compactChat(agentID) }

    suspend fun chatEvents(agentID: String, limit: Int? = null, onEvent: suspend (ChatSnapshot) -> Unit): Nothing =
        events(operations.streamChatEvents(agentID, limit?.toLong(), timeoutMs = 24 * 60 * 60 * 1000), SSEParser()) {
            onEvent(CrumblePatchJson.decodeFromString<dev.crumble.android.generated.SnapshotEventPayload>(it).domain())
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
    suspend fun activityFeed(): ActivityFeedResponse = operations.getActivityFeed().domain()
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

    suspend fun providers(): List<ProviderStatus> = operations.listProviders().map { it.domain() }
    suspend fun models(provider: String): List<ModelSummary> = operations.listModels(provider).map { it.domain() }
    suspend fun setProviderKey(provider: String, apiKey: String) { operations.putProviderKey(request(strings("apiKey" to apiKey)), provider) }
    suspend fun deleteProviderKey(provider: String) = operations.deleteProviderKey(provider)
    suspend fun mcpServers(): List<MCPServer> = operations.listMcpServers().map { it.domain() }
    suspend fun createMCPServer(payload: Map<String, JsonElement>): MCPServer = operations.createMcpServer(request(payload)).domain()
    suspend fun updateMCPServer(id: String, patch: Map<String, JsonElement>): MCPServer = requireNotNull(operations.updateMcpServer(request(patch), id)).domain()
    suspend fun deleteMCPServer(id: String) = operations.deleteMcpServer(id)
    suspend fun startMCPSignIn(id: String, redirectURI: String): OAuthStart = operations.startMcpOAuth(request(strings("redirectUri" to redirectURI)), id, timeoutMs = 60_000).domain()
    suspend fun finishMCPSignIn(id: String, code: String, state: String, iss: String?): MCPServer {
        val fields = strings("code" to code, "state" to state).toMutableMap()
        iss?.let { fields["iss"] = JsonPrimitive(it) }
        return operations.finishMcpOAuth(request(fields), id, timeoutMs = 60_000).domain()
    }
    suspend fun signOutOfMCP(id: String): MCPServer = operations.signOutMcpOAuth(id).domain()
}

/**
 * Splits a server-sent event stream into snapshot payloads, line by line. Heartbeats and other
 * event types are skipped; a snapshot without an agentId is a protocol error.
 */
class SSEParser(
    private val accepting: Set<String> = setOf("snapshot", "message"),
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
            return "Authentication failed. Check the server token in Settings."
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

data class OAuthCallbackResult(
    val code: String,
    val state: String,
    val iss: String?,
)

/** Reads the code, state and issuer from a sign-in callback, or the provider's reason for refusing. */
fun oauthResult(callbackUri: String): OAuthCallbackResult {
    val uri = URI(callbackUri)
    val rawQuery = uri.rawQuery ?: ""
    val params = LinkedHashMap<String, String>()
    if (rawQuery.isNotEmpty()) {
        for (pair in rawQuery.split("&")) {
            val idx = pair.indexOf('=')
            if (idx >= 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                val v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                params[k] = v
            } else if (pair.isNotEmpty()) {
                params[URLDecoder.decode(pair, StandardCharsets.UTF_8.name())] = ""
            }
        }
    }
    val error = params["error"]
    if (error != null) {
        val desc = params["error_description"] ?: error
        throw SignInError("Sign-in was refused: $desc")
    }
    val code = params["code"]
    val state = params["state"]
    if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
        throw SignInError("Sign-in returned no authorization code.")
    }
    return OAuthCallbackResult(code = code, state = state, iss = params["iss"])
}
