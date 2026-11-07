package dev.crumble.android.model

import dev.crumble.android.generated.CodingJobQuestion
import java.util.Locale
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

enum class JobKind(val label: String) {
    coding("Coding job"),
    desktop("Desktop job"),
}

fun isJobOrigin(kind: String?): Boolean = JobKind.entries.any { it.name == kind }

/** A coding or desktop job; `status` is what the tool result that named it reported. */
data class JobRef(val kind: JobKind, val name: String, val status: String? = null)

data class JobSummary(
    val kind: JobKind,
    val name: String,
    val status: String,
    val model: String? = null,
    val usage: JsonElement? = null,
    val question: CodingJobQuestion? = null,
) {
    val ref: JobRef
        get() = JobRef(kind, name)

    val subtitle: String
        get() = listOfNotNull(model, costLine(usage)).joinToString(" · ")
}

/** The open job panel: the job's transcript, oldest message first, and where earlier pages start. */
data class JobPanel(
    val job: JobRef,
    val messages: List<Message> = emptyList(),
    val hasMore: Boolean = false,
    val oldestEntryID: String? = null,
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val loadingEarlier: Boolean = false,
    val downloading: Boolean = false,
    val downloadFailed: Boolean = false,
)

sealed class JobItem {
    abstract val id: String

    data class Task(override val id: String, val text: String) : JobItem()
    data class Reply(override val id: String, val text: String) : JobItem()
    data class Step(
        override val id: String,
        val tool: String,
        val subject: String?,
        val result: Message? = null,
    ) : JobItem()
}

fun jobWorking(status: String?): Boolean = status == "working" || status == "running"

/** A coding or desktop job a tool result started or messaged. */
fun jobOf(message: Message): JobRef? {
    if (message.role != "toolResult" || message.isError == true) return null
    val kind = when (message.toolName) {
        "coding" -> JobKind.coding
        "desktop" -> JobKind.desktop
        else -> return null
    }
    val details = message.toolDetails as? JsonObject ?: return null
    val name = details.string("name")?.nilIfEmpty ?: return null
    return JobRef(kind, name, details.string("status"))
}

/** A job's cost over its models. Subscription providers such as Claude Code report zero, which says nothing. */
fun costLine(usage: JsonElement?): String? {
    val models = (usage as? JsonObject)?.get("models") as? JsonObject ?: return null
    val cost = models.values.sumOf { model ->
        val total = ((model as? JsonObject)?.get("cost") as? JsonObject)?.get("total") as? JsonPrimitive
        total?.doubleOrNull ?: 0.0
    }
    return if (cost > 0) String.format(Locale.ROOT, "$%.4f", cost) else null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun blocksOf(message: Message): List<JsonObject> =
    (message.blocks as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

private fun stepOf(message: Message, block: JsonObject, index: Int): JobItem.Step {
    val arguments = block["arguments"] as? JsonObject
    return JobItem.Step(
        id = block.string("id") ?: "${message.id}:$index",
        tool = block.string("name").orEmpty(),
        subject = arguments?.string("command") ?: arguments?.string("path"),
    )
}

/** The job's task, replies, and tool steps; a tool result attaches to the step that called it. */
fun jobItems(messages: List<Message>): List<JobItem> {
    val items = mutableListOf<JobItem>()
    val steps = mutableMapOf<String, Int>()
    for (message in messages) {
        when (message.role) {
            "user" -> if (message.text.isNotBlank()) items.add(JobItem.Task(message.id, message.text))
            "assistant" -> blocksOf(message).forEachIndexed { index, block ->
                val text = block.string("text").orEmpty()
                if (block.string("type") == "text" && text.isNotBlank()) {
                    items.add(JobItem.Reply("${message.id}:$index", text))
                }
                if (block.string("type") == "toolCall" && !block.string("name").isNullOrEmpty()) {
                    val step = stepOf(message, block, index)
                    steps[step.id] = items.size
                    items.add(step)
                }
            }
            "toolResult" -> steps[message.toolCallId]?.let { at ->
                items[at] = (items[at] as JobItem.Step).copy(result = message)
            }
        }
    }
    return items
}

private val STEP_VERBS = mapOf(
    "bash" to "Ran",
    "read" to "Read",
    "write" to "Wrote",
    "edit" to "Edited",
    "computer_use" to "Used the bot desktop",
)

fun jobStepVerb(tool: String): String = STEP_VERBS[tool] ?: tool.replaceFirstChar { it.uppercase() }

/** Keeps pages loaded earlier and rows the newest page has scrolled past; entries never change. */
fun mergeJobPages(existing: List<Message>, newest: List<Message>): List<Message> {
    val first = newest.firstOrNull()?.id ?: return existing
    return existing.takeWhile { it.id != first } + newest
}

/** A fence longer than any backtick run in the text, so the text can't close it. */
private fun fenced(text: String, info: String = ""): String {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(longest, 2) + 1)
    return "$fence$info\n$text\n$fence"
}

@OptIn(ExperimentalSerializationApi::class)
private val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private fun messageMarkdown(message: Message): List<String> = when (message.role) {
    "user" -> listOf("## Task\n\n${message.text}")
    "toolResult" -> listOf((if (message.isError == true) "Failed:\n\n" else "") + fenced(message.text))
    "assistant" -> blocksOf(message).mapNotNull { block ->
        val text = block.string("text").orEmpty()
        val name = block.string("name").orEmpty()
        when {
            block.string("type") == "text" && text.isNotBlank() -> text
            block.string("type") == "toolCall" && name.isNotEmpty() -> {
                val arguments = PrettyJson.encodeToString(JsonElement.serializer(), block["arguments"] ?: JsonObject(emptyMap()))
                "### $name\n\n${fenced(arguments, "json")}"
            }
            else -> null
        }
    }
    else -> emptyList()
}

/** A job's transcript as the Markdown document the web app downloads. */
fun jobMarkdown(job: JobRef, messages: List<Message>): String =
    (listOf("# ${job.name}", job.kind.label) + messages.flatMap(::messageMarkdown)).joinToString("\n\n") + "\n"
