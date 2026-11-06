package dev.crumble.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.ActivityHeadline
import dev.crumble.android.model.ActivityKind
import dev.crumble.android.model.ActivityRecord
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.activityDays
import dev.crumble.android.model.clock
import dev.crumble.android.model.detailText
import dev.crumble.android.model.failed
import dev.crumble.android.model.headline
import dev.crumble.android.model.preview

private fun kindIcon(kind: String): ImageVector = when (kind) {
    ActivityKind.ToolResult.value -> Icons.Default.SubdirectoryArrowRight
    ActivityKind.McpCall.value -> Icons.Default.Power
    ActivityKind.BotMessage.value -> Icons.Default.Forum
    ActivityKind.Change.value -> Icons.Default.History
    else -> Icons.Default.Build
}

@Composable
internal fun ActivityPanel(model: AppModel) {
    val feed = model.activityFeed
    Column(modifier = Modifier.fillMaxSize()) {
        ActivityFilters(model)
        if (feed.error.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { SheetErrorBanner(feed.error) }
        }
        when {
            feed.items.isNotEmpty() || feed.nextCursor != null -> ActivityList(model)
            feed.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            feed.error.isEmpty() -> EmptyWorkspacePlaceholder(
                icon = Icons.Default.Timeline,
                title = "No Activity",
                description = if (feed.kind == null) "Tool calls and bot messages will appear here." else "Nothing of this kind yet.",
            )
        }
    }
}

@Composable
private fun ActivityFilters(model: AppModel) {
    val feed = model.activityFeed
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = feed.kind == null, onClick = { model.filterActivity(kind = null) }, label = { Text("All") })
            for (kind in ActivityKind.entries) {
                FilterChip(
                    selected = feed.kind == kind.value,
                    onClick = { model.filterActivity(kind = kind.value) },
                    label = { Text(kind.label) },
                    leadingIcon = { Icon(kindIcon(kind.value), contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        BotFilter(model)
    }
}

@Composable
private fun BotFilter(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val selected = model.agents.firstOrNull { it.id == model.activityFeed.botID }
    Box {
        OutlinedButton(onClick = { open = true }) {
            if (selected != null) {
                BotAvatarView(selected, size = 18.dp)
                Box(modifier = Modifier.size(6.dp))
            }
            Text(selected?.name ?: "All bots", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All bots") },
                onClick = {
                    open = false
                    model.filterActivity(botID = null)
                },
            )
            for (bot in model.agents) {
                DropdownMenuItem(
                    text = { Text(bot.name) },
                    leadingIcon = { BotAvatarView(bot, size = 18.dp) },
                    onClick = {
                        open = false
                        model.filterActivity(botID = bot.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActivityList(model: AppModel) {
    val feed = model.activityFeed
    val days = remember(feed.items) { activityDays(feed.items) }
    val bots = remember(model.agents) { model.agents.associateBy { it.id } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        for (day in days) {
            item(key = "day:${day.key}") {
                Box(modifier = Modifier.padding(top = 8.dp)) { GroupLabel(day.label) }
            }
            itemsIndexed(day.items, key = { _, activity -> activity.id }) { index, activity ->
                ActivityRow(model, activity, bots[activity.agentId], groupedShape(index, day.items.size))
            }
        }
        if (feed.nextCursor != null) {
            item(key = "older") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (feed.olderError.isNotEmpty()) {
                        Text(feed.olderError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { model.loadOlderActivity() }, enabled = !feed.loadingOlder) {
                        if (feed.loadingOlder) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Box(modifier = Modifier.size(8.dp))
                        }
                        Text("Show older activity")
                    }
                }
            }
        }
    }
}

@Composable
private fun headlineText(headline: ActivityHeadline): AnnotatedString {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(headline.lead) }
        if (headline.verb.isNotEmpty()) withStyle(SpanStyle(color = muted)) { append(" ${headline.verb}") }
        append(" ")
        val style = when {
            headline.code -> SpanStyle(fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
            headline.muted -> SpanStyle(color = muted)
            else -> SpanStyle(fontWeight = FontWeight.Medium)
        }
        withStyle(style) { append(headline.target) }
    }
}

@Composable
private fun ActivityMark(activity: ActivityRecord, bot: Agent?) {
    Box(modifier = Modifier.size(48.dp)) {
        if (bot != null) BotAvatarView(bot, size = 44.dp) else IconTile(Icons.Default.Psychology, Tone.Lavender)
        Surface(
            shape = CircleShape,
            color = sheetColor,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).align(Alignment.BottomEnd),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(kindIcon(activity.kind), contentDescription = null, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun ActivityRow(model: AppModel, activity: ActivityRecord, bot: Agent?, shape: Shape) {
    var expanded by remember(activity.id) { mutableStateOf(false) }
    val preview = remember(activity.id) { activity.preview() }
    GroupedItem(
        shape = shape,
        onClick = { expanded = !expanded },
        clickBelow = true,
        leading = { ActivityMark(activity, bot) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(activity.clock(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activity.failed) ErrorBadge()
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        below = if (expanded) {
            { ActivityDetails(model, activity) }
        } else {
            null
        },
    ) {
        Text(headlineText(activity.headline()), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (preview.isNotEmpty()) ActivityPreview(activity, preview)
    }
}

@Composable
private fun ActivityPreview(activity: ActivityRecord, text: String) {
    val message = activity.kind == ActivityKind.BotMessage.value
    RowSubtitle(text, maxLines = if (message) 2 else 1, fontFamily = if (message) null else FontFamily.Monospace)
}

@Composable
private fun ErrorBadge() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(50)) {
        Text(
            "Error",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ActivityDetails(model: AppModel, activity: ActivityRecord) {
    val change = activity.change
    if (change != null) {
        ChangeDiff(change)
        RevertChangeButton(model, change)
        return
    }
    val text = remember(activity.id) { activity.detailText() }
    Surface(color = sheetColor, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(10.dp),
        )
    }
    if (activity.agentId.isNotEmpty()) {
        OutlinedButton(onClick = { model.selectAgent(activity.agentId) }) { Text("Open Chat") }
    }
}
