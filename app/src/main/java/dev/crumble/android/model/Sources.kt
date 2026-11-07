package dev.crumble.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pages a reply may cite, keyed by `sourceKey` of their URL, with their titles. */
typealias Sources = Map<String, String>

data class SourceChip(val href: String, val title: String)

data class SplitReply(val text: String, val chips: List<SourceChip>)

// Parsed by hand: java.net.URI rejects characters real page URLs carry, such as `|` in a query.
private val WEB_URL = Regex("^https?://(?:[^/?#@]*@)?([^/?#]+)([^?#]*)(\\?[^#]*)?", RegexOption.IGNORE_CASE)

private val DEFAULT_PORT = Regex("^(https://.*:443|http://.*:80)$", RegexOption.IGNORE_CASE)

private val WEB_TOOLS = setOf("web_search", "web_extract")

/** Ignores the fragment, a trailing slash, and the host's case, so a cited link matches the page that was read. */
fun sourceKey(href: String): String? {
    val match = WEB_URL.find(href) ?: return null
    val (authority, path, query) = match.destructured
    val origin = href.substring(0, href.indexOf("://") + 3) + authority
    val host = if (DEFAULT_PORT.matches(origin)) authority.substringBeforeLast(':') else authority
    return host.lowercase() + path.trimEnd('/') + query.takeIf { it.length > 1 }.orEmpty()
}

/** The host a source chip names, without its port or `www.`. */
fun siteOf(href: String): String {
    val authority = WEB_URL.find(href)?.groupValues?.get(1) ?: return href
    return authority.lowercase().replace(Regex(":\\d+$"), "").removePrefix("www.")
}

private fun pagesOf(message: Message): List<JsonObject> {
    if (message.role != "toolResult" || message.isError == true || message.toolName !in WEB_TOOLS) return emptyList()
    val pages = message.toolDetails as? JsonArray ?: return emptyList()
    return pages.filterIsInstance<JsonObject>().filterNot { "error" in it }
}

/** For each bot reply, the pages its turn searched or read; a turn starts at each message to the bot. */
fun sourcesOf(messages: List<Message>): Map<String, Sources> {
    val replies = mutableMapOf<String, Sources>()
    var turn = mutableMapOf<String, String>()
    for (message in messages) {
        if (message.role == "user") turn = mutableMapOf()
        for (page in pagesOf(message)) {
            val url = (page["url"] as? JsonPrimitive)?.contentOrNull ?: continue
            val key = sourceKey(url) ?: continue
            turn[key] = (page["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        if (message.role == "assistant" && turn.isNotEmpty()) replies[message.id] = turn.toMap()
    }
    return replies
}

// A citation: a Markdown link labeled "source", whose URL may hold one pair of parentheses.
private val CITATION = Regex("\\[sources?\\]\\((https?://[^\\s()]*(?:\\([^\\s()]*\\)[^\\s()]*)*)\\)", RegexOption.IGNORE_CASE)

// A private-use character marks where a chip was; bot text never contains it.
private const val SLOT = ""

private val SEPARATOR = Regex("$SLOT[ \\t]*(?:,|;|&|and)[ \\t]*(?=$SLOT)")

private val LEFTOVER = Regex("[ \\t]*$SLOT")

private val EMPTY_PARENS = Regex("[ \\t]*\\((?:\\s*sources?:?)?\\s*\\)", RegexOption.IGNORE_CASE)

private val FENCE = Regex("^\\s*(?:```|~~~)")

private val CODE_SPAN = Regex("`[^`]*`")

private fun takeSources(prose: String, sources: Sources, chips: MutableMap<String, SourceChip>): String {
    val marked = CITATION.replace(prose) { citation ->
        val href = citation.groupValues[1]
        val key = sourceKey(href)
        val title = key?.let { sources[it] }
        if (key == null || title == null) {
            citation.value
        } else {
            chips.getOrPut(key) { SourceChip(href, title) }
            SLOT
        }
    }
    return EMPTY_PARENS.replace(LEFTOVER.replace(SEPARATOR.replace(marked, SLOT), ""), "")
}

private fun takeOutsideCode(line: String, sources: Sources, chips: MutableMap<String, SourceChip>): String {
    fun prose(part: String) = if (part.startsWith("`")) part else takeSources(part, sources, chips)
    val kept = StringBuilder()
    var end = 0
    for (span in CODE_SPAN.findAll(line)) {
        kept.append(prose(line.substring(end, span.range.first))).append(span.value)
        end = span.range.last + 1
    }
    return kept.append(prose(line.substring(end))).toString()
}

/** Takes the reply's citations of pages its turn read out of its text, outside code, so they show once as chips after it. */
fun splitSources(markdown: String, sources: Sources): SplitReply {
    val chips = linkedMapOf<String, SourceChip>()
    var fenced = false
    val text = markdown.split("\n").joinToString("\n") { line ->
        when {
            FENCE.containsMatchIn(line) -> {
                fenced = !fenced
                line
            }
            fenced -> line
            else -> takeOutsideCode(line, sources, chips)
        }
    }
    return SplitReply(text, chips.values.toList())
}
