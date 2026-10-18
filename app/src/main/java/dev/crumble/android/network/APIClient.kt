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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    private suspend fun <T> call(
        method: String,
        path: String,
        deserializer: KSerializer<T>,
        body: ByteArray? = null,
        timeoutMs: Int = 20_000,
    ): T {
        val bytes = send(method, path, body, timeoutMs)
        return CrumbleJson.decodeFromString(deserializer, String(bytes, StandardCharsets.UTF_8))
    }

    private suspend fun perform(
        method: String,
        path: String,
        body: ByteArray? = null,
        timeoutMs: Int = 20_000,
    ) {
        send(method, path, body, timeoutMs)
    }

    companion object {
        fun escape(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%7E", "~")
    }

    suspend fun agents(): List<Agent> =
        call("GET", "/v1/agents", ListSerializer(Agent.serializer()))

    suspend fun createAgent(agent: Agent): Agent {
        val payload = CrumbleJson.encodeToString(Agent.serializer(), agent).toByteArray(StandardCharsets.UTF_8)
        return call("POST", "/v1/agents", Agent.serializer(), body = payload)
    }

    suspend fun updateAgent(id: String, patch: Map<String, JsonElement>): Agent {
        val payload = CrumblePatchJson.encodeToString(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            patch,
        ).toByteArray(StandardCharsets.UTF_8)
        return call("PATCH", "/v1/agents/$id", Agent.serializer(), body = payload)
    }

    suspend fun deleteAgent(id: String) =
        perform("DELETE", "/v1/agents/$id")

    suspend fun duplicateAgent(id: String): Agent =
        call("POST", "/v1/agents/$id/duplicate", Agent.serializer())

    suspend fun chat(agentID: String, limit: Int? = null): ChatSnapshot =
        call("GET", "/v1/agents/$agentID/chat${limit?.let { "?limit=$it" }.orEmpty()}", ChatSnapshot.serializer())

    suspend fun chatHistory(agentID: String, before: String, limit: Int): ChatHistoryResponse {
        val query = mutableListOf<String>()
        if (before.isNotEmpty()) query.add("before=${escape(before)}")
        if (limit > 0) query.add("limit=$limit")
        val suffix = if (query.isEmpty()) "" else "?" + query.joinToString("&")
        return call("GET", "/v1/agents/$agentID/chat/history$suffix", ChatHistoryResponse.serializer())
    }

    @Serializable
    private data class SendChatBody(
        val content: String,
        val requestId: String,
        val replyTo: String? = null,
        val attachments: List<OutgoingAttachment>? = null,
    )

    suspend fun sendChatMessage(
        agentID: String,
        content: String,
        requestID: String,
        replyTo: String,
        attachments: List<OutgoingAttachment> = emptyList(),
    ) {
        val body = SendChatBody(
            content = content,
            requestId = requestID,
            replyTo = replyTo.nilIfEmpty,
            attachments = attachments.ifEmpty { null },
        )
        val payload = CrumbleJson.encodeToString(SendChatBody.serializer(), body).toByteArray(StandardCharsets.UTF_8)
        perform(
            "POST",
            "/v1/agents/$agentID/messages",
            body = payload,
            timeoutMs = if (attachments.isEmpty()) 20_000 else 120_000,
        )
    }

    /** The bytes of an attached image, by its position in the message's attachments. */
    suspend fun attachment(agentID: String, entryID: String, index: Int): ByteArray =
        send("GET", "/v1/agents/$agentID/attachments/${escape(entryID)}/$index")

    suspend fun abortChat(agentID: String) =
        perform("POST", "/v1/agents/$agentID/abort")

    suspend fun compactChat(agentID: String) =
        perform("POST", "/v1/agents/$agentID/compact")

    /**
     * Follows the bot chat's server-sent events, passing each snapshot payload to onEvent.
     * It returns only by throwing: SSEClosedError when the server ends the stream.
     */
    suspend fun chatEvents(agentID: String, limit: Int? = null, onEvent: suspend (String) -> Unit): Nothing =
        events("/v1/agents/$agentID/events${limit?.let { "?limit=$it" }.orEmpty()}", SSEParser(), onEvent)

    /** Follows server-wide activity: finished replies and sidebar previews from every bot. */
    suspend fun activityEvents(onEvent: suspend (String) -> Unit): Nothing =
        events("/v1/events", SSEParser(accepting = setOf("activity")), onEvent)

    private suspend fun events(
        path: String,
        parser: SSEParser,
        onEvent: suspend (String) -> Unit,
    ): Nothing {
        val request = makeRequest("GET", path, timeoutMs = 24 * 60 * 60 * 1000)
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

    private fun memoryPath(agentID: String?): String =
        if (agentID != null) "/v1/agents/$agentID/memories" else "/v1/memories"

    suspend fun memories(agentID: String?): List<Memory> =
        call("GET", memoryPath(agentID), ListSerializer(Memory.serializer()))

    suspend fun addMemory(agentID: String?, content: String): Memory {
        val payload = CrumbleJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("content" to content),
        ).toByteArray(StandardCharsets.UTF_8)
        return call("POST", memoryPath(agentID), Memory.serializer(), body = payload)
    }

    suspend fun updateMemory(agentID: String?, memoryID: String, content: String): Memory {
        val payload = CrumbleJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("content" to content),
        ).toByteArray(StandardCharsets.UTF_8)
        return call("PATCH", "${memoryPath(agentID)}/$memoryID", Memory.serializer(), body = payload)
    }

    suspend fun deleteMemory(agentID: String?, memoryID: String) =
        perform("DELETE", "${memoryPath(agentID)}/$memoryID")

    suspend fun sharedSkills(): List<SharedSkill> =
        call("GET", "/v1/skills", ListSerializer(SharedSkill.serializer()))

    suspend fun createSharedSkill(name: String, instructions: String): SharedSkill {
        val payload = CrumbleJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("name" to name, "instructions" to instructions),
        ).toByteArray(StandardCharsets.UTF_8)
        return call("POST", "/v1/skills", SharedSkill.serializer(), body = payload)
    }

    suspend fun updateSharedSkill(id: String, name: String, instructions: String): SharedSkill {
        val payload = CrumbleJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("name" to name, "instructions" to instructions),
        ).toByteArray(StandardCharsets.UTF_8)
        return call("PATCH", "/v1/skills/$id", SharedSkill.serializer(), body = payload)
    }

    suspend fun deleteSharedSkill(id: String) =
        perform("DELETE", "/v1/skills/$id")

    suspend fun schedules(): List<Schedule> =
        call("GET", "/v1/schedules", ListSerializer(Schedule.serializer()))

    suspend fun createSchedule(payload: Map<String, JsonElement>): Schedule {
        val bytes = CrumblePatchJson.encodeToString(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            payload,
        ).toByteArray(StandardCharsets.UTF_8)
        return call("POST", "/v1/schedules", Schedule.serializer(), body = bytes)
    }

    suspend fun updateSchedule(id: String, patch: Map<String, JsonElement>): Schedule {
        val bytes = CrumblePatchJson.encodeToString(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            patch,
        ).toByteArray(StandardCharsets.UTF_8)
        return call("PATCH", "/v1/schedules/$id", Schedule.serializer(), body = bytes)
    }

    suspend fun deleteSchedule(id: String) =
        perform("DELETE", "/v1/schedules/$id")

    @Serializable
    private data class ScheduleRunEnvelope(val result: Execution)

    suspend fun runSchedule(id: String): Execution =
        call("POST", "/v1/schedules/$id/run", ScheduleRunEnvelope.serializer()).result

    suspend fun executions(): List<Execution> =
        call("GET", "/v1/executions", ListSerializer(Execution.serializer()))

    suspend fun activityFeed(): ActivityFeedResponse =
        call("GET", "/v1/activity/feed", ActivityFeedResponse.serializer())

    suspend fun providers(): List<ProviderStatus> =
        call("GET", "/v1/providers", ListSerializer(ProviderStatus.serializer()))

    suspend fun models(provider: String): List<ModelSummary> =
        call("GET", "/v1/models?provider=${escape(provider)}", ListSerializer(ModelSummary.serializer()))

    suspend fun setProviderKey(provider: String, apiKey: String) {
        val payload = CrumbleJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("apiKey" to apiKey),
        ).toByteArray(StandardCharsets.UTF_8)
        perform("PUT", "/v1/providers/$provider", body = payload)
    }

    suspend fun deleteProviderKey(provider: String) =
        perform("DELETE", "/v1/providers/$provider")

    suspend fun mcpServers(): List<MCPServer> =
        call("GET", "/v1/mcp-servers", ListSerializer(MCPServer.serializer()))

    suspend fun createMCPServer(payload: Map<String, JsonElement>): MCPServer {
        val bytes = CrumblePatchJson.encodeToString(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            payload,
        ).toByteArray(StandardCharsets.UTF_8)
        return call("POST", "/v1/mcp-servers", MCPServer.serializer(), body = bytes)
    }

    suspend fun updateMCPServer(id: String, patch: Map<String, JsonElement>): MCPServer {
        val bytes = CrumblePatchJson.encodeToString(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            patch,
        ).toByteArray(StandardCharsets.UTF_8)
        return call("PATCH", "/v1/mcp-servers/$id", MCPServer.serializer(), body = bytes)
    }

    suspend fun deleteMCPServer(id: String) =
        perform("DELETE", "/v1/mcp-servers/$id")

    suspend fun startMCPSignIn(id: String, redirectURI: String): OAuthStart {
        val payload = CrumbleJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("redirectUri" to redirectURI),
        ).toByteArray(StandardCharsets.UTF_8)
        return call("POST", "/v1/mcp-servers/$id/oauth", OAuthStart.serializer(), body = payload, timeoutMs = 60_000)
    }

    suspend fun finishMCPSignIn(id: String, code: String, state: String, iss: String?): MCPServer {
        val body = LinkedHashMap<String, String>()
        body["code"] = code
        body["state"] = state
        if (iss != null) body["iss"] = iss
        val payload = CrumbleJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            body,
        ).toByteArray(StandardCharsets.UTF_8)
        return call("POST", "/v1/mcp-servers/$id/oauth/callback", MCPServer.serializer(), body = payload, timeoutMs = 60_000)
    }

    suspend fun signOutOfMCP(id: String): MCPServer =
        call("DELETE", "/v1/mcp-servers/$id/oauth", MCPServer.serializer())
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
