package dev.crumble.android.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.activity.compose.BackHandler
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.MCPDraft
import dev.crumble.android.model.MCPServer
import dev.crumble.android.model.Memory
import dev.crumble.android.model.MemoryDraft
import dev.crumble.android.model.Route
import dev.crumble.android.model.SkillDraft
import dev.crumble.android.model.SKILLS_SHARED
import dev.crumble.android.model.activityTime
import dev.crumble.android.model.matchesMCP
import dev.crumble.android.model.memoryLine
import java.time.Instant

@Composable
fun PanelView(
    model: AppModel,
    panel: Route,
    modifier: Modifier = Modifier,
) {
    BackHandler { model.navigate(panel.parent) }
    Box(modifier = modifier.fillMaxSize()) {
        when (panel) {
            Route.Memory -> MemoryPanel(model)
            Route.Skills -> SkillsPanel(model)
            Route.Tasks -> TasksPanel(model)
            Route.Activity -> ActivityPanel(model)
            Route.Integrations -> IntegrationsPanel(model)
            Route.Avatars -> AvatarsPanel(model)
            Route.Providers -> ProvidersPanel(model)
            Route.PasswordManagers -> PasswordManagersPanel(model)
            Route.Credentials -> CredentialsPanel(model)
            Route.Logins -> LoginsPanel(model)
            Route.WebSearch -> WebSearchPanel(model)
            Route.JudgeModel -> JudgeModelPanel(model)
            Route.VisionModel -> VisionModelPanel(model)
            Route.Coding -> CodingPanel(model)
            Route.Users -> UsersPanel(model)
            Route.MessageGateways -> MessageGatewaysPanel(model)
            is Route.MessageGateway -> MessageGatewayPanel(model, panel.id)
            Route.EventRelay -> EventRelayPanel(model)
            Route.Settings -> SettingsPanel(model)
            Route.General -> GeneralPanel(model)
            Route.Account -> AccountPanel(model)
            Route.NewChat -> NewChatPanel(model)
            Route.NewGroup -> NewGroupPanel(model)
            is Route.AgentChat, is Route.GroupChat -> Unit
        }
    }
}

/** The text with every occurrence of `needle`, already lowercased, marked. */
internal fun highlighted(text: String, needle: String, mark: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (needle.isEmpty()) return@buildAnnotatedString
    val lower = text.lowercase()
    if (lower.length != text.length) return@buildAnnotatedString
    var at = lower.indexOf(needle)
    while (at >= 0) {
        addStyle(SpanStyle(background = mark), at, at + needle.length)
        at = lower.indexOf(needle, at + needle.length)
    }
}

@Composable
private fun MemoryPanel(model: AppModel) {
    var query by remember { mutableStateOf("") }
    val scope = model.memoryScope
    val normalizedQuery = query.trim().lowercase()
    val matches: (Memory) -> Boolean = { memory -> normalizedQuery.isEmpty() || memory.content.lowercase().contains(normalizedQuery) }
    val shared = model.sharedMemories.filter(matches)
    val scopedAgents = model.agents
        .filter { scope == null || scope == it.id }
        .filter { agent ->
            scope != null || agent.id in model.memoryErrors || model.agentMemories[agent.id].orEmpty().any(matches)
        }
    val sharedEnabledBots = model.agents.filter { it.sharedMemory == true }.map { it.name }
    val sharedFooter = if (sharedEnabledBots.isEmpty()) {
        "No bots use shared memory. Turn it on in a bot's profile."
    } else {
        "Used by: ${sharedEnabledBots.joinToString(", ")}"
    }
    val total = model.sharedMemories.size + model.agents.sumOf { model.agentMemories[it.id].orEmpty().size }
    val now = Instant.now()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search memories") },
                shape = CircleShape,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(selected = scope == null, onClick = { model.memoryScope = null }, label = { Text("All · $total") })
                FilterChip(selected = scope == SKILLS_SHARED, onClick = { model.memoryScope = SKILLS_SHARED }, label = { Text("Shared · ${model.sharedMemories.size}") })
                for (agent in model.agents) {
                    FilterChip(
                        selected = scope == agent.id,
                        onClick = { model.memoryScope = agent.id },
                        label = { Text("${agent.name} · ${model.agentMemories[agent.id].orEmpty().size}") },
                        leadingIcon = { BotAvatarView(agent = agent, size = 18.dp) },
                    )
                }
            }
        }
        item {
            if (scope != null && scope != SKILLS_SHARED) return@item
            RowsSection(
                title = "Shared · ${model.sharedMemories.size}",
                footer = sharedFooter,
                expandable = true,
            ) {
                model.memoryErrors[SKILLS_SHARED]?.let { note(it, isError = true) }
                if (shared.isEmpty() && SKILLS_SHARED !in model.memoryErrors) {
                    note(if (normalizedQuery.isEmpty()) "No shared memories yet." else "No matching shared memories.")
                }
                for (memory in shared) {
                    row { shape -> MemoryRow(model = model, memory = memory, agentID = null, needle = normalizedQuery, now = now, shape = shape) }
                }
            }
        }

        items(scopedAgents.filter { scope != SKILLS_SHARED }, key = { it.id }) { agent ->
            val memories = model.agentMemories[agent.id].orEmpty().filter(matches)
            val totalMemories = model.agentMemories[agent.id].orEmpty().size
            RowsSection(
                title = "${agent.name} · $totalMemories",
                expandable = true,
            ) {
                model.memoryErrors[agent.id]?.let { note(it, isError = true) }
                if (memories.isEmpty() && agent.id !in model.memoryErrors) {
                    note(if (normalizedQuery.isEmpty()) "No memories yet." else "No matching memories.")
                }
                for (memory in memories) {
                    row { shape -> MemoryRow(model = model, memory = memory, agentID = agent.id, needle = normalizedQuery, now = now, shape = shape) }
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
    needle: String,
    now: Instant,
    shape: Shape,
) {
    val isPending = memory.id.startsWith("pending-")
    var confirmingDelete by remember(memory.id) { mutableStateOf(false) }
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    val line = memoryLine(memory, now)
    GroupedItem(
        shape = shape,
        leading = { IconTile(routeIcon(Route.Memory), Tone.Lavender) },
        below = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { model.openMemoryForm(memory.id, agentID = agentID) }, enabled = !isPending) { Text("Edit") }
                TextButton(
                    onClick = { confirmingDelete = true },
                    enabled = !isPending,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            }
        },
    ) {
        Text(text = highlighted(memory.content, needle, mark), style = MaterialTheme.typography.titleMedium)
        if (line.isNotEmpty()) RowSubtitle(line)
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete memory?") },
            text = { Text("This memory will be removed from future chats.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    model.deleteMemory(memory.id, agentID = agentID)
                },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IntegrationsPanel(model: AppModel) {
    val appContext = LocalContext.current.applicationContext
    var search by remember { mutableStateOf("") }
    val needle = search.trim().lowercase()
    val listed = model.mcpServers.filter { matchesMCP(it, needle) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search MCP servers") },
                shape = CircleShape,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            val shared = listed.filter { it.agentId == null }
            RowsSection(
                title = "Shared",
                footer = "Every bot can use these. Turn one on for a bot in its profile.",
            ) {
                if (shared.isEmpty()) note("No shared MCP servers yet.")
                for (server in shared) {
                    row { shape -> MCPServerRow(model = model, server = server, shape = shape, onOpenBrowser = { url -> openSignInPage(appContext, url) }) }
                }
                add("Add Shared MCP Server") { model.openMCPForm("", agentID = null) }
            }
        }

        items(model.agents.filter { agent -> needle.isEmpty() || listed.any { it.agentId == agent.id } }, key = { it.id }) { agent ->
            val own = listed.filter { it.agentId == agent.id }
            RowsSection(
                title = agent.name,
                footer = "Only ${agent.name} can use these, with their own sign-in.",
            ) {
                if (own.isEmpty()) note("No MCP servers of its own.")
                for (server in own) {
                    row { shape -> MCPServerRow(model = model, server = server, shape = shape, onOpenBrowser = { url -> openSignInPage(appContext, url) }) }
                }
                add("Add MCP Server") { model.openMCPForm("", agentID = agent.id) }
            }
        }
    }
}

@Composable
private fun MCPServerRow(
    model: AppModel,
    server: MCPServer,
    shape: Shape,
    onOpenBrowser: (String) -> Unit,
) {
    val isPending = server.id.startsWith("pending-")
    var confirmingDelete by remember { mutableStateOf(false) }
    val statusText = when (server.auth) {
        "oauth" -> "Signed in with OAuth"
        "token" -> "Uses a token"
        else -> "No sign-in"
    }
    GroupedItem(
        shape = shape,
        leading = { LetterTile(server.name) },
        trailing = {
            if (!server.enabled) {
                Text(
                    text = "Disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        below = {
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
                    onClick = { confirmingDelete = true },
                    enabled = !isPending,
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        RowTitle(server.name)
        RowSubtitle(server.url, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        RowSubtitle(statusText)
        server.error?.let { RowSubtitle(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${server.name}?") },
            text = { Text("Bots lose its tools and its sign-in is removed. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        model.deleteMCP(server.id)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SectionCard(
    title: String,
    footer: String? = null,
    expandable: Boolean = false,
    content: @Composable () -> Unit,
) {
    SectionFrame(title, footer, expandable) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

/** A SectionCard for a list: each row is its own surface in a group. */
@Composable
fun RowsSection(
    title: String,
    footer: String? = null,
    expandable: Boolean = false,
    build: GroupScope.() -> Unit,
) {
    SectionFrame(title, footer, expandable) { GroupedRows(build) }
}

/** A section's heading and footer around its content, which an expandable heading hides. */
@Composable
internal fun SectionFrame(
    title: String,
    footer: String? = null,
    expandable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        if (expandable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            GroupLabel(title)
        }
        if (!expandable || expanded) content()
        if (!footer.isNullOrEmpty()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        }
    }
}

@Composable
internal fun EmptyWorkspacePlaceholder(
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
                    onValueChange = { model.memoryDraft = draft.copy(content = it.take(32_000)) },
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
fun MCPFormDialog(
    model: AppModel,
    draft: MCPDraft,
) {
    var ownerMenuExpanded by remember { mutableStateOf(false) }
    val server = model.mcpServers.firstOrNull { it.id == draft.serverID }
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
                SecretSourceField(
                    model = model,
                    label = "Token (Optional)",
                    draft = draft.token,
                    onChange = { model.mcpDraft = model.mcpDraft?.copy(token = it) },
                    valueDescription = "Leave the token empty for servers that use OAuth, then choose Sign In in the list after saving.",
                    valuePlaceholder = server?.takeIf { it.auth == "token" && it.token?.reference == null }?.let { "Replace the saved token" },
                )
                Text(
                    text = "OAuth app",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = "Only for servers whose sign-in can't register Crumble, such as GitHub's. Register an OAuth app there with the callback URL ${AppModel.oauthRedirectURI} and enter its client ID and secret.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.clientID,
                    onValueChange = { model.mcpDraft = model.mcpDraft?.copy(clientID = it) },
                    label = { Text("Client ID") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretSourceField(
                    model = model,
                    label = "Client secret",
                    draft = draft.clientSecret,
                    onChange = { model.mcpDraft = model.mcpDraft?.copy(clientSecret = it) },
                    valueDescription = "Stored encrypted and never shown again. Changing the app signs the server out.",
                    valuePlaceholder = if (server?.oauthClientSecret?.configured == true) "Replace the saved secret" else "Optional",
                    enabled = draft.clientID.isNotBlank(),
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
            }
        },
        confirmButton = {
            Button(
                onClick = { model.saveMCP() },
                enabled = !model.loading && !draft.saving,
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
@OptIn(ExperimentalMaterial3Api::class)
fun SkillFormDialog(
    model: AppModel,
    draft: SkillDraft,
) {
    AlertDialog(
        onDismissRequest = {
            model.errorText = ""
            model.skillDraft = null
        },
        title = { Text(if (draft.skillID.isEmpty()) if (draft.agentID == null) "New Shared Skill" else "New Personal Skill" else if (draft.agentID == null) "Edit Shared Skill" else "Edit Personal Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetErrorBanner(model.errorText)
                if (draft.skillID.isEmpty()) {
                    var ownerExpanded by remember(draft.id) { mutableStateOf(false) }
                    val ownerLabel = draft.agentID?.let { id -> model.agents.firstOrNull { it.id == id }?.name ?: "Bot" } ?: "Shared library"
                    ExposedDropdownMenuBox(
                        expanded = ownerExpanded,
                        onExpandedChange = { ownerExpanded = !ownerExpanded },
                    ) {
                        OutlinedTextField(
                            value = ownerLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Owner") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ownerExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = ownerExpanded,
                            onDismissRequest = { ownerExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Shared library") },
                                onClick = {
                                    model.skillDraft = draft.copy(agentID = null)
                                    ownerExpanded = false
                                },
                            )
                            for (agent in model.agents) {
                                DropdownMenuItem(
                                    text = { Text(agent.name) },
                                    onClick = {
                                        model.skillDraft = draft.copy(agentID = agent.id)
                                        ownerExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { model.skillDraft = draft.copy(name = it.take(100)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.instructions,
                    onValueChange = { model.skillDraft = draft.copy(instructions = it.take(32_000)) },
                    label = { Text("Instructions") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.agentID == null) {
                    SkillUsedByField(model.agents, draft.usedBy) { model.skillDraft = draft.copy(usedBy = it) }
                } else {
                    Text(
                        text = "Only this bot follows this personal skill.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/** The sign-in outlives the activity that started it, so this can't go through that activity. */
private fun openSignInPage(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
