package dev.crumble.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 6.dp
val ROW_GAP = 3.dp

/** Android's page color; cards sit on it with `cardColor`. */
val pageColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
val cardColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest

/** The darker sheet under Messages' conversation list, and its chats. */
val sheetColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest

/** Rows in a group share one rounded outline, like the system Settings app. */
fun groupedShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) OUTER_CORNER else INNER_CORNER,
    topEnd = if (index == 0) OUTER_CORNER else INNER_CORNER,
    bottomStart = if (index == count - 1) OUTER_CORNER else INNER_CORNER,
    bottomEnd = if (index == count - 1) OUTER_CORNER else INNER_CORNER,
)

/** Fixed hues, as in the system Settings app, so tiles stay colored on a neutral wallpaper. */
enum class Tone(val container: Color, val content: Color) {
    Blue(Color(0xFF7BD3FF), Color(0xFF00405A)),
    Lavender(Color(0xFFB4C8FF), Color(0xFF12337A)),
    Pink(Color(0xFFFFB0E6), Color(0xFF6E0052)),
    Orange(Color(0xFFFFB877), Color(0xFF5A2A00)),
    Green(Color(0xFF9FE3B0), Color(0xFF0B4A24)),
}

@Composable
fun ToneTile(tone: Tone, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).background(tone.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tone.content) { content() }
    }
}

@Composable
fun IconTile(icon: ImageVector, tone: Tone) {
    ToneTile(tone) { Icon(icon, contentDescription = null) }
}

/** A tile with a name's first letter, for rows that have no avatar or icon of their own. */
@Composable
fun LetterTile(name: String, tone: Tone = Tone.Lavender) {
    ToneTile(tone) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun GroupedRow(
    title: String,
    shape: Shape,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    leading: @Composable (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = cardColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 2, fontFamily: FontFamily? = null, fontWeight: FontWeight? = null) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun RowSubtitle(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    fontFamily: FontFamily? = null,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, fontFamily = fontFamily, maxLines = maxLines, overflow = overflow)
}

/**
 * A grouped row for what GroupedRow can't hold: trailing controls, extra lines, and content below.
 * `onClick` covers the head of the row, and the content below as well with `clickBelow`.
 */
@Composable
fun GroupedItem(
    shape: Shape,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    clickBelow: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val click = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Surface(shape = shape, color = cardColor, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = if (clickBelow) click else Modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (clickBelow) Modifier else click)
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                leading?.invoke()
                Column(modifier = Modifier.weight(1f), content = content)
                trailing()
            }
            if (below != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = below,
                )
            }
        }
    }
}

/** A line of text in a group: an empty state, a note, or an error. */
@Composable
fun GroupedNote(text: String, shape: Shape, isError: Boolean = false, fontFamily: FontFamily? = null) {
    Surface(shape = shape, color = cardColor, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = fontFamily,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Collects a group's rows, so each gets its groupedShape whichever rows are showing. */
class GroupScope {
    internal val rows = mutableListOf<@Composable (Shape) -> Unit>()

    fun row(content: @Composable (Shape) -> Unit) {
        rows += content
    }

    fun note(text: String, isError: Boolean = false, fontFamily: FontFamily? = null) {
        row { shape -> GroupedNote(text, shape, isError, fontFamily) }
    }

    /** The row that adds to a group, like New group chat on Home. */
    fun add(title: String, enabled: Boolean = true, onClick: () -> Unit) {
        row { shape ->
            GroupedRow(title = title, shape = shape, onClick = onClick, enabled = enabled, leading = { IconTile(Icons.Default.Add, Tone.Lavender) })
        }
    }
}

@Composable
fun GroupedRows(build: GroupScope.() -> Unit) {
    val rows = GroupScope().apply(build).rows
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        rows.forEachIndexed { index, row -> row(groupedShape(index, rows.size)) }
    }
}

/** A flat row on the sheet, like a conversation in Messages. */
@Composable
fun ConversationRow(
    title: String,
    onClick: () -> Unit,
    preview: String? = null,
    enabled: Boolean = true,
    leading: @Composable () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = 84.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                preview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A plain card of content, for forms and switches. */
@Composable
fun GroupCard(shape: Shape = RoundedCornerShape(OUTER_CORNER), content: @Composable () -> Unit) {
    Surface(shape = shape, color = cardColor, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** The search pill at the top of Messages and Settings. */
@Composable
fun SearchPill(
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
