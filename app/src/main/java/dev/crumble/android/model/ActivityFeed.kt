package dev.crumble.android.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

const val ACTIVITY_PAGE_SIZE = 50L
private const val PREVIEW_CHARS = 240
private val SPACES = Regex("[\\s\\p{Z}]+")

@OptIn(ExperimentalSerializationApi::class)
private val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

enum class ActivityKind(val value: String, val label: String) {
    ToolCall("tool-call", "Tool calls"),
    ToolResult("tool-result", "Tool results"),
    McpCall("mcp-call", "MCP calls"),
    BotMessage("bot-message", "Bot messages"),
    Change("change", "Changes"),
}

/** The Activity page: its filters, the pages loaded so far, and the read in flight. */
data class ActivityFeedState(
    val kind: String? = null,
    val botID: String? = null,
    val items: List<ActivityRecord> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val error: String = "",
    val olderError: String = "",
)

/** The first page; `keepOlder` keeps older pages already loaded when the page still joins up with them. */
fun ActivityFeedState.withFirstPage(page: ActivityFeedResponse, keepOlder: Boolean): ActivityFeedState {
    val joint = if (keepOlder) items.indexOfFirst { it.id == page.activities.lastOrNull()?.id } else -1
    val merged = if (joint >= 0) page.activities + items.drop(joint + 1) else page.activities
    return copy(
        items = merged.distinctBy { it.id },
        nextCursor = if (joint >= 0) nextCursor else page.nextCursor,
        loading = false,
        error = "",
    )
}

fun ActivityFeedState.withOlderPage(page: ActivityFeedResponse): ActivityFeedState =
    copy(items = (items + page.activities).distinctBy { it.id }, nextCursor = page.nextCursor, loadingOlder = false, olderError = "")

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.text(key: String): String? = get(key)?.let { value: JsonElement ->
    if (value is JsonPrimitive && value.isString) value.content else value.toString()
}

private val ActivityRecord.fields: JsonObject
    get() = details as? JsonObject ?: JsonObject(emptyMap())

private fun previewOf(text: String?) = text.orEmpty().replace(SPACES, " ").take(PREVIEW_CHARS)

/** What a row says: `lead verb target`, with tool names set as code. */
data class ActivityHeadline(val lead: String, val verb: String, val target: String, val code: Boolean = false, val muted: Boolean = false)

fun ActivityRecord.headline(): ActivityHeadline {
    if (kind == "change") {
        val change = fields
        return ActivityHeadline(changeSummary(change, agentName.nilIfEmpty), "·", changeTitle(change), muted = true)
    }
    val tool = fields.string("toolName") ?: fields.string("name") ?: "a tool"
    val communication = fields["communication"] as? JsonObject
    return when (kind) {
        "tool-call" -> ActivityHeadline(agentName, "used", tool, code = true)
        "tool-result" -> ActivityHeadline(agentName, if (fields.isError()) "got an error from" else "got a result from", tool, code = true)
        "mcp-call" -> ActivityHeadline(agentName, "called", fields.string("name") ?: "an MCP tool", code = true)
        "bot-message" -> ActivityHeadline(communication?.string("fromName") ?: agentName, "messaged", communication?.string("toName") ?: "a bot")
        else -> ActivityHeadline(agentName, "", kind.replace('-', ' '))
    }
}

private fun JsonObject.isError() = (get("isError") as? JsonPrimitive)?.booleanOrNull == true

fun ActivityRecord.preview(): String = when (kind) {
    "change" -> ""
    "tool-result", "bot-message" -> previewOf(fields.text("content"))
    else -> previewOf(fields.string("error") ?: fields.string("args") ?: fields.text("arguments"))
}

fun ActivityRecord.detailText(): String {
    val content = fields.text("content")
    if (kind == "bot-message" && !content.isNullOrEmpty()) return content
    return details?.let { PrettyJson.encodeToString(JsonElement.serializer(), it) } ?: "No details"
}

val ActivityRecord.failed: Boolean
    get() = kind != "change" && (fields.isError() || !fields.string("error").isNullOrEmpty())

data class ActivityDay(val key: String, val label: String, val items: List<ActivityRecord>)

private fun activityDayLabel(date: LocalDate?, today: LocalDate, locale: Locale): String {
    if (date == null) return "Earlier"
    return when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy", locale))
    }
}

/** Groups records by the viewer's local day, oldest day last, keeping feed order inside a day. */
fun activityDays(
    records: List<ActivityRecord>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ActivityDay> = records
    .groupBy { record -> record.timestamp?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
    .map { (date, items) -> ActivityDay(date?.toString() ?: "undated", activityDayLabel(date, today, locale), items) }

fun ActivityRecord.clock(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val stamp = timestamp?.takeIf { it != 0L } ?: return ""
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(stamp).atZone(zone))
}
