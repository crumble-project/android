package dev.crumble.android

import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import kotlinx.serialization.json.*

internal fun agentFixture(id: String, name: String = "Atlas", enabled: Boolean = true, lastMessage: String? = null): String =
    buildJsonObject {
        put("id", id); put("name", name); put("enabled", enabled)
        put("avatar", "bot"); put("description", ""); put("instructions", "")
        put("provider", "openai"); put("model", "model")
        for (key in listOf("tools", "skills", "mcpServers", "delegates")) put(key, JsonArray(emptyList()))
        put("createdAt", "2026-10-18T10:00:00Z"); put("updatedAt", "2026-10-18T10:00:00Z")
        lastMessage?.let { put("lastMessage", it) }
    }.toString()

internal fun memoryFixture(id: String, content: String): String =
    buildJsonObject {
        put("id", id); put("agentId", JsonNull); put("content", content)
        put("createdAt", "2026-10-18T10:00:00Z"); put("updatedAt", "2026-10-18T10:00:00Z")
    }.toString()

internal fun snapshotFixture(snapshot: ChatSnapshot): String =
    buildJsonObject {
        put("agentId", snapshot.agentId ?: "a1"); put("conversationId", "chat")
        put("conversation", buildJsonObject {
            put("id", "chat"); put("agentId", snapshot.agentId ?: "a1"); put("title", "Chat")
            put("createdAt", "2026-10-18T10:00:00Z"); put("updatedAt", "2026-10-18T10:00:00Z")
        })
        put("status", snapshot.status ?: "idle"); put("partial", snapshot.partial.orEmpty())
        put("error", snapshot.error?.let(::JsonPrimitive) ?: JsonNull); put("updatedAt", 0)
        put("hasMore", snapshot.hasMore ?: false)
        put("oldestEntryId", snapshot.oldestEntryId?.let(::JsonPrimitive) ?: JsonNull)
        put("messages", JsonArray(snapshot.messages.orEmpty().map { message ->
            val fields = (CrumbleJson.encodeToJsonElement(message) as JsonObject).toMutableMap()
            fields["entryId"] = JsonPrimitive(message.entryId ?: message.id)
            fields["content"] = JsonPrimitive(message.text)
            JsonObject(fields)
        }))
    }.toString()

internal const val executionFixture = """{"id":"e1","agentId":"a1","conversationId":"chat","scheduleId":null,"submissionId":null,"requestId":"req","status":"pending","prompt":"","error":null,"createdAt":"2026-10-18T10:00:00Z","updatedAt":"2026-10-18T10:00:00Z"}"""
