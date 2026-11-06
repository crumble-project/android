package dev.crumble.android.model

import dev.crumble.android.generated.SecretStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val CrumbleJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

val CrumblePatchJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
data class Agent(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val instructions: String? = null,
    val avatar: dev.crumble.android.generated.BotAvatarDefinition? = null,
    val enabled: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val webSearchProvider: String? = null,
    val desktopModel: ModelChoice? = null,
    val tools: List<String>? = null,
    val delegates: List<String>? = null,
    val skills: List<Skill>? = null,
    val sharedSkills: List<String>? = null,
    val sharedMemory: Boolean? = null,
    val mcpServers: List<String>? = null,
    val inference: Inference? = null,
    val lastMessage: String? = null,
)

@Serializable
data class ModelChoice(val provider: String = "", val model: String = "")

@Serializable
data class Inference(
    val thinkingLevel: String = "off",
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
data class ModelSummary(
    val provider: String = "",
    val id: String = "",
    val name: String? = null,
    val contextWindow: Int? = null,
    val reasoning: Boolean? = null,
    val thinkingLevels: List<String>? = null,
)

@Serializable
data class Skill(
    @Transient val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val instructions: String = "",
)

/** Who started a turn: the owner, a scheduled task, or another bot. */
@Serializable
data class MessageOrigin(
    val kind: String = "",
    val name: String? = null,
    val agentId: String? = null,
    val source: String? = null,
)

/** A persisted request or reply exchanged between two bots. */
@Serializable
data class BotCommunication(
    val executionId: String = "",
    val fromAgentId: String = "",
    val fromName: String = "",
    val toAgentId: String = "",
    val toName: String = "",
    val status: String = "",
)

/** A desktop job action in the transcript; its live state comes from the bot's held actions. */
@Serializable
data class HeldActionRef(val id: String = "", val job: String = "")

/** A coding job's question in the transcript; whether it still waits comes from the job listing. */
@Serializable
data class JobQuestionRef(val id: String = "", val job: String = "")

/** A `request_bot` result in the transcript; its live state comes from the bot's requests. */
@Serializable
data class BotRequestRef(val id: String = "", val name: String = "")

/** A page a group message's bot searched or read, stored with the message. */
@Serializable
data class MessageSource(val url: String = "", val title: String = "")

/** Client-side delivery state for an optimistic owner message. */
@Serializable
enum class MessageDeliveryState {
    confirmed,
    sending,
    failed,
}

@Serializable
data class Message(
    val id: String = "",
    val entryId: String? = null,
    val role: String = "",
    val content: JsonElement? = null,
    val blocks: JsonElement? = null,
    val kind: String? = null,
    val replyTo: String? = null,
    val replyPreview: String? = null,
    val origin: MessageOrigin? = null,
    val communication: BotCommunication? = null,
    val timestamp: Long? = null,
    val createdAt: String? = null,
    val attachments: List<AttachmentInfo>? = null,
    /** The idempotency key echoed by the server for owner messages. */
    val requestId: String? = null,
    val deliveryState: MessageDeliveryState = MessageDeliveryState.confirmed,
    val heldAction: HeldActionRef? = null,
    val jobQuestion: JobQuestionRef? = null,
    val botRequest: BotRequestRef? = null,
    val credentialRequest: SecretRequestRef? = null,
    val loginRequest: SecretRequestRef? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val isError: Boolean? = null,
    val toolDetails: JsonElement? = null,
    val sources: List<MessageSource>? = null,
) {
    val text: String
        get() = contentText(content)
}

/** A file a sent message carries, as the transcript lists it; image bytes are fetched separately. */
@Serializable
data class AttachmentInfo(
    val name: String = "",
    val mimeType: String = "",
    val size: Int = 0,
    val kind: String = "",
) {
    val isImage: Boolean
        get() = kind == "image"
}

typealias OutgoingAttachment = dev.crumble.android.generated.SendMessageRequestAttachmentsItem

@Serializable
data class ChatSnapshot(
    val messages: List<Message>? = null,
    val status: String? = null,
    val partial: String? = null,
    val error: String? = null,
    val agentId: String? = null,
    val hasMore: Boolean? = null,
    val oldestEntryId: String? = null,
) {
    val isRunning: Boolean
        get() = status == "running"
}

@Serializable
data class ChatHistoryResponse(
    val messages: List<Message>? = null,
    val hasMore: Boolean? = null,
    val oldestEntryId: String? = null,
)

@Serializable
data class Memory(
    val id: String = "",
    val content: String = "",
    val createdAt: String? = null,
    val agentId: String? = null,
    val source: dev.crumble.android.generated.MemorySource? = null,
    val updatedAt: String? = null,
)

/** A shared skill: bundled defaults apply to every bot; library entries are opt-in. */
@Serializable
data class SharedSkill(
    val id: String = "",
    val name: String = "",
    val instructions: String = "",
    val source: String? = null,
    val path: String? = null,
    val description: String? = null,
    val origin: dev.crumble.android.generated.SkillOrigin? = null,
    val update: dev.crumble.android.generated.SkillUpdate? = null,
) {
    val isDefault: Boolean
        get() = source == "bundled"
}

@Serializable
data class Schedule(
    val id: String = "",
    val agentId: String = "",
    val name: String = "",
    val cron: String? = null,
    val prompt: String = "",
    val timezone: String? = null,
    val runAt: String? = null,
    val enabled: Boolean = false,
    /** The bot that saved this task. Null when the owner saved it. */
    val createdByAgentId: String? = null,
    val kind: String = "prompt",
    val command: String? = null,
    val timeoutSeconds: Long = 600,
    val notify: Boolean = true,
    val eventSourceId: String? = null,
    val nextRunAt: String? = null,
)

@Serializable
data class Execution(
    val id: String = "",
    val agentId: String? = null,
    val conversationId: String? = null,
    val status: String? = null,
    val prompt: String? = null,
    val error: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val scheduleId: String? = null,
)

@Serializable
data class ProviderStatus(
    val provider: String = "",
    val name: String? = null,
    val configured: Boolean = false,
)

@Serializable
data class MCPServer(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = false,
    /** The bot the server belongs to, or null when every bot may use it. */
    val agentId: String? = null,
    val authenticated: Boolean? = null,
    /** How the server signs in: "oauth", "token", or "none". */
    val auth: String? = null,
    val oauthClientId: String? = null,
    val token: SecretStatus? = null,
    val oauthClientSecret: SecretStatus? = null,
    val error: String? = null,
)

/** The start of an OAuth sign-in: a URL to open, or `authorized` when saved tokens still work. */
@Serializable
data class OAuthStart(
    val authorizationUrl: String? = null,
    val authorized: Boolean? = null,
)

/** One event from the server's activity stream. */
@Serializable
data class ActivityEvent(
    val type: String = "",
    val agentId: String = "",
    val agentName: String = "",
    val entryId: String? = null,
    val origin: String? = null,
    val preview: String? = null,
    val lastMessage: String? = null,
    val eventId: String? = null,
    val serverId: String? = null,
    val createdAt: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
)

@Serializable
data class ActivityRecord(
    val id: String = "",
    val agentId: String = "",
    val agentName: String = "",
    val entryId: String? = null,
    val timestamp: Long? = null,
    val kind: String = "",
    val details: JsonElement? = null,
    val change: dev.crumble.android.generated.LearnedChange? = null,
)

@Serializable
data class ActivityFeedResponse(
    val activities: List<ActivityRecord> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

private val CHANGE_VERBS = mapOf("create" to "added", "update" to "changed", "delete" to "deleted", "revert" to "reverted")

private fun changeText(source: JsonObject?, key: String) = (source?.get(key) as? JsonPrimitive)?.contentOrNull

/** What a learned change did, such as "Kev's review changed a skill". */
internal fun changeSummary(change: JsonObject, botName: String?): String {
    val actor = when (changeText(change, "actor")) {
        "owner" -> "You"
        "review" -> botName?.let { "$it's review" } ?: "A review"
        "curator" -> "The curator"
        else -> botName ?: "A bot"
    }
    val target = when {
        changeText(change, "target") == "skill" -> "a skill"
        changeText(change, "agentId") != null -> "a memory"
        else -> "a shared memory"
    }
    return "$actor ${CHANGE_VERBS[changeText(change, "action")] ?: "changed"} $target"
}

/** The record a learned change touched: a memory's first line or a skill's name. */
internal fun changeTitle(change: JsonObject): String {
    val state = change["after"] as? JsonObject ?: change["before"] as? JsonObject
    return changeText(state, "content")?.substringBefore('\n') ?: changeText(state, "name") ?: changeText(change, "targetId").orEmpty()
}

/** What a learned change did and to what, such as "Kev's review changed a skill · Triage". */
internal fun changeLabel(change: JsonObject, botName: String?): String = "${changeSummary(change, botName)} · ${changeTitle(change)}"

/** Reads the visible text of message content: a string, text parts, or a text object. */
fun contentText(value: JsonElement?): String {
    when (value) {
        null, is JsonNull -> return ""
        is JsonPrimitive -> if (value.isString) return value.content
        is JsonArray -> {
            val parts = value.mapNotNull { item ->
                (item as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            }
            if (parts.isNotEmpty()) return parts.joinToString("")
        }
        is JsonObject -> {
            val text = (value["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (text != null) return text
        }
    }
    return CrumbleJson.encodeToString(JsonElement.serializer(), value)
}

/** One row of the chat list: a transcript message or a transient row around it. */
sealed class ChatRow {
    abstract val id: String

    data object Older : ChatRow() {
        override val id: String = "older"
    }

    data class Time(val messageId: String, val label: String) : ChatRow() {
        override val id: String = "time:$messageId"
    }

    data class MessageRow(val message: Message) : ChatRow() {
        override val id: String = message.id
    }

    data class Partial(val text: String) : ChatRow() {
        override val id: String = "partial"
    }

    data object Thinking : ChatRow() {
        override val id: String = "thinking"
    }

    data class Failure(val error: String) : ChatRow() {
        override val id: String = "error"
    }

    companion object {
        fun rows(
            messages: List<Message>,
            snapshot: ChatSnapshot?,
            hasMore: Boolean,
            shown: (Message) -> Boolean = ::shownInChat,
        ): List<ChatRow> {
            val rows = mutableListOf<ChatRow>()
            if (hasMore) rows.add(Older)
            var last: Long = 0
            for (message in messages) {
                if (!shown(message)) continue
                val at = message.timestamp
                if (at != null && at > 0) {
                    if (at - last > 30L * 60L * 1000L) {
                        rows.add(Time(message.id, timeLabel(Instant.ofEpochMilli(at))))
                    }
                    last = at
                }
                rows.add(MessageRow(message))
            }
            if (snapshot != null) {
                if (snapshot.isRunning) {
                    rows.add(Thinking)
                }
                val error = snapshot.error
                if (!error.isNullOrEmpty()) {
                    rows.add(Failure(error))
                }
            }
            return rows
        }

        /**
         * Keeps the conversation itself: owner and agent text and context events, not thinking,
         * tool calls, or tool results other than decisions, jobs, and display cards.
         */
        fun shownInChat(message: Message): Boolean {
            return when (message.role) {
                "user" -> (message.text.isNotEmpty() || !message.attachments.isNullOrEmpty()) && !isJobOrigin(message.origin?.kind)
                "event" -> message.text.isNotEmpty()
                "toolResult" -> message.botRequest != null || message.credentialRequest != null || message.loginRequest != null ||
                    jobOf(message) != null || displayOf(message) != null || isDesktopScreenshot(message)
                "assistant" -> {
                    val blocks = message.blocks
                    if (blocks is JsonArray) {
                        blocks.any { block ->
                            val obj = block as? JsonObject ?: return@any false
                            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                            val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""
                            type == "text" && text.trim().isNotEmpty()
                        }
                    } else {
                        contentText(blocks).isNotEmpty()
                    }
                }
                else -> false
            }
        }
    }
}

fun timeLabel(instant: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = ZonedDateTime.ofInstant(instant, zone)
    val current = ZonedDateTime.ofInstant(now, zone)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    val time = date.format(timeFmt)
    if (date.toLocalDate() == current.toLocalDate()) {
        return "Today $time"
    }
    if (date.year == current.year) {
        val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        return "${date.format(dayFmt)} $time"
    }
    val fullFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    return "${date.format(fullFmt)} $time"
}

fun activityTime(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val instant = taskRunDate(value) ?: return value
    return timeLabel(instant)
}

/** Server timestamps are absolute instants; the date picker displays them in its time zone. */
fun taskRunDate(value: String): Instant? {
    return try {
        Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value))
    } catch (_: Exception) {
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            null
        }
    }
}

fun validateTask(
    name: String,
    prompt: String,
    cron: String,
    runAt: String,
    timezone: String = ZoneId.systemDefault().id,
    kind: String = "prompt",
    command: String = "",
    timeoutSeconds: String = "600",
    onEvent: Boolean = false,
    eventSourceID: String = "",
): String? {
    if (kind == "script") {
        if (name.trim().isEmpty() || command.isBlank()) {
            return "Task name and command are required."
        }
        if (timeoutSeconds.trim().toLongOrNull()?.takeIf { it in 1..3600 } == null) {
            return "Timeout must be between 1 and 3600 seconds."
        }
    } else if (name.trim().isEmpty() || prompt.trim().isEmpty()) {
        return "Task name and prompt are required."
    }
    if (onEvent) {
        return if (eventSourceID.isEmpty()) "Choose an event source." else null
    }
    if (cron.isEmpty() == runAt.isEmpty()) {
        return "Choose either a recurring cron or a one-time run time."
    }
    val validZone = try {
        ZoneId.of(timezone)
        true
    } catch (_: Exception) {
        false
    }
    if (!validZone) {
        return "Choose a valid time zone."
    }
    if (runAt.isNotEmpty() && taskRunDate(runAt) == null) {
        return "Choose a valid date and time."
    }
    return null
}

/** Undoes one saved edit; a field someone changed since keeps its newer value. */
internal fun Agent.undoingEdit(edit: Agent, old: Agent): Agent {
    fun <T> undone(field: (Agent) -> T) = if (field(this) == field(edit)) field(old) else field(this)
    return copy(
        name = undone { it.name },
        description = undone { it.description },
        instructions = undone { it.instructions },
        avatar = undone { it.avatar },
        enabled = undone { it.enabled },
        provider = undone { it.provider },
        model = undone { it.model },
        webSearchProvider = undone { it.webSearchProvider },
        desktopModel = undone { it.desktopModel },
        tools = undone { it.tools },
        delegates = undone { it.delegates },
        sharedMemory = undone { it.sharedMemory },
        mcpServers = undone { it.mcpServers },
        inference = undone { it.inference },
    )
}

fun newRequestID(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)

/** Unions transcript rows by ID in entry order. Entries are immutable, so a row that scrolled out stays valid. */
fun mergeMessages(existing: List<Message>, incoming: List<Message>): List<Message> {
    val byId = LinkedHashMap<String, Message>()
    for (msg in existing) byId[msg.id] = msg
    for (msg in incoming) byId[msg.id] = msg
    return byId.values.sortedWith { a, b ->
        val orderA = messageOrder(a.id)
        val orderB = messageOrder(b.id)
        val cmp0 = orderA[0].compareTo(orderB[0])
        if (cmp0 != 0) cmp0 else orderA[1].compareTo(orderB[1])
    }
}

/** Reads the entry and message index from an "agent:entry:index" row ID. */
fun messageOrder(id: String): IntArray {
    val parts = id.split(":")
    if (parts.size < 2) return intArrayOf(0, 0)
    val first = parts[parts.size - 2].toIntOrNull() ?: 0
    val second = parts[parts.size - 1].toIntOrNull() ?: 0
    return intArrayOf(first, second)
}

fun messageLabel(message: Message): String {
    if (message.role == "assistant") return "Agent"
    if (message.role != "user") return "Tool result"
    return noticeTitle(message) ?: "You"
}

/** What a message the owner didn't write is collapsed under; null for the owner's own. */
fun noticeTitle(message: Message): String? {
    val origin = message.origin ?: return null
    val name = origin.name.orEmpty()
    return when (origin.kind) {
        "task" -> "Automation · $name"
        "event" -> "Automation · $name, event from ${origin.source ?: "a deleted source"}"
        "script" -> "Script automation · $name"
        "credential" -> "Credential · $name"
        "bot" -> "From $name"
        "bot_request" -> "Bot request"
        else -> null
    }
}

fun isNotice(message: Message) = message.role == "user" && noticeTitle(message) != null

fun preview(text: String): String {
    var plain = text
    for (mark in listOf("**", "__", "`", "# ", "> ")) {
        plain = plain.replace(mark, "")
    }
    plain = plain.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    return if (plain.length > 120) plain.take(119) + "…" else plain
}

val String.nilIfEmpty: String?
    get() = ifEmpty { null }

val String.trimmingTrailingSlashes: String
    get() = trimEnd('/')

fun Instant.toIso8601SecondString(): String =
    truncatedTo(ChronoUnit.SECONDS).toString()
