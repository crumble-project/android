package dev.crumble.android.model

data class MentionMatch(
    val range: IntRange,
    val start: Int,
    val length: Int,
    val query: String,
)

/** Find an unfinished @name at the caret, including names containing spaces, but never emails. */
fun mentionQuery(
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
): MentionMatch? {
    if (selectionStart != selectionEnd || selectionStart < 0 || selectionStart > text.length) return null
    val prefix = text.substring(0, selectionStart)
    val at = prefix.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0) {
        val prev = prefix[at - 1]
        if (!prev.isWhitespace() && !isPunctuation(prev)) return null
    }
    val query = prefix.substring(at + 1)
    if (query.contains('\n') || query.contains('\uFFFC')) return null
    val len = selectionStart - at
    return MentionMatch(
        range = at until selectionStart,
        start = at,
        length = len,
        query = query,
    )
}

private fun isPunctuation(c: Char): Boolean {
    return when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }
}

/** Inline completion stays separate from the editable draft. */
data class MentionCompletion(
    val range: IntRange,
    val start: Int,
    val length: Int,
    val query: String,
    val bots: List<Agent>,
) {
    companion object {
        fun find(
            text: String,
            selectionStart: Int,
            selectionEnd: Int = selectionStart,
            bots: List<Agent>,
        ): MentionCompletion? {
            if (selectionStart != text.length || selectionStart != selectionEnd) return null
            val match = mentionQuery(text, selectionStart, selectionEnd) ?: return null
            if (match.query.lastOrNull()?.isWhitespace() == true) return null
            val matches = bots
                .filter { it.name.lowercase().startsWith(match.query.lowercase()) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            if (matches.isEmpty()) return null
            return MentionCompletion(
                range = match.range,
                start = match.start,
                length = match.length,
                query = match.query,
                bots = matches,
            )
        }
    }
}

data class NewlineInsertion(
    val text: String,
    val caret: Int,
)

/** Replaces the selection, or appends when there is none, with a line break; returns the text and the caret after it. */
fun insertingNewline(into: String, replacing: IntRange? = null): NewlineInsertion {
    val start = (replacing?.first ?: into.length).coerceIn(0, into.length)
    val endExclusive = ((replacing?.last?.plus(1)) ?: into.length).coerceIn(start, into.length)
    val edited = into.substring(0, start) + "\n" + into.substring(endExclusive)
    return NewlineInsertion(text = edited, caret = start + 1)
}
