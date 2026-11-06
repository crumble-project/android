package dev.crumble.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.ScriptRun
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.EventSource
import dev.crumble.android.model.EventSourceDraft
import dev.crumble.android.model.MAX_COMMAND_CHARS
import dev.crumble.android.model.MAX_EVENT_SOURCE_NAME_CHARS
import dev.crumble.android.model.Route
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.ScheduleStatus
import dev.crumble.android.model.TaskDraft
import dev.crumble.android.model.TaskRun
import dev.crumble.android.model.TaskTab
import dev.crumble.android.model.activityTime
import dev.crumble.android.model.canRunScripts
import dev.crumble.android.model.describeTiming
import dev.crumble.android.model.finished
import dev.crumble.android.model.listedSchedules
import dev.crumble.android.model.scriptRunDuration
import dev.crumble.android.model.status
import dev.crumble.android.model.taskRuns

private val SUCCESS = Color(0xFF059669)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksPanel(model: AppModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = model.taskTab.ordinal, containerColor = Color.Transparent) {
            for (tab in TaskTab.entries) {
                Tab(
                    selected = model.taskTab == tab,
                    onClick = { model.selectTaskTab(tab) },
                    text = { Text(tab.label, maxLines = 1) },
                )
            }
        }
        when (model.taskTab) {
            TaskTab.Tasks -> TaskList(model)
            TaskTab.Runs -> RunList(model)
            TaskTab.EventSources -> EventSourceList(model)
        }
    }
}

@Composable
private fun TaskList(model: AppModel) {
    if (model.agents.isEmpty()) {
        EmptyWorkspacePlaceholder(
            icon = Icons.Default.Schedule,
            title = "No Tasks",
            description = "Create an agent, then give it a task to run once or on a schedule.",
        )
        return
    }
    val listed = listedSchedules(model.schedules)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(model.agents, key = { it.id }) { agent ->
            val tasks = listed.filter { it.agentId == agent.id }
            RowsSection(
                title = agent.name,
                footer = "Runs in ${agent.name}’s chat, once or on a schedule.",
            ) {
                if (tasks.isEmpty()) note("No tasks yet.")
                for (schedule in tasks) {
                    row { shape -> TaskRow(model, schedule, agent, shape) }
                }
                add("New Task") { model.openTaskForm("", agentID = agent.id) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(model: AppModel, schedule: Schedule, agent: Agent, shape: Shape) {
    val isPending = model.schedulePending(schedule.id)
    var confirmingDelete by remember(schedule.id) { mutableStateOf(false) }
    if (confirmingDelete) {
        ConfirmDialog(
            title = "Delete ${schedule.name}?",
            text = "The task stops running. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { model.deleteSchedule(schedule.id) },
            onDismiss = { confirmingDelete = false },
        )
    }
    GroupedItem(
        shape = shape,
        leading = { IconTile(routeIcon(Route.Tasks), Tone.Lavender) },
        below = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { model.runSchedule(schedule.id) },
                    enabled = schedule.enabled && !isPending && !model.pendingMutations.contains("schedule-run:${schedule.id}"),
                ) {
                    Text("Run Now")
                }
                if (!schedule.finished) {
                    TextButton(onClick = { model.toggleSchedule(schedule.id) }, enabled = !isPending) {
                        Text(if (schedule.enabled) "Pause" else "Resume")
                    }
                }
                TextButton(onClick = { model.openTaskForm(schedule.id) }, enabled = !isPending) {
                    Text("Edit")
                }
                TextButton(onClick = { confirmingDelete = true }, enabled = !isPending) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RowTitle(schedule.name, modifier = Modifier.weight(1f, fill = false))
            if (schedule.kind == "script") Pill("Script")
        }
        RowSubtitle(
            text = if (schedule.kind == "script") schedule.command.orEmpty() else schedule.prompt,
            maxLines = 2,
            fontFamily = if (schedule.kind == "script") FontFamily.Monospace else null,
        )
        RowSubtitle("${schedule.status.label} · ${describeTiming(schedule, model.eventSources)}")
        val nextRun = schedule.nextRunAt
        if (schedule.status == ScheduleStatus.Active && nextRun != null) {
            RowSubtitle("Next run ${activityTime(nextRun)}")
        }
        val creator = schedule.createdByAgentId
        if (creator != null && creator != agent.id) {
            val creatorName = model.agents.firstOrNull { it.id == creator }?.name
            if (creatorName != null) RowSubtitle("Scheduled by $creatorName")
        }
    }
}

@Composable
private fun RunList(model: AppModel) {
    val runs = taskRuns(model.schedules, model.executions, model.scriptRuns)
    var open by remember { mutableStateOf<TaskRun?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (model.taskRunsError.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SheetErrorBanner(model.taskRunsError)
            }
        }
        when {
            runs.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                itemsIndexed(runs, key = { _, run -> run.id }) { index, run ->
                    val bot = model.agents.firstOrNull { it.id == run.agentId }
                    RunRow(run, bot, groupedShape(index, runs.size)) {
                        when {
                            run.script != null -> open = run
                            bot != null -> model.selectAgent(bot.id)
                        }
                    }
                }
            }
            model.taskRunsLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            model.taskRunsError.isEmpty() -> EmptyWorkspacePlaceholder(
                icon = Icons.Default.Schedule,
                title = "No runs yet",
                description = "Each time a task runs, it shows up here.",
            )
        }
    }
    open?.let { run ->
        val script = run.script
        if (script != null) RunDetailsDialog(run, script) { open = null }
    }
}

@Composable
private fun RunRow(run: TaskRun, bot: Agent?, shape: Shape, onOpen: () -> Unit) {
    GroupedItem(
        shape = shape,
        onClick = onOpen,
        leading = { if (bot != null) BotAvatarView(bot, size = 44.dp) else IconTile(routeIcon(Route.Tasks), Tone.Lavender) },
        trailing = { RunStatus(run) },
    ) {
        RowTitle(run.schedule?.name ?: "Deleted task", maxLines = 1)
        RowSubtitle(
            text = listOfNotNull(
                bot?.name ?: "Deleted bot",
                activityTime(run.startedAt),
                run.script?.let(::scriptRunDuration),
            ).joinToString(" · "),
            maxLines = 1,
        )
    }
}

@Composable
private fun RunStatus(run: TaskRun) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            run.running -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            run.failed -> Icon(Icons.Default.Cancel, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            else -> Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = SUCCESS)
        }
        Text(run.label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RunDetailsDialog(run: TaskRun, script: ScriptRun, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(run.schedule?.name ?: "Deleted task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = listOf(
                        script.status.replace("_", " "),
                        "Exit code: ${script.exitCode ?: "—"}",
                        activityTime(script.startedAt),
                        "Duration: ${scriptRunDuration(script)}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                script.error?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (script.droppedBytes > 0) {
                    Text(
                        text = "${script.droppedBytes} bytes omitted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = sheetColor) {
                    Text(
                        text = script.output.ifEmpty { "No output" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun EventSourceList(model: AppModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            RowsSection(
                title = "Event sources",
                footer = "Each source is one webhook address. Tasks that use a source run when an event arrives there.",
            ) {
                if (model.eventSourcesError.isNotEmpty()) note(model.eventSourcesError, isError = true)
                if (model.eventSourcesLoaded && model.eventSources.isEmpty()) note("No event sources yet.")
                for (source in model.eventSources) {
                    row { shape -> EventSourceRow(model, source, shape) }
                }
                add("Add source") { model.openEventSourceForm() }
            }
        }
    }
}

@Composable
internal fun EventSourceRow(model: AppModel, source: EventSource, shape: Shape) {
    val deleting = model.deletingEventSources.contains(source.id)
    var confirming by remember { mutableStateOf(false) }
    GroupedItem(
        shape = shape,
        leading = { LetterTile(source.name) },
        trailing = {
            if (deleting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { confirming = true }, enabled = source.usedBy == 0L) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        },
    ) {
        RowTitle("${source.name} (used by ${source.usedBy})", maxLines = 1)
        if (source.usedBy > 0) RowSubtitle("Remove the tasks that use it first")
        model.eventSourceErrors[source.id]?.let { RowSubtitle(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete ${source.name}?") },
            text = { Text("Its webhook address stops working. Events that already arrived are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        model.deleteEventSource(source.id)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun EventSourceDialog(model: AppModel, draft: EventSourceDraft) {
    val created = draft.created
    if (created != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${created.name} address") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CopyField("Webhook address", created.address, sensitive = true)
                    Text(
                        text = "Shown once. Paste it into the provider's webhook settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = { model.closeEventSourceForm() }) { Text("Done") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { model.closeEventSourceForm() },
        title = { Text("Add event source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Crumble creates the source on the relay and shows its webhook address once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SheetErrorBanner(draft.error)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { model.eventSourceDraft = draft.copy(name = it.take(MAX_EVENT_SOURCE_NAME_CHARS)) },
                    label = { Text("Name") },
                    placeholder = { Text("GitHub issues") },
                    singleLine = true,
                    enabled = !draft.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { model.saveEventSource() }, enabled = draft.name.isNotBlank() && !draft.saving) {
                if (draft.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { model.closeEventSourceForm() }, enabled = !draft.saving) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormDialog(
    model: AppModel,
    draft: TaskDraft,
) {
    var botMenuExpanded by remember { mutableStateOf(false) }
    val selectedBotName = model.agents.firstOrNull { it.id == draft.agentID }?.name ?: ""
    val canScript = canRunScripts(model.capabilities, model.agents.firstOrNull { it.id == draft.agentID })
    val editingScript = model.schedules.firstOrNull { it.id == draft.scheduleID }?.kind == "script"

    AlertDialog(
        onDismissRequest = {
            model.errorText = ""
            model.taskDraft = null
        },
        title = { Text(if (draft.scheduleID.isEmpty()) "New Task" else "Edit Task") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetErrorBanner(model.errorText)
                ExposedDropdownMenuBox(
                    expanded = botMenuExpanded,
                    onExpandedChange = { botMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedBotName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Runs on") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = botMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = botMenuExpanded,
                        onDismissRequest = { botMenuExpanded = false },
                    ) {
                        for (agent in model.agents) {
                            DropdownMenuItem(
                                text = { Text(agent.name) },
                                onClick = {
                                    model.pickTaskBot(agent.id)
                                    botMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { model.taskDraft = draft.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Enabled")
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { model.taskDraft = draft.copy(enabled = it) },
                    )
                }
                if (canScript || editingScript) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.kind == "prompt",
                            onClick = { model.taskDraft = draft.copy(kind = "prompt") },
                            label = { Text("Prompt") },
                        )
                        FilterChip(
                            selected = draft.kind == "script",
                            onClick = { model.taskDraft = draft.copy(kind = "script") },
                            enabled = canScript,
                            label = { Text("Script") },
                        )
                    }
                }
                if (draft.kind == "script") {
                    ScriptFields(model, draft)
                } else {
                    OutlinedTextField(
                        value = draft.prompt,
                        onValueChange = { model.taskDraft = draft.copy(prompt = it) },
                        label = { Text("Prompt") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !draft.onEvent,
                        onClick = { model.taskDraft = draft.copy(onEvent = false) },
                        label = { Text("Time") },
                    )
                    FilterChip(
                        selected = draft.onEvent,
                        onClick = { model.taskDraft = draft.copy(onEvent = true) },
                        label = { Text("Event") },
                    )
                }
                if (draft.onEvent) {
                    EventSourcePicker(model, draft)
                } else {
                    TaskTimeFields(draft) { model.taskDraft = it }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { model.saveTask() },
                enabled = !model.loading,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    model.errorText = ""
                    model.taskDraft = null
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ScriptFields(model: AppModel, draft: TaskDraft) {
    OutlinedTextField(
        value = draft.command,
        onValueChange = { model.taskDraft = draft.copy(command = it.take(MAX_COMMAND_CHARS)) },
        label = { Text("Command") },
        placeholder = { Text("./scripts/backup.sh") },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.timeoutSeconds,
        onValueChange = { model.taskDraft = draft.copy(timeoutSeconds = it) },
        label = { Text("Timeout (seconds)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Send results to the chat")
            Text(
                text = "Each finished run posts its output to the bot's chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = draft.notify,
            onCheckedChange = { model.taskDraft = draft.copy(notify = it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventSourcePicker(model: AppModel, draft: TaskDraft) {
    if (model.eventSources.isEmpty() && model.eventSourcesError.isNotEmpty()) {
        SheetErrorBanner(model.eventSourcesError)
        return
    }
    if (model.eventSourcesLoaded && model.eventSources.isEmpty()) {
        Text(
            text = "Add a source in Event sources first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = model.eventSources.firstOrNull { it.id == draft.eventSourceID }?.name ?: "Choose a source",
            onValueChange = {},
            readOnly = true,
            label = { Text("Event source") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (source in model.eventSources) {
                DropdownMenuItem(
                    text = { Text(source.name) },
                    onClick = {
                        model.taskDraft = draft.copy(eventSourceID = source.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
