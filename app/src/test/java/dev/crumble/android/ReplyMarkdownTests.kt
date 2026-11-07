package dev.crumble.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageOrigin
import dev.crumble.android.model.isNotice
import dev.crumble.android.model.messageLabel
import dev.crumble.android.model.noticeTitle
import dev.crumble.android.ui.MarkdownBlock
import dev.crumble.android.ui.inlineMarkdown
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun AnnotatedString.links() = getLinkAnnotations(0, length).map { range ->
    text.substring(range.start, range.end) to (range.item as LinkAnnotation.Url).url
}

private fun AnnotatedString.styled(match: (androidx.compose.ui.text.SpanStyle) -> Boolean) =
    spanStyles.filter { match(it.item) }.map { text.substring(it.start, it.end) }

class ReplyMarkdownTests {
    // Links

    @Test
    fun aMarkdownLinkShowsItsLabelAndOpensItsTarget() {
        val reply = inlineMarkdown("See [the docs](https://example.com/a) now")

        assertEquals("See the docs now", reply.text)
        assertEquals(listOf("the docs" to "https://example.com/a"), reply.links())
    }

    @Test
    fun aLinkMayCarryATitleAngleBracketsOrParentheses() {
        assertEquals("https://a.dev", inlineMarkdown("[a](https://a.dev \"Home\")").links().single().second)
        assertEquals("https://a.dev/x", inlineMarkdown("[a](<https://a.dev/x>)").links().single().second)
        assertEquals("https://a.dev/w_(x)", inlineMarkdown("[a](https://a.dev/w_(x))").links().single().second)
    }

    @Test
    fun aLabelKeepsItsInlineMarkdown() {
        val reply = inlineMarkdown("[**bold** text](https://a.dev)")

        assertEquals("bold text", reply.text)
        assertEquals(listOf("bold text" to "https://a.dev"), reply.links())
        assertEquals(listOf("bold"), reply.styled { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun aBoldLinkStaysALink() {
        assertEquals(listOf("a" to "https://a.dev"), inlineMarkdown("**[a](https://a.dev)**").links())
        assertEquals(listOf("https://a.dev" to "https://a.dev"), inlineMarkdown("*https://a.dev*").links())
    }

    @Test
    fun onlyWebLinksCanBeTapped() {
        for (target in listOf("javascript:alert(1)", "intent://x#Intent;end", "mailto:a@b.dev", "file:///etc/passwd", "/relative")) {
            val reply = inlineMarkdown("[label]($target)")
            assertEquals(target, "label", reply.text)
            assertTrue(target, reply.links().isEmpty())
        }
    }

    @Test
    fun uppercaseSchemesAreWebLinks() {
        assertEquals(listOf("a" to "HTTPS://a.dev"), inlineMarkdown("[a](HTTPS://a.dev)").links())
    }

    @Test
    fun anUnfinishedLinkStaysText() {
        assertEquals("[half](", inlineMarkdown("[half](").text)
        assertEquals("[not a link]", inlineMarkdown("[not a link]").text)
        assertTrue(inlineMarkdown("[half](").links().isEmpty())
    }

    @Test
    fun aBareUrlLinksWithoutItsSentencePunctuation() {
        val reply = inlineMarkdown("Read https://example.com/page, then https://a.dev.")

        assertEquals("Read https://example.com/page, then https://a.dev.", reply.text)
        assertEquals(
            listOf("https://example.com/page" to "https://example.com/page", "https://a.dev" to "https://a.dev"),
            reply.links(),
        )
    }

    @Test
    fun aBareUrlKeepsParenthesesItOpenedAndDropsOnesItDidNot() {
        assertEquals("https://a.dev/w_(x)", inlineMarkdown("https://a.dev/w_(x)").links().single().second)
        assertEquals("https://a.dev/x", inlineMarkdown("(https://a.dev/x)").links().single().second)
    }

    @Test
    fun aUrlInsideAWordOrCodeSpanIsNotLinked() {
        assertTrue(inlineMarkdown("xhttps://a.dev").links().isEmpty())
        assertTrue(inlineMarkdown("`https://a.dev`").links().isEmpty())
        assertTrue(inlineMarkdown("https://").links().isEmpty())
    }

    @Test
    fun anAutolinkDropsItsAngleBrackets() {
        val reply = inlineMarkdown("Go to <https://a.dev/x> now")

        assertEquals("Go to https://a.dev/x now", reply.text)
        assertEquals(listOf("https://a.dev/x" to "https://a.dev/x"), reply.links())
        assertEquals("<mailto:a@b.dev>", inlineMarkdown("<mailto:a@b.dev>").text)
    }

    @Test
    fun aUrlInALabelDoesNotNestALink() {
        assertEquals(
            listOf("https://inner.dev" to "https://outer.dev"),
            inlineMarkdown("[https://inner.dev](https://outer.dev)").links(),
        )
    }

    @Test
    fun aBackslashKeepsPunctuationLiteral() {
        val reply = inlineMarkdown("\\[a\\](https://a.dev) \\~\\~b\\~\\~")

        assertEquals("[a](https://a.dev) ~~b~~", reply.text)
    }

    // Strikethrough

    @Test
    fun tildesStrikeThrough() {
        val struck = { source: String ->
            inlineMarkdown(source).styled { it.textDecoration == TextDecoration.LineThrough }
        }

        assertEquals(listOf("gone"), struck("~~gone~~ kept"))
        assertEquals(listOf("gone"), struck("~gone~ kept"))
        assertEquals(emptyList<String>(), struck("about ~5 min and ~ 6"))
        assertEquals(emptyList<String>(), struck("~~~gone~~~"))
    }

    @Test
    fun anOpenStrikeShowsItsTildes() {
        assertEquals("~~half", inlineMarkdown("~~half").text)
    }

    @Test
    fun strikethroughNestsWithOtherMarks() {
        val reply = inlineMarkdown("~~*both*~~")

        assertEquals("both", reply.text)
        assertEquals(listOf("both"), reply.styled { it.fontStyle == FontStyle.Italic })
        assertEquals(listOf("both"), reply.styled { it.textDecoration == TextDecoration.LineThrough })
    }

    // Tables

    private fun table(source: String) = MarkdownBlock.parse(source).single() as MarkdownBlock.Table

    @Test
    fun aTableKeepsItsHeaderRowsAndAlignment() {
        val parsed = table("| Name | Cost | Note |\n| :--- | ---: | :-: |\n| Ada | 1 | ok |\n| Kev | 2 | fine |")

        assertEquals(listOf("Name", "Cost", "Note"), parsed.header)
        assertEquals(listOf(TextAlign.Start, TextAlign.End, TextAlign.Center), parsed.align)
        assertEquals(listOf(listOf("Ada", "1", "ok"), listOf("Kev", "2", "fine")), parsed.rows)
    }

    @Test
    fun outerPipesAreOptionalAndEscapedPipesStayInTheCell() {
        val parsed = table("a | b\n--|--\n1 \\| 2 | 3")

        assertEquals(listOf("a", "b"), parsed.header)
        assertEquals(listOf(listOf("1 | 2", "3")), parsed.rows)
    }

    @Test
    fun rowsAreFittedToTheHeader() {
        val parsed = table("| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |")

        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), parsed.rows)
    }

    @Test
    fun aTableEndsAtABlankLineOrAnotherBlockAndInterruptsAParagraph() {
        val blocks = MarkdownBlock.parse("Before\n| a |\n|---|\n| 1 |\n# Next\n\n| b |\n|---|\n\nAfter")

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("Before"),
                MarkdownBlock.Table(listOf("a"), listOf(TextAlign.Start), listOf(listOf("1"))),
                MarkdownBlock.Heading(1, "Next"),
                MarkdownBlock.Table(listOf("b"), listOf(TextAlign.Start), emptyList()),
                MarkdownBlock.Paragraph("After"),
            ),
            blocks,
        )
    }

    @Test
    fun aMismatchedDelimiterIsNotATable() {
        assertEquals(listOf(MarkdownBlock.Paragraph("a | b\n---")), MarkdownBlock.parse("a | b\n---"))
        assertEquals(listOf(MarkdownBlock.Paragraph("| a | b |")), MarkdownBlock.parse("| a | b |"))
        assertEquals(listOf(MarkdownBlock.Paragraph("x\n---")), MarkdownBlock.parse("x\n---"))
    }

    @Test
    fun aStreamingHeaderStaysTextUntilItsDelimiterIsComplete() {
        assertEquals(listOf(MarkdownBlock.Paragraph("| a | b |\n|--")), MarkdownBlock.parse("| a | b |\n|--"))
        assertTrue(MarkdownBlock.parse("| a | b |\n|--|--").single() is MarkdownBlock.Table)
    }

    @Test
    fun tablesInsideCodeStayCode() {
        assertEquals(listOf(MarkdownBlock.Code("| a |\n|---|")), MarkdownBlock.parse("```\n| a |\n|---|\n```"))
    }

    @Test
    fun aMermaidDiagramStaysACodeBlock() {
        assertEquals(listOf(MarkdownBlock.Code("graph TD\n  A --> B")), MarkdownBlock.parse("```mermaid\ngraph TD\n  A --> B\n```"))
    }

    // Notices

    private fun user(kind: String?, name: String? = null, source: String? = null) = Message(
        id = "m",
        role = "user",
        content = JsonPrimitive("Run the report"),
        origin = kind?.let { MessageOrigin(kind = it, name = name, source = source) },
    )

    @Test
    fun messagesTheOwnerDidNotWriteCollapseUnderTheirOrigin() {
        assertEquals("Automation · Digest", noticeTitle(user("task", "Digest")))
        assertEquals("Automation · Deploy, event from GitHub", noticeTitle(user("event", "Deploy", "GitHub")))
        assertEquals("Automation · Deploy, event from a deleted source", noticeTitle(user("event", "Deploy")))
        assertEquals("Script automation · Nightly", noticeTitle(user("script", "Nightly")))
        assertEquals("Credential · ACME_TOKEN", noticeTitle(user("credential", "ACME_TOKEN")))
        assertEquals("Bot request", noticeTitle(user("bot_request")))
        assertEquals("From Kev", noticeTitle(user("bot", "Kev")))
        listOf("task", "event", "script", "credential", "bot_request", "bot").forEach { assertTrue(it, isNotice(user(it, "x"))) }
    }

    @Test
    fun theOwnersOwnMessagesAreNotNotices() {
        assertFalse(isNotice(user(null)))
        assertFalse(isNotice(user("owner")))
        assertNull(noticeTitle(user("owner")))
        assertEquals("You", messageLabel(user("owner")))
    }

    @Test
    fun onlyUserRowsAreNotices() {
        assertFalse(isNotice(user("task", "Digest").copy(role = "assistant")))
    }

    @Test
    fun noticesStayInTheChatButJobMessagesDoNot() {
        assertTrue(ChatRow.shownInChat(user("task", "Digest")))
        assertFalse(ChatRow.shownInChat(user("coding", "fix")))
    }
}
