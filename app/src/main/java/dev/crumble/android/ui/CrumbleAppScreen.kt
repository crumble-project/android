package dev.crumble.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Route
import dev.crumble.android.model.nilIfEmpty
import dev.crumble.android.model.preview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrumbleAppScreen(model: AppModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(310.dp)) {
                SidebarView(
                    model = model,
                    onSelectRoute = { route ->
                        model.navigate(route)
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        model.showingSettings = true
                        scope.launch { drawerState.close() }
                    },
                    onCreateAgent = {
                        model.createAgent()
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                CrumbleTopBar(
                    model = model,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                when (val currentRoute = model.route) {
                    is Route.AgentChat, null -> {
                        val agent = model.selectedAgent
                        if (agent != null) {
                            ChatView(model = model, agent = agent)
                        } else {
                            WelcomeView(model = model)
                        }
                    }
                    else -> {
                        PanelView(model = model, panel = currentRoute)
                    }
                }

                ErrorBanner(
                    model = model,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                NoticeToast(
                    model = model,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    model.agentDraft?.let { draft ->
        AgentEditorDialog(model = model, draft = draft)
    }
    model.memoryDraft?.let { draft ->
        MemoryFormDialog(model = model, draft = draft)
    }
    model.taskDraft?.let { draft ->
        TaskFormDialog(model = model, draft = draft)
    }
    model.mcpDraft?.let { draft ->
        MCPFormDialog(model = model, draft = draft)
    }
    model.skillDraft?.let { draft ->
        SkillFormDialog(model = model, draft = draft)
    }
    if (model.showingSettings) {
        SettingsDialog(model = model)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrumbleTopBar(
    model: AppModel,
    onOpenDrawer: () -> Unit,
) {
    val currentRoute = model.route
    val selectedAgent = model.selectedAgent

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Open Navigation")
            }
        },
        title = {
            if ((currentRoute is Route.AgentChat || currentRoute == null) && selectedAgent != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BotAvatarView(
                        agent = selectedAgent,
                        size = 30.dp,
                        state = model.botState(selectedAgent),
                        live = true,
                    )
                    Column {
                        Text(
                            text = selectedAgent.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = when {
                            !selectedAgent.enabled -> "Paused"
                            model.snapshot?.isRunning == true -> "Working…"
                            else -> selectedAgent.description?.nilIfEmpty ?: selectedAgent.model ?: ""
                        }
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                Text(currentRoute?.title ?: "Crumble")
            }
        },
        actions = {
            when (currentRoute) {
                is Route.AgentChat, null -> {
                    if (selectedAgent != null) {
                        IconButton(
                            onClick = { model.compactChat() },
                            enabled = !model.loading && model.messages.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Compress,
                                contentDescription = "Summarize Older Context",
                            )
                        }
                        IconButton(onClick = { model.openEditor() }) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Agent Profile",
                            )
                        }
                    }
                }
                Route.Memory -> {
                    IconButton(onClick = { model.openMemoryForm("", agentID = null) }) {
                        Icon(Icons.Default.Add, contentDescription = "New Shared Memory")
                    }
                    IconButton(onClick = { model.openPanel(Route.Memory) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Skills -> {
                    IconButton(onClick = { model.openSkillForm("") }) {
                        Icon(Icons.Default.Add, contentDescription = "New Shared Skill")
                    }
                    IconButton(onClick = { model.openPanel(Route.Skills) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Tasks -> {
                    IconButton(
                        onClick = { model.openTaskForm("") },
                        enabled = model.agents.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Task")
                    }
                    IconButton(onClick = { model.openPanel(Route.Tasks) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Integrations -> {
                    IconButton(onClick = { model.openMCPForm("", agentID = null) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Shared MCP Server")
                    }
                    IconButton(onClick = { model.openPanel(Route.Integrations) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Activity -> {
                    IconButton(onClick = { model.openPanel(Route.Activity) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            }
        },
    )
}

@Composable
private fun SidebarView(
    model: AppModel,
    onSelectRoute: (Route) -> Unit,
    onOpenSettings: () -> Unit,
    onCreateAgent: () -> Unit,
) {
    val workspaceItems = listOf(
        Triple(Route.Memory, "Memory", Icons.Default.Psychology),
        Triple(Route.Skills, "Skills", Icons.AutoMirrored.Filled.MenuBook),
        Triple(Route.Tasks, "Tasks", Icons.Default.Schedule),
        Triple(Route.Activity, "Activity", Icons.Default.Timeline),
        Triple(Route.Integrations, "Integrations", Icons.Default.Extension),
    )

    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Crumble",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Row {
                IconButton(onClick = onCreateAgent) {
                    Icon(Icons.Default.Edit, contentDescription = "New Agent")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                Text(
                    text = "Agents",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (model.agents.isEmpty()) {
                item {
                    Text(
                        text = "No agents yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(model.agents, key = { it.id }) { agent ->
                    SidebarAgentRow(
                        model = model,
                        agent = agent,
                        selected = model.route == Route.AgentChat(agent.id),
                        onClick = { onSelectRoute(Route.AgentChat(agent.id)) },
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Workspace",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            items(workspaceItems, key = { it.second }) { (route, title, icon) ->
                NavigationDrawerItem(
                    label = { Text(title) },
                    icon = { Icon(icon, contentDescription = null) },
                    selected = model.route == route,
                    onClick = { onSelectRoute(route) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }

        HorizontalDivider()
        ConnectionStatus(model = model)
    }
}

@Composable
private fun SidebarAgentRow(
    model: AppModel,
    agent: Agent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isPending = model.pendingMutations.contains("agent:${agent.id}")
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(enabled = !isPending, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BotAvatarView(
                agent = agent,
                size = 44.dp,
                state = model.botState(agent),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!agent.enabled) {
                        Text(
                            text = "Paused",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = preview(agent.lastMessage ?: "").nilIfEmpty ?: "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ConnectionStatus(model: AppModel) {
    val (status, color) = when {
        model.streamStatus.isNotEmpty() -> model.streamStatus to MaterialTheme.colorScheme.error
        model.loading -> "Syncing…" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Connected" to Color(0xFF16A34A)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WelcomeView(model: AppModel) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (model.loading && model.agents.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Opening your workspace",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "A little help, always here.",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Create an agent with a purpose, then give it something to do.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { model.createAgent() }) {
                        Text("Create an Agent")
                    }
                    Button(onClick = { model.showingSettings = true }) {
                        Text("Server Settings")
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorBanner(
    model: AppModel,
    modifier: Modifier = Modifier,
) {
    val noSheetOpen = model.agentDraft == null &&
        model.memoryDraft == null &&
        model.taskDraft == null &&
        model.mcpDraft == null &&
        model.skillDraft == null &&
        !model.showingSettings

    AnimatedVisibility(
        visible = model.errorText.isNotEmpty() && noSheetOpen,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = model.errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { model.errorText = "" },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
fun NoticeToast(
    model: AppModel,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = model.notice.isNotEmpty(),
        modifier = modifier.padding(bottom = 84.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = CircleShape,
            tonalElevation = 6.dp,
        ) {
            Text(
                text = model.notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
