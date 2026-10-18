package dev.crumble.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.MCPDraft
import dev.crumble.android.model.MCPServer
import dev.crumble.android.model.Memory
import dev.crumble.android.model.MemoryDraft
import dev.crumble.android.model.Route
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.SkillDraft
import dev.crumble.android.model.TaskDraft
import dev.crumble.android.model.activityTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun PanelView(
    model: AppModel,
    panel: Route,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (panel) {
            Route.Memory -> MemoryPanel(model)
            Route.Skills -> SkillsPanel(model)
            Route.Tasks -> TasksPanel(model)
            Route.Activity -> ActivityPanel(model)
            Route.Integrations -> IntegrationsPanel(model)
            is Route.AgentChat -> Unit
        }
    }
}

@Composable
private fun MemoryPanel(model: AppModel) {
    val sharedEnabledBots = model.agents.filter { it.sharedMemory == true }.map { it.name }
    val sharedFooter = if (sharedEnabledBots.isEmpty()) {
        "No bots use shared memory. Turn it on in a bot's profile."
    } else {
        "Used by: ${sharedEnabledBots.joinToString(", ")}"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(
                title = "Shared",
                footer = sharedFooter,
            ) {
                if (model.sharedMemories.isEmpty()) {
                    Text(
                        text = "No shared memories yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (memory in model.sharedMemories) {
                    MemoryRow(model = model, memory = memory, agentID = null)
                }
                TextButton(onClick = { model.openMemoryForm("", agentID = null) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("New Shared Memory")
                }
            }
        }

        items(model.agents, key = { it.id }) { agent ->
            val memories = model.agentMemories[agent.id].orEmpty()
            SectionCard(
                title = agent.name,
                footer = "What ${agent.name} remembers between chats.",
            ) {
                if (memories.isEmpty()) {
                    Text(
                        text = "No memories yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (memory in memories) {
                    MemoryRow(model = model, memory = memory, agentID = agent.id)
                }
                Text(
                    text = "${memories.size} personal memor${if (memories.size == 1) "y" else "ies"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { model.openMemoryForm("", agentID = agent.id) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("New Memory")
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(
    model: AppModel,
    memory: Memory,
    agentID: String?,
) {
    val isPending = memory.id.startsWith("pending-")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = memory.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { model.openMemoryForm(memory.id, agentID = agentID) },
            enabled = !isPending,
        ) {
            Text("Edit")
        }
        TextButton(
            onClick = { model.deleteMemory(memory.id, agentID = agentID) },
            enabled = !isPending,
        ) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SkillsPanel(model: AppModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(
                title = "Shared",
                footer = "Defaults apply to every bot. Enable optional shared skills in a bot’s profile. Edit default files on the server and restart it to load changes.",
            ) {
                if (model.sharedSkills.isEmpty()) {
                    Text(
                        text = "No shared skills yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (skill in model.sharedSkills) {
                    var expanded by remember(skill.id) { mutableStateOf(false) }
                    val isPending = skill.id.startsWith("pending-")
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = skill.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                val sub = if (skill.isDefault) {
                                    "Default · Every bot"
                                } else {
                                    val enabled = model.agents
                                        .filter { it.sharedSkills?.contains(skill.id) == true }
                                        .map { it.name }
                                    if (enabled.isEmpty()) "No bots enabled" else "Enabled for: ${enabled.joinToString(", ")}"
                                }
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!skill.isDefault) {
                                TextButton(
                                    onClick = { model.openSkillForm(skill.id) },
                                    enabled = !isPending,
                                ) {
                                    Text("Edit")
                                }
                                TextButton(
                                    onClick = { model.deleteSharedSkill(skill.id) },
                                    enabled = !isPending,
                                ) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            Column(
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = skill.instructions,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!skill.path.isNullOrEmpty()) {
                                    Text(
                                        text = "Source: ${skill.path}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        items(model.agents, key = { it.id }) { agent ->
            val skills = agent.skills.orEmpty()
            SectionCard(
                title = agent.name,
                footer = "Instructions only ${agent.name} follows.",
            ) {
                if (skills.isEmpty()) {
                    Text(
                        text = "No skills of its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (skill in skills) {
                    var expanded by remember(skill.id) { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            Text(
                                text = skill.instructions,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                }
                TextButton(onClick = { model.openEditor(agentID = agent.id) }) {
                    Text("Edit in Profile…")
                }
            }
        }
    }
}

@Composable
private fun TasksPanel(model: AppModel) {
    if (model.agents.isEmpty()) {
        EmptyWorkspacePlaceholder(
            icon = Icons.Default.Schedule,
            title = "No Tasks",
            description = "Create an agent, then give it a task to run once or on a schedule.",
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(model.agents, key = { it.id }) { agent ->
                val tasks = model.schedules.filter { it.agentId == agent.id }
                SectionCard(
                    title = agent.name,
                    footer = "Runs in ${agent.name}’s chat, once or on a schedule.",
                ) {
                    if (tasks.isEmpty()) {
                        Text(
                            text = "No tasks yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (schedule in tasks) {
                        val isPending = schedule.id.startsWith("pending-")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = schedule.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = schedule.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = scheduleTiming(schedule),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val creator = schedule.createdByAgentId
                            if (creator != null && creator != agent.id) {
                                val creatorName = model.agents.firstOrNull { it.id == creator }?.name
                                if (creatorName != null) {
                                    Text(
                                        text = "Scheduled by $creatorName",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { model.runSchedule(schedule.id) },
                                    enabled = schedule.enabled && !isPending,
                                ) {
                                    Text("Run Now")
                                }
                                TextButton(
                                    onClick = { model.openTaskForm(schedule.id) },
                                    enabled = !isPending,
                                ) {
                                    Text("Edit")
                                }
                                TextButton(
                                    onClick = { model.deleteSchedule(schedule.id) },
                                    enabled = !isPending,
                                ) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { model.openTaskForm("", agentID = agent.id) }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("New Task")
                    }
                }
            }
        }
    }
}

private fun scheduleTiming(schedule: Schedule): String {
    var timing = schedule.runAt ?: ""
    if (!schedule.cron.isNullOrEmpty()) {
        timing = "${schedule.cron} · ${schedule.timezone ?: ""}"
    }
    return if (schedule.enabled) timing else "Paused · $timing"
}

@Composable
private fun ActivityPanel(model: AppModel) {
    if (model.executions.isEmpty()) {
        EmptyWorkspacePlaceholder(
            icon = Icons.Default.Timeline,
            title = "No Activity",
            description = "Work from chats and tasks will appear here.",
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(model.executions, key = { it.id }) { execution ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        text = execution.status ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                Text(
                                    text = activityTime(execution.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = execution.prompt ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!execution.error.isNullOrEmpty()) {
                                Text(
                                    text = execution.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        val agentId = execution.agentId
                        if (!agentId.isNullOrEmpty()) {
                            OutlinedButton(onClick = { model.selectAgent(agentId) }) {
                                Text("Open Chat")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegrationsPanel(model: AppModel) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val shared = model.mcpServers.filter { it.agentId == null }
            SectionCard(
                title = "Shared",
                footer = "Every bot can use these. Turn one on for a bot in its profile.",
            ) {
                if (shared.isEmpty()) {
                    Text(
                        text = "No shared MCP servers yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (server in shared) {
                    MCPServerRow(
                        model = model,
                        server = server,
                        onOpenBrowser = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    )
                }
                TextButton(onClick = { model.openMCPForm("", agentID = null) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add Shared MCP Server")
                }
            }
        }

        items(model.agents, key = { it.id }) { agent ->
            val own = model.mcpServers.filter { it.agentId == agent.id }
            SectionCard(
                title = agent.name,
                footer = "Only ${agent.name} can use these, with their own sign-in.",
            ) {
                if (own.isEmpty()) {
                    Text(
                        text = "No MCP servers of its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (server in own) {
                    MCPServerRow(
                        model = model,
                        server = server,
                        onOpenBrowser = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    )
                }
                TextButton(onClick = { model.openMCPForm("", agentID = agent.id) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add MCP Server")
                }
            }
        }
    }
}

@Composable
private fun MCPServerRow(
    model: AppModel,
    server: MCPServer,
    onOpenBrowser: (String) -> Unit,
) {
    val isPending = server.id.startsWith("pending-")
    val statusText = when (server.auth) {
        "oauth" -> "Signed in with OAuth"
        "token" -> "Uses a token"
        else -> "No sign-in"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (!server.enabled) {
                Text(
                    text = "Disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = server.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (model.signingIn == server.id) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (server.auth == "oauth") {
                OutlinedButton(onClick = { model.signOutOfMCP(server.id) }) {
                    Text("Sign Out")
                }
            } else {
                OutlinedButton(
                    onClick = { model.beginMCPSignIn(server.id, onOpenBrowser) },
                    enabled = model.signingIn == null && !isPending,
                ) {
                    Text("Sign In…")
                }
            }
            TextButton(
                onClick = { model.openMCPForm(server.id) },
                enabled = !isPending,
            ) {
                Text("Edit")
            }
            TextButton(
                onClick = { model.deleteMCP(server.id) },
                enabled = !isPending,
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
        if (!footer.isNullOrEmpty()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyWorkspacePlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: Workspace Form Dialogs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFormDialog(
    model: AppModel,
    draft: MemoryDraft,
) {
    var ownerMenuExpanded by remember { mutableStateOf(false) }
    val selectedOwnerName = if (draft.agentID == null) {
        "Shared"
    } else {
        model.agents.firstOrNull { it.id == draft.agentID }?.name ?: "Shared"
    }

    AlertDialog(
        onDismissRequest = {
            model.errorText = ""
            model.memoryDraft = null
        },
        title = { Text(if (draft.memoryID.isEmpty()) "New Memory" else "Edit Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetErrorBanner(model.errorText)
                if (draft.memoryID.isEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = ownerMenuExpanded,
                        onExpandedChange = { ownerMenuExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedOwnerName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Remembered by") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ownerMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = ownerMenuExpanded,
                            onDismissRequest = { ownerMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Shared") },
                                onClick = {
                                    model.memoryDraft = draft.copy(agentID = null)
                                    ownerMenuExpanded = false
                                },
                            )
                            for (agent in model.agents) {
                                DropdownMenuItem(
                                    text = { Text(agent.name) },
                                    onClick = {
                                        model.memoryDraft = draft.copy(agentID = agent.id)
                                        ownerMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.content,
                    onValueChange = { model.memoryDraft = draft.copy(content = it) },
                    label = { Text("Memory Entry") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { model.saveMemory() },
                enabled = !model.loading,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    model.errorText = ""
                    model.memoryDraft = null
                },
            ) {
                Text("Cancel")
            }
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
    val zone = remember(draft.timezone) {
        try {
            ZoneId.of(draft.timezone)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
    }
    val localFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    var runDateText by remember(draft.id, zone) {
        mutableStateOf(ZonedDateTime.ofInstant(draft.runDate, zone).format(localFormatter))
    }

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
                                    model.taskDraft = draft.copy(agentID = agent.id)
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
                OutlinedTextField(
                    value = draft.prompt,
                    onValueChange = { model.taskDraft = draft.copy(prompt = it) },
                    label = { Text("Prompt") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !draft.repeats,
                        onClick = { model.taskDraft = draft.copy(repeats = false) },
                        label = { Text("Run once") },
                    )
                    FilterChip(
                        selected = draft.repeats,
                        onClick = { model.taskDraft = draft.copy(repeats = true) },
                        label = { Text("Repeat") },
                    )
                }
                if (draft.repeats) {
                    OutlinedTextField(
                        value = draft.cron,
                        onValueChange = { model.taskDraft = draft.copy(cron = it) },
                        label = { Text("Cron expression") },
                        placeholder = { Text("0 9 * * 1-5") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = runDateText,
                        onValueChange = { text ->
                            runDateText = text
                            try {
                                val ldt = LocalDateTime.parse(text.trim(), localFormatter)
                                val instant = ldt.atZone(zone).toInstant()
                                model.taskDraft = draft.copy(runDate = instant)
                            } catch (_: Exception) {
                            }
                        },
                        label = { Text("Run at (YYYY-MM-DD HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = draft.timezone,
                    onValueChange = { model.taskDraft = draft.copy(timezone = it) },
                    label = { Text("Time zone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPFormDialog(
    model: AppModel,
    draft: MCPDraft,
) {
    var ownerMenuExpanded by remember { mutableStateOf(false) }
    val selectedOwnerName = if (draft.agentID == null) {
        "Every bot (shared)"
    } else {
        model.agents.firstOrNull { it.id == draft.agentID }?.name ?: "Every bot (shared)"
    }

    AlertDialog(
        onDismissRequest = {
            model.errorText = ""
            model.mcpDraft = null
        },
        title = { Text(if (draft.serverID.isEmpty()) "Add MCP Server" else "Edit MCP Server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetErrorBanner(model.errorText)
                if (draft.serverID.isEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = ownerMenuExpanded,
                        onExpandedChange = { ownerMenuExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedOwnerName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Available to") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ownerMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = ownerMenuExpanded,
                            onDismissRequest = { ownerMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Every bot (shared)") },
                                onClick = {
                                    model.mcpDraft = draft.copy(agentID = null)
                                    ownerMenuExpanded = false
                                },
                            )
                            for (agent in model.agents) {
                                DropdownMenuItem(
                                    text = { Text(agent.name) },
                                    onClick = {
                                        model.mcpDraft = draft.copy(agentID = agent.id)
                                        ownerMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { model.mcpDraft = draft.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.url,
                    onValueChange = { model.mcpDraft = draft.copy(url = it) },
                    label = { Text("HTTP URL") },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.token,
                    onValueChange = { model.mcpDraft = draft.copy(token = it) },
                    label = { Text("Token (Optional)") },
                    visualTransformation = PasswordVisualTransformation(),
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
                        onCheckedChange = { model.mcpDraft = draft.copy(enabled = it) },
                    )
                }
                Text(
                    text = "Leave the token empty for servers that use OAuth, then choose Sign In in the list after saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { model.saveMCP() },
                enabled = !model.loading,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    model.errorText = ""
                    model.mcpDraft = null
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun SkillFormDialog(
    model: AppModel,
    draft: SkillDraft,
) {
    AlertDialog(
        onDismissRequest = {
            model.errorText = ""
            model.skillDraft = null
        },
        title = { Text(if (draft.skillID.isEmpty()) "New Shared Skill" else "Edit Shared Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetErrorBanner(model.errorText)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { model.skillDraft = draft.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.instructions,
                    onValueChange = { model.skillDraft = draft.copy(instructions = it) },
                    label = { Text("Instructions") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Bots that turn this skill on in their profile follow these instructions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { model.saveSkill() },
                enabled = !model.loading,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    model.errorText = ""
                    model.skillDraft = null
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun SheetErrorBanner(errorText: String) {
    if (errorText.isNotEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
