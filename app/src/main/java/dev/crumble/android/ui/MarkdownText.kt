package dev.crumble.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders agent replies: headings, lists, quotes, and code blocks, with inline Markdown inside them.
 * Unfinished streaming text renders too; an open code fence simply runs to the end.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { MarkdownBlock.parse(text) }
    val contentColor = LocalContentColor.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = inlineMarkdown(block.text),
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
                        text = inlineMarkdown(block.text),
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
                                    text = inlineMarkdown(item.text),
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
                            text = inlineMarkdown(block.text),
                            color = contentColor.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
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

fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
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
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val end = text.indexOf(delim, i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (text.startsWith("*", i) && !text.startsWith("**", i)) {
            val end = text.indexOf('*', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
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

            for (line in text.split("\n")) {
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
