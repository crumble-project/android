package dev.crumble.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AgentDraft
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Skill
import dev.crumble.android.model.nilIfEmpty

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorDialog(
    model: AppModel,
    draft: AgentDraft,
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            model.errorText = ""
            model.agentDraft = null
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (draft.isNew) "New Agent" else "Agent Profile") },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                model.errorText = ""
                                model.agentDraft = null
                            },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { model.saveAgentEditor() },
                            enabled = !model.loading,
                        ) {
                            Text(if (draft.isNew) "Create" else "Save")
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BotAvatarView(
                            seed = agentAvatarSeed(Agent(id = draft.agentID, avatar = draft.avatar)),
                            size = 96.dp,
                            live = true,
                        )
                        Text(
                            text = draft.name.trim().nilIfEmpty ?: "New Agent",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            for (seed in draft.avatarChoices) {
                                val selected = draft.avatar == "bot:$seed"
                                Box(
                                    modifier = Modifier
                                        .border(
                                            width = if (selected) 2.dp else 0.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                        .clickable {
                                            model.agentDraft = draft.copy(avatar = "bot:$seed")
                                        }
                                        .padding(4.dp),
                                ) {
                                    BotAvatarView(seed = seed, size = 36.dp)
                                }
                            }
                            IconButton(
                                onClick = {
                                    model.agentDraft = draft.withShuffledAvatars()
                                },
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = "More Avatars")
                            }
                        }
                    }
                }

                if (model.errorText.isNotEmpty()) {
                    item {
                        SheetErrorBanner(model.errorText)
                    }
                }

                item {
                    SectionCard(title = "Identity") {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { model.agentDraft = draft.copy(name = it) },
                            label = { Text("Name") },
                            placeholder = { Text("Required") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draft.description,
                            onValueChange = { model.agentDraft = draft.copy(description = it) },
                            label = { Text("Description") },
                            placeholder = { Text("What it helps with") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draft.folder,
                            onValueChange = { model.agentDraft = draft.copy(folder = it) },
                            label = { Text("Folder") },
                            placeholder = { Text("Personal") },
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
                                onCheckedChange = { model.agentDraft = draft.copy(enabled = it) },
                            )
                        }
                    }
                }

                item {
                    SectionCard(
                        title = "Model",
                        footer = model.modelHint,
                    ) {
                        val choices = model.providerChoices
                        if (choices.isEmpty()) {
                            OutlinedTextField(
                                value = draft.provider,
                                onValueChange = { model.changeEditorProvider(it) },
                                label = { Text("Provider") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            ExposedDropdownMenuBox(
                                expanded = providerMenuExpanded,
                                onExpandedChange = { providerMenuExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = draft.provider,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Provider") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth(),
                                )
                                ExposedDropdownMenu(
                                    expanded = providerMenuExpanded,
                                    onDismissRequest = { providerMenuExpanded = false },
                                ) {
                                    for (choice in choices) {
                                        DropdownMenuItem(
                                            text = { Text(choice) },
                                            onClick = {
                                                model.changeEditorProvider(choice)
                                                providerMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = draft.model,
                            onValueChange = { model.agentDraft = draft.copy(model = it) },
                            label = { Text("Model") },
                            placeholder = { Text("Model ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (model.models.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (summary in model.models) {
                                    AssistChip(
                                        onClick = { model.agentDraft = draft.copy(model = summary.id) },
                                        label = { Text(summary.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard(
                        title = "Instructions",
                        footer = "How the agent should behave in every chat.",
                    ) {
                        OutlinedTextField(
                            value = draft.instructions,
                            onValueChange = { model.agentDraft = draft.copy(instructions = it) },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    SectionCard(
                        title = "Capabilities",
                        footer = "Memory lets the agent keep notes between chats; with shared memory it also recalls and keeps notes every bot using shared memory sees. Schedule tasks lets it save one-time and recurring tasks for itself, or for a bot it is allowed to message. When Message other bots is enabled, it can ask the permitted bots you select and relay their answers. Code mode has the agent write a short script that calls its tools.",
                    ) {
                        ToggleRow(
                            label = "Memory",
                            checked = draft.toolsMemory,
                            onCheckedChange = { model.agentDraft = draft.copy(toolsMemory = it) },
                        )
                        if (draft.toolsMemory) {
                            ToggleRow(
                                label = "Use shared memory",
                                checked = draft.sharedMemory,
                                onCheckedChange = { model.agentDraft = draft.copy(sharedMemory = it) },
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        ToggleRow(
                            label = "Schedule tasks",
                            checked = draft.toolsSchedule,
                            onCheckedChange = { model.agentDraft = draft.copy(toolsSchedule = it) },
                        )
                        ToggleRow(
                            label = "Message other bots",
                            checked = draft.toolsDelegate,
                            onCheckedChange = { model.agentDraft = draft.copy(toolsDelegate = it) },
                        )
                        if (draft.toolsDelegate) {
                            for (other in model.agents.filter { it.id != draft.agentID }) {
                                val permitted = draft.delegates.contains(other.id)
                                ToggleRow(
                                    label = other.name,
                                    checked = permitted,
                                    onCheckedChange = { enabled ->
                                        val next = if (enabled) draft.delegates + other.id else draft.delegates - other.id
                                        model.agentDraft = draft.copy(delegates = next)
                                    },
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                        ToggleRow(
                            label = "Code mode",
                            checked = draft.toolsCodemode,
                            onCheckedChange = { model.agentDraft = draft.copy(toolsCodemode = it) },
                        )
                    }
                }

                item {
                    SectionCard(title = "Integrations") {
                        val servers = model.usableServers(forAgentID = draft.agentID)
                        if (servers.isEmpty()) {
                            Text(
                                text = "Add an MCP server under Integrations to use its tools here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        for (server in servers) {
                            val checked = draft.mcpServers.contains(server.id)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(server.name)
                                    if (server.agentId != null) {
                                        Text(
                                            text = "Only this agent",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Switch(
                                    checked = checked,
                                    enabled = server.enabled,
                                    onCheckedChange = { enabled ->
                                        val next = if (enabled) draft.mcpServers + server.id else draft.mcpServers - server.id
                                        model.agentDraft = draft.copy(mcpServers = next)
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    SectionCard(
                        title = "Skills",
                        footer = "Every bot follows the shared defaults, along with its personal skills and the optional shared skills you turn on.",
                    ) {
                        Text(
                            text = "Manage skills in Workspace → Skills, where you can create, edit, delete, and assign them to bots.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${draft.skills.size} personal · ${draft.sharedSkills.size} shared assigned",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!draft.isNew) {
                    item {
                        SectionCard(title = "Actions") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { model.duplicateSelected() }) {
                                    Text("Duplicate Agent")
                                }
                                Button(
                                    onClick = { model.confirmingDelete = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                ) {
                                    Text("Delete Agent…")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (model.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { model.confirmingDelete = false },
            title = { Text("Delete agent?") },
            text = { Text("The agent, its chat, memories and tasks will be removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        model.confirmingDelete = false
                        model.deleteSelected()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { model.confirmingDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
