package dev.crumble.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.crumble.android.model.AppModel
import dev.crumble.android.generated.JobHistoryItem
import dev.crumble.android.generated.JobStepItem
import dev.crumble.android.model.id
import dev.crumble.android.model.JobKind
import dev.crumble.android.model.JobPanel
import dev.crumble.android.model.JobRef
import dev.crumble.android.model.JobSummary
import dev.crumble.android.model.jobWorking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun JobKind.icon(): ImageVector = if (this == JobKind.coding) Icons.Default.Build else Icons.Default.DesktopWindows

private fun stepIcon(tool: String): ImageVector = when (tool) {
    "terminal" -> Icons.Default.Terminal
    "file" -> Icons.Default.Description
    "pencil" -> Icons.Default.Edit
    "pointer" -> Icons.Default.TouchApp
    else -> Icons.Default.Build
}

/** Whether a job works, as its card and panel show it. */
@Composable
private fun JobStatusLabel(working: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (working) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Text(
            text = if (working) "Working" else "Idle",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JobRow(kind: JobKind, name: String, subtitle: String, working: Boolean, shape: Shape, onClick: () -> Unit) {
    GroupedItem(
        shape = shape,
        onClick = onClick,
        leading = { IconTile(kind.icon(), Tone.Lavender) },
        trailing = { JobStatusLabel(working) },
    ) {
        RowTitle(name, maxLines = 1)
        RowSubtitle(subtitle, maxLines = 1)
    }
}

/** A job in the chat; the job list's status wins over the one its tool result reported. */
@Composable
fun JobCard(model: AppModel, job: JobRef) {
    val status = model.jobs.firstOrNull { it.name == job.name }?.status ?: job.status
    JobRow(job.kind, job.name, job.kind.label, jobWorking(status), groupedShape(0, 1)) { model.openJob(job) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobDialog(
    onClose: () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            containerColor = pageColor,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = pageColor, scrolledContainerColor = pageColor),
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    },
                    actions = { actions() },
                )
            },
            content = content,
        )
    }
}

@Composable
private fun JobLoadError(text: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
fun JobListDialog(model: AppModel) {
    val onClose = { model.closeJobList() }
    JobDialog(onClose = onClose, title = { Text("Jobs") }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            model.selectedAgent?.takeIf { "desktop" in it.tools.orEmpty() }?.let { bot ->
                DesktopPreviewSection(model, bot.id)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                if (model.jobsFailed) {
                    item { JobLoadError("Couldn't load jobs.", onRetry = { model.openJobList() }) }
                }
                if (!model.jobsLoaded && !model.jobsFailed) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                }
                if (model.jobsLoaded && model.jobs.isEmpty()) {
                    item { Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                itemsIndexed(model.jobs, key = { _, job -> "${job.kind}:${job.name}" }) { index, job ->
                    JobRow(
                        job.kind,
                        job.name,
                        listOf(job.kind.label, job.subtitle).filter { it.isNotEmpty() }.joinToString(" · "),
                        jobWorking(job.status),
                        groupedShape(index, model.jobs.size),
                    ) {
                        model.openJob(job.ref)
                    }
                }
            }
        }
    }
}

@Composable
private fun JobTitle(job: JobRef, current: JobSummary?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(job.kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(job.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = current?.subtitle.orEmpty()
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun JobDownloadButton(model: AppModel, panel: JobPanel) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            model.downloadJobTranscript { markdown ->
                withContext(Dispatchers.IO) {
                    val stream = checkNotNull(context.contentResolver.openOutputStream(uri)) { "The file could not be opened." }
                    stream.use { it.write(markdown.toByteArray()) }
                }
            }
        }
    }
    if (panel.downloading) {
        CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
    } else {
        IconButton(onClick = { save.launch("${panel.job.name}.md") }) {
            Icon(Icons.Default.Download, contentDescription = "Download transcript")
        }
    }
}

@Composable
private fun JobStep(step: JobStepItem, shape: Shape) {
    var open by remember(step.id) { mutableStateOf(false) }
    val result = step.result
    GroupedItem(
        shape = shape,
        onClick = { open = !open },
        enabled = result != null,
        leading = {
            ToneTile(Tone.Lavender) {
                if (result == null) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                } else {
                    Icon(stepIcon(step.icon), contentDescription = null)
                }
            }
        },
        trailing = {
            if (result?.isError == true) {
                Text("Failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (result != null) {
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        below = if (open && result != null) {
            {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(sheetColor, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        text = result.content.trimEnd('\n').ifEmpty { "No output." },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        } else {
            null
        },
    ) {
        RowTitle(step.label, maxLines = 1)
        step.subject?.takeIf { it.isNotEmpty() }?.let { RowSubtitle(it, maxLines = 1, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun JobItemView(item: JobHistoryItem, shape: Shape) {
    when (item) {
        is JobHistoryItem.Variant1 -> Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            GroupLabel("Task")
            GroupCard { Text(item.value.text, style = MaterialTheme.typography.bodyMedium) }
        }
        is JobHistoryItem.Variant2 -> MarkdownText(text = item.value.text)
        is JobHistoryItem.Variant3 -> JobStep(item.value, shape)
    }
}

// With the list's ROW_GAP, this keeps 16dp between blocks; consecutive steps stay one group.
private val BLOCK_GAP = 13.dp

/** A job's task, replies and tool steps, read-only, over the chat. */
@Composable
fun JobPanelDialog(model: AppModel, panel: JobPanel) {
    val job = panel.job
    val current = model.jobs.firstOrNull { it.ref == job }
    val working = jobWorking(current?.status)
    val items = panel.items
    JobDialog(
        onClose = { model.closeJob() },
        title = { JobTitle(job, current) },
        actions = {
            JobStatusLabel(working)
            if (job.kind == JobKind.desktop) {
                IconButton(onClick = { model.openBotDesktop() }) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = "Bot desktop")
                }
            }
            JobDownloadButton(model, panel)
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (panel.downloadFailed) {
                Text(
                    text = "Couldn't download the transcript. Try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            current?.question?.let { question ->
                Box(modifier = Modifier.padding(16.dp)) { JobQuestionAnswer(model, job.name, question) }
            }
            // Reverse layout keeps the newest step in view as the job works.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP, Alignment.Bottom),
            ) {
                if (working) {
                    item(key = "working") { Text("Working…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = BLOCK_GAP)) }
                }
                itemsIndexed(items.asReversed(), key = { _, item -> item.id }) { reversedIndex, item ->
                    val index = items.lastIndex - reversedIndex
                    val afterStep = item is JobHistoryItem.Variant3 && items.getOrNull(index - 1) is JobHistoryItem.Variant3
                    val beforeStep = item is JobHistoryItem.Variant3 && items.getOrNull(index + 1) is JobHistoryItem.Variant3
                    val position = if (afterStep) 1 else 0
                    Box(modifier = Modifier.fillMaxWidth().padding(top = if (afterStep) 0.dp else BLOCK_GAP)) {
                        JobItemView(item, groupedShape(position, position + if (beforeStep) 2 else 1))
                    }
                }
                if (panel.loaded && items.isEmpty()) {
                    item(key = "empty") { Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (panel.failed) {
                    item(key = "failed") { JobLoadError("Couldn't load this ${job.kind.label.lowercase()}.", onRetry = { model.retryJobHistory() }) }
                }
                if (!panel.loaded && !panel.failed) {
                    item(key = "loading") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                }
                if (panel.hasMore) {
                    item(key = "earlier") {
                        TextButton(enabled = !panel.loadingEarlier, onClick = { model.loadEarlierJobActivity() }) { Text("Load earlier activity") }
                    }
                }
            }
        }
    }
}
