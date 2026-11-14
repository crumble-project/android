package dev.crumble.android

import dev.crumble.android.generated.SchedulePreview
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.CrumbleJson
import kotlinx.serialization.json.*

private val avatarFixture = buildJsonObject {
    put("definition", buildJsonObject {
        put("version", 1); put("id", "box"); put("name", "Box")
        put("body", buildJsonObject { put("path", "M20 20H80V80H20Z") })
        put("eyes", buildJsonObject { put("x", 50); put("y", 46); put("spacing", 15); put("scale", 1) })
    })
    put("color", "#38bdf8"); put("eyeColor", "#151515")
}

internal fun agentFixture(id: String, name: String = "Atlas", enabled: Boolean = true, lastMessage: String? = null): String =
    buildJsonObject {
        put("id", id); put("name", name); put("enabled", enabled)
        put("avatar", avatarFixture); put("description", ""); put("instructions", "")
        put("provider", "openai"); put("model", "model")
        for (key in listOf("tools", "skills", "mcpServers", "delegates")) put(key, JsonArray(emptyList()))
        put("createdAt", "2026-10-18T10:00:00Z"); put("updatedAt", "2026-10-18T10:00:00Z")
        lastMessage?.let { put("lastMessage", it) }
    }.toString()

internal fun memoryFixture(id: String, content: String): String =
    buildJsonObject {
        put("id", id); put("agentId", JsonNull); put("content", content); put("source", JsonNull)
        put("createdAt", "2026-10-18T10:00:00Z"); put("updatedAt", "2026-10-18T10:00:00Z")
    }.toString()

internal fun snapshotFixture(snapshot: ChatSnapshot): String =
    buildJsonObject {
        put("agentId", snapshot.agentId ?: "a1"); put("conversationId", "chat")
        put("conversation", buildJsonObject {
            put("id", "chat"); put("agentId", snapshot.agentId ?: "a1"); put("title", "Chat")
            put("createdAt", "2026-10-18T10:00:00Z"); put("updatedAt", "2026-10-18T10:00:00Z")
        })
        put("status", snapshot.status ?: "idle"); put("working", snapshot.working ?: if (snapshot.status == "running") "thinking" else "idle"); put("partial", snapshot.partial.orEmpty())
        put("error", snapshot.error?.let(::JsonPrimitive) ?: JsonNull); put("updatedAt", 0)
        put("hasMore", snapshot.hasMore ?: false)
        put("oldestEntryId", snapshot.oldestEntryId?.let(::JsonPrimitive) ?: JsonNull)
        put("messages", JsonArray(snapshot.messages.orEmpty().map { message ->
            val fields = (CrumbleJson.encodeToJsonElement(message) as JsonObject).toMutableMap()
            fields["entryId"] = JsonPrimitive(message.entryId ?: message.id)
            fields["content"] = JsonPrimitive(message.text)
            fields["replyReferences"] = CrumbleJson.encodeToJsonElement(message.replyReferences)
            fields.remove("attachments")
            message.attachments?.let { attachments ->
                fields["files"] = JsonArray(attachments.map { attachment ->
                    buildJsonObject {
                        put("id", attachment.id)
                        if (attachment.deleted) {
                            put("deleted", true)
                        } else {
                            put("name", attachment.name)
                            put("mimeType", attachment.mimeType)
                            put("size", attachment.size)
                        }
                    }
                })
            }
            JsonObject(fields)
        }))
    }.toString()

internal const val executionFixture = """{"id":"e1","agentId":"a1","conversationId":"chat","scheduleId":null,"submissionId":null,"requestId":"req","status":"pending","prompt":"","error":null,"createdAt":"2026-10-18T10:00:00Z","updatedAt":"2026-10-18T10:00:00Z"}"""

internal fun AppModel.allowTaskSave() {
    acceptSchedulePreview(client!!, taskDraft!!, SchedulePreview("", "Ready", false, null))
}
