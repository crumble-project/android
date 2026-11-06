package dev.crumble.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.LearnedChange
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.DiffLine
import dev.crumble.android.model.SKILLS_ALL
import dev.crumble.android.model.SKILLS_SHARED
import dev.crumble.android.model.changeLabel
import dev.crumble.android.model.diff
import dev.crumble.android.model.revertPrompt
import kotlinx.serialization.json.JsonObject

private val ADDED = Color(0xFF16A34A)

/** What happened and to what, such as "Kev's review changed a skill · Triage". */
private fun changeText(change: LearnedChange, botName: String?): String =
    changeLabel(CrumbleJson.encodeToJsonElement(LearnedChange.serializer(), change) as JsonObject, botName)

@Composable
fun DiffView(lines: List<DiffLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp),
    ) {
        for (line in lines) {
            val color = when (line.kind) {
                '-' -> MaterialTheme.colorScheme.error
                '+' -> ADDED
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val tint = if (line.kind == ' ') Color.Transparent else color.copy(alpha = 0.1f)
            Text(
                text = "${line.kind} ${line.text}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                modifier = Modifier.fillMaxWidth().background(tint).padding(horizontal = 10.dp),
            )
        }
    }
}

@Composable
fun ChangeDiff(change: LearnedChange) {
    val lines = remember(change.id) { change.diff() }
    DiffView(lines)
}

/** Reverts a change after confirming; the server records the revert as a change of its own. */
@Composable
fun RevertChangeButton(model: AppModel, change: LearnedChange) {
    var confirming by remember(change.id) { mutableStateOf(false) }
    Box(contentAlignment = Alignment.CenterStart) {
        TextButton(enabled = "change:${change.id}" !in model.pendingMutations, onClick = { confirming = true }) { Text("Revert") }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Revert this change?",
            text = revertPrompt(change),
            confirmLabel = "Revert",
            onConfirm = { model.revertChange(change) },
            onDismiss = { confirming = false },
            destructive = false,
        )
    }
}

@Composable
private fun ChangeRow(model: AppModel, change: LearnedChange) {
    var expanded by remember(change.id) { mutableStateOf(false) }
    val bot = model.agents.firstOrNull { it.id == change.agentId }
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(changeText(change, bot?.name), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (expanded) {
                ChangeDiff(change)
                RevertChangeButton(model, change)
            }
        }
    }
}

/** Skill and memory changes, newest first. */
@Composable
fun ChangeHistoryDialog(model: AppModel) {
    var scope by remember { mutableStateOf(SKILLS_ALL) }
    val shown = model.changes.filter { change ->
        scope == SKILLS_ALL || scope == (change.agentId ?: SKILLS_SHARED)
    }
    JobDialog(onClose = { model.closeChanges() }, title = { Text("Changes") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SheetErrorBanner(model.errorText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(scope == SKILLS_ALL, { scope = SKILLS_ALL }, { Text("All") })
                    FilterChip(scope == SKILLS_SHARED, { scope = SKILLS_SHARED }, { Text("Shared") })
                    for (bot in model.agents) {
                        FilterChip(scope == bot.id, { scope = bot.id }, { Text(bot.name) })
                    }
                }
            }
            if (model.changesError.isNotEmpty()) {
                item { Text(model.changesError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (!model.changesLoading && model.changesError.isEmpty() && shown.isEmpty()) {
                item { Text("Skill and memory changes will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(shown, key = { it.id }) { change -> ChangeRow(model, change) }
            if (model.changesLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (model.changesHasMore) {
                item { OutlinedButton(onClick = { model.loadMoreChanges() }) { Text("Show older changes") } }
            }
        }
    }
}
