package dev.crumble.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.isWebLink

private val MAX_CELL_WIDTH = 240.dp
private val BARE_URL = Regex("https?://[^\\s<>\"]+")
private val ANGLE_URL = Regex("<(https?://[^\\s<>\"]+)>")
private val LINK_TARGET = Regex("(?:<([^<>\\n]*)>|([^<>\\s]*?))(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?")
private val TABLE_DELIMITER_CELL = Regex(":?-+:?")
private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
private const val URL_END_PUNCTUATION = ".,:;!?'*_~"

/**
 * Renders agent replies: headings, lists, quotes, tables, and code blocks, with inline Markdown inside them.
 * Unfinished streaming text renders too; an open code fence simply runs to the end.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { MarkdownBlock.parse(text) }
    val contentColor = LocalContentColor.current
    val uriHandler = LocalUriHandler.current
    val linkStyles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val inline = { source: String -> inlineMarkdown(source, linkStyles) { url -> runCatching { uriHandler.openUri(url) } } }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = inline(block.text),
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is MarkdownBlock.Heading -> {
                    val style = when {
                        block.level <= 1 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        block.level == 2 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = inline(block.text),
                        color = contentColor,
                        style = style,
                    )
                }
                is MarkdownBlock.ListBlock -> {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (item in block.items) {
                            Row(
                                modifier = Modifier.padding(start = (item.indent * 14).dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = item.marker,
                                    color = contentColor.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = inline(item.text),
                                    color = contentColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                is MarkdownBlock.Quote -> {
                    Row(
                        modifier = Modifier.height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(contentColor.copy(alpha = 0.35f), CircleShape),
                        )
                        Text(
                            text = inline(block.text),
                            color = contentColor.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is MarkdownBlock.Table -> MarkdownTable(block, inline)
                is MarkdownBlock.Code -> {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                RoundedCornerShape(8.dp),
                            )
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        Text(
                            text = block.code,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

/** Cells wrap at a capped width and rows line up across columns, so a wide table scrolls sideways. */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, inline: (String) -> AnnotatedString) {
    val contentColor = LocalContentColor.current
    val columns = table.header.size

    @Composable
    fun Cell(text: String, column: Int, weight: FontWeight?) {
        Text(
            text = inline(text),
            color = contentColor,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = weight,
            textAlign = table.align[column],
            modifier = Modifier
                .border(0.5.dp, contentColor.copy(alpha = 0.25f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    Layout(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        content = {
            table.header.forEachIndexed { column, text -> Cell(text, column, FontWeight.SemiBold) }
            for (row in table.rows) row.forEachIndexed { column, text -> Cell(text, column, null) }
        },
    ) { measurables, _ ->
        val rows = measurables.chunked(columns)
        val cap = MAX_CELL_WIDTH.roundToPx()
        val widths = List(columns) { column -> rows.maxOf { minOf(it[column].maxIntrinsicWidth(Constraints.Infinity), cap) } }
        val heights = rows.map { row -> row.indices.maxOf { row[it].minIntrinsicHeight(widths[it]) } }
        val cells = rows.mapIndexed { y, row ->
            row.mapIndexed { x, cell -> cell.measure(Constraints.fixed(widths[x], heights[y])) }
        }
        layout(widths.sum(), heights.sum()) {
            var top = 0
            cells.forEachIndexed { y, row ->
                var left = 0
                row.forEachIndexed { x, cell ->
                    cell.place(left, top)
                    left += widths[x]
                }
                top += heights[y]
            }
        }
    }
}

/** `onLink` handles a tap; without it Compose opens the link itself. Only web links become tappable. */
fun inlineMarkdown(
    text: String,
    linkStyles: TextLinkStyles? = null,
    onLink: ((String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    appendInline(text, inLink = false) { url ->
        LinkAnnotation.Url(url, linkStyles, onLink?.let { open -> LinkInteractionListener { open(url) } })
    }
}

private fun AnnotatedString.Builder.appendInline(text: String, inLink: Boolean, linkTo: (String) -> LinkAnnotation) {
    fun marked(style: SpanStyle, inner: String) = withStyle(style) { appendInline(inner, inLink, linkTo) }

    fun linked(url: String, label: String) = when {
        inLink || !isWebLink(url) -> appendInline(label, inLink, linkTo)
        else -> withLink(linkTo(url)) { appendInline(label, inLink = true, linkTo) }
    }

    var i = 0
    val n = text.length
    while (i < n) {
        if (text[i] == '\\' && i + 1 < n && text[i + 1] in ESCAPABLE) {
            append(text[i + 1])
            i += 2
            continue
        }
        if (text.startsWith("`", i)) {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (text[i] == '[' || text.startsWith("![", i)) {
            // Replies show no remote images, so an image reads as a link to it.
            val link = inlineLink(text, if (text[i] == '!') i + 1 else i)
            if (link != null) {
                linked(link.target, link.label.ifEmpty { link.target })
                i = link.end
                continue
            }
        }
        if (text[i] == '<' && !inLink) {
            val url = ANGLE_URL.matchAt(text, i)
            if (url != null) {
                linked(url.groupValues[1], url.groupValues[1])
                i = url.range.last + 1
                continue
            }
        }
        if (text[i] == 'h' && !inLink && text.getOrNull(i - 1)?.isLetterOrDigit() != true) {
            val url = BARE_URL.matchAt(text, i)?.value?.let(::trimUrl)
            if (url != null && url.length > "https://".length) {
                linked(url, url)
                i += url.length
                continue
            }
        }
        if (text.startsWith("~~~", i)) {
            val run = text.drop(i).takeWhile { it == '~' }
            append(run)
            i += run.length
            continue
        }
        if (text[i] == '~') {
            val size = if (text.startsWith("~~", i)) 2 else 1
            val end = strikeEnd(text, i + size, size)
            if (end > i) {
                marked(SpanStyle(textDecoration = TextDecoration.LineThrough), text.substring(i + size, end))
                i = end + size
                continue
            }
        }
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val end = text.indexOf(delim, i + 2)
            if (end > i) {
                marked(SpanStyle(fontWeight = FontWeight.Bold), text.substring(i + 2, end))
                i = end + 2
                continue
            }
        }
        if (text.startsWith("*", i) && !text.startsWith("**", i)) {
            val end = text.indexOf('*', i + 1)
            if (end > i) {
                marked(SpanStyle(fontStyle = FontStyle.Italic), text.substring(i + 1, end))
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

private class InlineLink(val label: String, val target: String, val end: Int)

private fun inlineLink(text: String, open: Int): InlineLink? {
    val close = balancedEnd(text, open, '[', ']')
    if (close < 0 || text.getOrNull(close + 1) != '(') return null
    val end = balancedEnd(text, close + 1, '(', ')')
    if (end < 0) return null
    val target = LINK_TARGET.matchEntire(text.substring(close + 2, end).trim()) ?: return null
    return InlineLink(text.substring(open + 1, close), target.groupValues[1].ifEmpty { target.groupValues[2] }, end + 1)
}

private fun balancedEnd(text: String, open: Int, opening: Char, closing: Char): Int {
    var depth = 0
    var i = open
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            opening -> depth++
            closing -> if (--depth == 0) return i
        }
        i++
    }
    return -1
}

/** Drops what GitHub drops from a bare URL's end: sentence punctuation, and closing brackets it didn't open. */
private fun trimUrl(url: String): String {
    var end = url.length
    while (end > 0) {
        val kept = url.substring(0, end)
        val unopened = kept.last() == ')' && kept.count { it == ')' } > kept.count { it == '(' }
        if (kept.last() !in URL_END_PUNCTUATION && !unopened) break
        end--
    }
    return url.substring(0, end)
}

/** One tilde must hug its text, so "~5 min" and "~ 6" stay as written. */
private fun strikeEnd(text: String, start: Int, size: Int): Int {
    if (size == 1 && text.getOrNull(start)?.let { it.isWhitespace() || it.isDigit() } != false) return -1
    val delimiter = "~".repeat(size)
    var index = text.indexOf(delimiter, start)
    while (index >= 0) {
        if (text[index - 1] != '~' && text.getOrNull(index + size) != '~') {
            return if (size == 1 && text[index - 1].isWhitespace()) -1 else index
        }
        index = text.indexOf(delimiter, index + 1)
    }
    return -1
}

sealed class MarkdownBlock {
    data class Item(
        val marker: String,
        var text: String,
        val indent: Int,
    )

    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class ListBlock(val items: List<Item>) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Code(val code: String) : MarkdownBlock()

    /** Every row has as many cells as `header`, and `align` holds one alignment per column. */
    data class Table(
        val header: List<String>,
        val align: List<TextAlign>,
        val rows: List<List<String>>,
    ) : MarkdownBlock()

    companion object {
        fun parse(text: String): List<MarkdownBlock> {
            val blocks = mutableListOf<MarkdownBlock>()
            var paragraph = mutableListOf<String>()
            var items = mutableListOf<Item>()
            var quote = mutableListOf<String>()
            var code: MutableList<String>? = null

            fun flush() {
                if (paragraph.isNotEmpty()) blocks.add(Paragraph(paragraph.joinToString("\n")))
                if (items.isNotEmpty()) blocks.add(ListBlock(items.toList()))
                if (quote.isNotEmpty()) blocks.add(Quote(quote.joinToString("\n")))
                paragraph = mutableListOf()
                items = mutableListOf()
                quote = mutableListOf()
            }

            val lines = text.split("\n")
            var index = -1
            while (++index < lines.size) {
                val line = lines[index]
                val trimmed = line.trim()
                val codeLines = code
                if (codeLines != null) {
                    if (trimmed.startsWith("```")) {
                        blocks.add(Code(codeLines.joinToString("\n")))
                        code = null
                    } else {
                        codeLines.add(line)
                    }
                    continue
                }
                if (trimmed.startsWith("```")) {
                    flush()
                    code = mutableListOf()
                } else if (trimmed.isEmpty()) {
                    flush()
                } else {
                    val hLevel = headingLevel(trimmed)
                    val lItem = if (hLevel == null) listItem(line) else null
                    when {
                        hLevel != null -> {
                            flush()
                            blocks.add(Heading(hLevel, trimmed.dropWhile { it == '#' }.trim()))
                        }
                        lItem != null -> {
                            if (paragraph.isNotEmpty() || quote.isNotEmpty()) {
                                val pending = items
                                items = mutableListOf()
                                flush()
                                items = pending
                            }
                            items.add(lItem)
                        }
                        trimmed.startsWith(">") -> {
                            if (paragraph.isNotEmpty() || items.isNotEmpty()) flush()
                            quote.add(trimmed.drop(1).trim())
                        }
                        isTableHeader(line, lines.getOrNull(index + 1)) -> {
                            flush()
                            val header = tableCells(line)
                            val align = tableCells(lines[index + 1]).map(::columnAlign)
                            val rows = mutableListOf<List<String>>()
                            index += 1
                            while (index + 1 < lines.size && !endsTable(lines[index + 1], lines.getOrNull(index + 2))) {
                                index += 1
                                val cells = tableCells(lines[index])
                                rows.add(header.indices.map { cells.getOrElse(it) { "" } })
                            }
                            blocks.add(Table(header, align, rows))
                        }
                        items.isNotEmpty() && line.startsWith(" ") -> {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = "${last.text} $trimmed")
                        }
                        else -> {
                            if (items.isNotEmpty() || quote.isNotEmpty()) flush()
                            paragraph.add(line)
                        }
                    }
                }
            }
            code?.let { blocks.add(Code(it.joinToString("\n"))) }
            flush()
            return blocks
        }

        private fun isTableHeader(header: String, delimiter: String?): Boolean {
            if (delimiter == null || '|' !in header) return false
            val cells = tableCells(delimiter)
            return cells.size == tableCells(header).size && cells.all(TABLE_DELIMITER_CELL::matches)
        }

        private fun endsTable(line: String, next: String?): Boolean {
            val trimmed = line.trim()
            return trimmed.isEmpty() || trimmed.startsWith("```") || trimmed.startsWith(">") ||
                headingLevel(trimmed) != null || listItem(line) != null || isTableHeader(line, next)
        }

        private fun tableCells(line: String): List<String> {
            val row = line.trim()
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var i = if (row.startsWith("|")) 1 else 0
            while (i < row.length) {
                when {
                    row.startsWith("\\|", i) -> {
                        current.append('|')
                        i++
                    }
                    row[i] == '|' -> {
                        cells.add(current.toString().trim())
                        current.clear()
                    }
                    else -> current.append(row[i])
                }
                i++
            }
            if (current.isNotEmpty() || !row.endsWith("|") || cells.isEmpty()) cells.add(current.toString().trim())
            return cells
        }

        private fun columnAlign(delimiter: String): TextAlign = when {
            delimiter.startsWith(":") && delimiter.endsWith(":") -> TextAlign.Center
            delimiter.endsWith(":") -> TextAlign.End
            else -> TextAlign.Start
        }

        private fun headingLevel(line: String): Int? {
            val hashes = line.takeWhile { it == '#' }.length
            if (hashes !in 1..6) return null
            if (line.drop(hashes).firstOrNull() != ' ') return null
            return hashes
        }

        private fun listItem(line: String): Item? {
            val indent = line.takeWhile { it == ' ' }.length / 2
            val trimmed = line.trim()
            for (bullet in listOf("- ", "* ", "+ ")) {
                if (trimmed.startsWith(bullet)) {
                    return Item(marker = "•", text = trimmed.drop(2), indent = indent)
                }
            }
            val digits = trimmed.takeWhile { it.isDigit() }
            if (digits.isNotEmpty() && digits.length <= 3) {
                val rest = trimmed.drop(digits.length)
                if (rest.startsWith(". ") || rest.startsWith(") ")) {
                    return Item(marker = "$digits.", text = rest.drop(2), indent = indent)
                }
            }
            return null
        }
    }
}
