package dev.crumble.android.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AgentDraft
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChoiceOption
import dev.crumble.android.model.Skill
import dev.crumble.android.model.THINKING_LEVELS
import dev.crumble.android.model.WEB_SEARCH_ACCOUNT_DEFAULT
import dev.crumble.android.model.WEB_SEARCH_OPTIONS
import dev.crumble.android.model.botProviderOptions
import dev.crumble.android.model.desktopModelHint
import dev.crumble.android.model.desktopProviderOptions
import dev.crumble.android.model.nilIfEmpty
import dev.crumble.android.model.shownCapabilities

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorDialog(
    model: AppModel,
    draft: AgentDraft,
) {
    val requestID = draft.botRequestID
    val complete = draft.name.isNotBlank() && draft.provider.isNotEmpty() && draft.model.isNotBlank()

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
                    title = {
                        Text(
                            when {
                                requestID != null -> "Edit bot proposal"
                                draft.isNew -> "New Agent"
                                else -> "Agent Profile"
                            },
                        )
                    },
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
                        if (requestID != null) {
                            val deciding = requestID in model.decidingIDs
                            if (deciding) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            TextButton(
                                onClick = { model.saveAgentEditor() },
                                enabled = !deciding && complete,
                            ) {
                                Text("Approve")
                            }
                        } else {
                            TextButton(
                                onClick = { model.saveAgentEditor() },
                                enabled = !model.loading && complete,
                            ) {
                                Text(if (draft.isNew) "Create" else "Save")
                            }
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
                if (requestID != null) {
                    item {
                        Text(
                            text = "Review the proposed bot before approving it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    model.decisionErrors[requestID]?.let { error ->
                        item { SheetErrorBanner(error) }
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val avatar = draft.avatar ?: placeholderAvatar(draft.id.toString())
                        BotAvatarView(avatar = avatar, size = 96.dp, live = true)
                        Text(
                            text = draft.name.trim().nilIfEmpty ?: "New Agent",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }

                item {
                    SectionCard(title = "Avatar") {
                        AvatarEditor(
                            value = draft.avatar ?: placeholderAvatar(draft.id.toString()),
                            onChange = { model.agentDraft = draft.copy(avatar = it) },
                        )
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
                        ChoiceField(
                            label = "Provider",
                            options = botProviderOptions(model.providers, draft.provider),
                            value = draft.provider,
                            onChoose = { if (it.isNotEmpty()) model.changeEditorProvider(it) },
                        )
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
                        if (model.editorLevels.size > 1) {
                            ChoiceField(
                                label = "Reasoning",
                                options = THINKING_LEVELS.filter { it.value in model.editorLevels }.map { ChoiceOption(it.value, it.label) },
                                value = model.editorLevel,
                                onChoose = { model.agentDraft = draft.copy(thinking = it) },
                            )
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
                        footer = "Memory lets the agent keep notes between chats, including notes every bot with memory sees. When Message other bots is enabled, it can ask any other bot and relay their answers.",
                    ) {
                        for (capability in shownCapabilities(model.capabilities, draft.initialTools)) {
                            when (capability.tool) {
                                "memory" -> ToggleRow(
                                    label = capability.label,
                                    subtitle = capability.help,
                                    checked = draft.toolsMemory,
                                    onCheckedChange = { model.agentDraft = draft.copy(toolsMemory = it) },
                                )
                                "delegate" -> ToggleRow(
                                    label = capability.label,
                                    subtitle = capability.help,
                                    checked = draft.toolsDelegate,
                                    onCheckedChange = { model.agentDraft = draft.copy(toolsDelegate = it) },
                                )
                                else -> {
                                    ToggleRow(
                                        label = capability.label,
                                        subtitle = capability.help,
                                        checked = capability.tool in draft.otherTools,
                                        onCheckedChange = { on ->
                                            val tool = capability.tool
                                            model.agentDraft = draft.copy(otherTools = if (on) draft.otherTools + tool else draft.otherTools - tool)
                                        },
                                    )
                                    if (capability.tool == "desktop" && capability.tool in draft.otherTools) {
                                        DesktopModelFields(model, draft)
                                    }
                                }
                            }
                        }
                        if ("web" in draft.otherTools) {
                            ChoiceField(
                                label = "Web search provider",
                                options = WEB_SEARCH_OPTIONS,
                                value = draft.webSearchProvider ?: WEB_SEARCH_ACCOUNT_DEFAULT,
                                onChoose = { choice ->
                                    model.agentDraft = draft.copy(webSearchProvider = choice.takeIf { it != WEB_SEARCH_ACCOUNT_DEFAULT })
                                },
                            )
                            Text(
                                text = "Manage the default and Exa key in Settings → Web search.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        SectionCard(title = "Credentials") {
                            OutlinedButton(onClick = { model.openBotCredentials(draft.agentID) }) {
                                Text("Manage in Settings")
                            }
                        }
                    }
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
private fun DesktopModelFields(model: AppModel, draft: AgentDraft) {
    Column(
        modifier = Modifier.padding(start = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceField(
            label = "Desktop job provider",
            options = desktopProviderOptions(model.providers, draft.desktopProvider),
            value = draft.desktopProvider,
            onChoose = { model.changeEditorDesktopProvider(it) },
        )
        if (draft.desktopProvider.isNotEmpty()) {
            OutlinedTextField(
                value = draft.desktopModel,
                onValueChange = { model.agentDraft = draft.copy(desktopModel = it) },
                label = { Text("Desktop job model") },
                placeholder = { Text("Model ID") },
                supportingText = { Text(desktopModelHint(model.providers, draft.desktopProvider, model.desktopModels.size)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (model.desktopModels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (summary in model.desktopModels) {
                        AssistChip(
                            onClick = { model.agentDraft = draft.copy(desktopModel = summary.id) },
                            label = { Text(summary.id) },
                        )
                    }
                }
            }
        }
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
