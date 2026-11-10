package dev.crumble.android.ui

import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Tune
import androidx.activity.compose.BackHandler
import dev.crumble.android.model.WORKSPACE_PAGES
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Webhook
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.botListLine
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Route
import dev.crumble.android.model.SKILLS_SHARED
import dev.crumble.android.model.nilIfEmpty
import dev.crumble.android.model.preview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrumbleAppScreen(model: AppModel) {
    val inChat = model.route is Route.AgentChat || model.route is Route.GroupChat

    Scaffold(
        containerColor = pageColor,
        topBar = {
            CrumbleTopBar(model = model)
        },
    ) { innerPadding ->
        // The chat sheet runs under the gesture bar, so only the top inset comes off here.
        val top = PaddingValues(top = innerPadding.calculateTopPadding())
        Surface(
            color = if (inChat) sheetColor else Color.Transparent,
            shape = if (inChat) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RectangleShape,
            modifier = Modifier.fillMaxSize().padding(top).consumeWindowInsets(top),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (inChat) {
                            Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                        } else {
                            Modifier.imePadding()
                        },
                    ),
            ) {
                when (val currentRoute = model.route) {
                    is Route.GroupChat -> GroupChatView(model = model)
                    null -> HomeView(model = model)
                    is Route.AgentChat -> {
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

    BackHandler(enabled = inChat) { model.navigate(null) }
    model.agentDraft?.let { draft ->
        AgentEditorDialog(model = model, draft = draft)
    }
    model.memoryDraft?.let { draft ->
        MemoryFormDialog(model = model, draft = draft)
    }
    model.taskDraft?.let { draft ->
        TaskFormDialog(model = model, draft = draft)
    }
    model.eventSourceDraft?.let { draft ->
        EventSourceDialog(model = model, draft = draft)
    }
    model.mcpDraft?.let { draft ->
        MCPFormDialog(model = model, draft = draft)
    }
    model.skillDraft?.let { draft ->
        SkillFormDialog(model = model, draft = draft)
    }
    model.groupDraft?.let { draft ->
        GroupFormDialog(model = model, draft = draft)
    }
    model.skillInstall?.let { form ->
        InstallSkillsDialog(model = model, form = form)
    }
    model.installedSkill?.let { view ->
        InstalledSkillDialog(model = model, view = view)
    }
    model.skillUpdate?.let { view ->
        SkillUpdateDialog(model = model, view = view)
    }
    model.credentialForm?.let { form ->
        CredentialFormDialog(model = model, form = form)
    }
    model.loginForm?.let { form ->
        LoginFormDialog(model = model, form = form)
    }
    model.tokenForm?.let { form ->
        ManagerTokenDialog(model = model, form = form)
    }
    model.providerKeyForm?.let { form ->
        ProviderKeyDialog(model = model, form = form)
    }
    model.providerLogin?.let { view ->
        ProviderLoginDialog(model = model, view = view)
    }
    model.secretUses?.let { view ->
        SecretUsesDialog(model = model, view = view)
    }
    ConnectionDialogs(model)
    if (model.showingChanges) {
        ChangeHistoryDialog(model = model)
    }
    if (model.showingPalette) {
        CommandPaletteDialog(model = model)
    }
    if (model.showingBotDetails) {
        model.selectedAgent?.let { BotDetailsDialog(model = model, bot = it) }
    }
    if (model.showingJobs) {
        JobListDialog(model = model)
    }
    model.jobPanel?.let { panel ->
        JobPanelDialog(model = model, panel = panel)
    }
    model.callTranscript?.let { transcript ->
        CallDialog(model = model, transcript = transcript)
    }
    model.botDesktop?.let { desktop ->
        BotDesktopDialog(model = model, desktop = desktop)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrumbleTopBar(model: AppModel) {
    val currentRoute = model.route
    val selectedAgent = model.selectedAgent

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = pageColor, scrolledContainerColor = pageColor),
        navigationIcon = {
            if (currentRoute != null && currentRoute != Route.Account) {
                val parent = currentRoute.parent
                IconButton(onClick = { model.navigate(parent) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = parent?.let { "Back to ${it.title.lowercase()}" } ?: "Back",
                    )
                }
            }
        },
        title = {
            if (currentRoute is Route.AgentChat && selectedAgent != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BotAvatarView(
                        agent = selectedAgent,
                        size = 40.dp,
                        state = model.botState(selectedAgent),
                        live = true,
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Show details",
                                onClick = { model.openBotDetails() },
                            ),
                        verticalArrangement = Arrangement.Center,
                    ) {
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
            } else if (currentRoute is Route.GroupChat) {
                GroupTitle(model = model, groupID = currentRoute.id)
            } else if (currentRoute is Route.MessageGateway) {
                Text(model.gateways.value?.firstOrNull { it.id == currentRoute.id }?.label ?: currentRoute.title)
            } else {
                Text(currentRoute?.title ?: "Crumble")
            }
        },
        actions = {
            when (currentRoute) {
                null -> {
                    IconButton(onClick = { model.showingPalette = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    AccountButton(model)
                }
                Route.Account -> {
                    IconButton(onClick = { model.navigate(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                is Route.AgentChat -> {
                    if (model.jobs.isNotEmpty() || selectedAgent?.tools.orEmpty().any { it == "coding" || it == "desktop" }) {
                        IconButton(onClick = { model.openJobList() }) {
                            Icon(Icons.Default.Build, contentDescription = "Jobs")
                        }
                    }
                    ChatMenu {
                        item("Details") { model.openBotDetails() }
                        item("Bot settings") { model.openEditor() }
                    }
                }
                is Route.GroupChat -> {
                    ChatMenu {
                        item("Group settings") { model.openGroupEditor(currentRoute.id) }
                    }
                }
                Route.Settings, Route.General, Route.NewChat, Route.NewGroup -> Unit
                Route.Memory -> {
                    IconButton(onClick = { model.openMemoryForm("", agentID = model.memoryScope?.takeIf { it != SKILLS_SHARED }) }) {
                        Icon(Icons.Default.Add, contentDescription = "New Shared Memory")
                    }
                    IconButton(onClick = { model.openChanges() }) {
                        Icon(Icons.Default.History, contentDescription = "Change history")
                    }
                    IconButton(onClick = { model.openPanel(Route.Memory) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Skills -> {
                    IconButton(onClick = { model.openSkillForm("") }) {
                        Icon(Icons.Default.Add, contentDescription = "New Shared Skill")
                    }
                    IconButton(onClick = { model.openChanges() }) {
                        Icon(Icons.Default.History, contentDescription = "Change history")
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
                Route.Avatars, Route.WebSearch, Route.JudgeModel, Route.VisionModel -> Unit
                is Route.MessageGateway -> {
                    IconButton(onClick = { model.loadGateways() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Providers, Route.PasswordManagers, Route.Coding, Route.Users, Route.MessageGateways, Route.EventRelay -> {
                    IconButton(onClick = { model.openPanel(currentRoute) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Credentials -> {
                    IconButton(onClick = { model.openCredentialForm(null) }, enabled = model.agents.isNotEmpty()) {
                        Icon(Icons.Default.Add, contentDescription = "New credential")
                    }
                    IconButton(onClick = { model.openPanel(Route.Credentials) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                Route.Logins -> {
                    IconButton(onClick = { model.openLoginForm(null) }, enabled = model.agents.isNotEmpty()) {
                        Icon(Icons.Default.Add, contentDescription = "New login")
                    }
                    IconButton(onClick = { model.openPanel(Route.Logins) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            }
        },
    )
}

/** A page's icon. Chats have avatars instead. */
fun routeIcon(route: Route): ImageVector = when (route) {
    Route.Memory -> Icons.Default.Psychology
    Route.Skills -> Icons.AutoMirrored.Filled.MenuBook
    Route.Tasks -> Icons.Default.Schedule
    Route.Activity -> Icons.Default.Timeline
    Route.Integrations -> Icons.Default.Extension
    Route.Avatars -> Icons.Default.Face
    Route.Providers -> Icons.Default.Key
    Route.PasswordManagers -> Icons.Default.Shield
    Route.WebSearch -> Icons.Default.Language
    Route.Credentials -> Icons.Default.Lock
    Route.Logins -> Icons.AutoMirrored.Filled.Login
    Route.JudgeModel -> Icons.Default.Balance
    Route.VisionModel -> Icons.Default.Visibility
    Route.Coding -> Icons.Default.Code
    Route.Users -> Icons.Default.Group
    Route.MessageGateways, is Route.MessageGateway -> Icons.AutoMirrored.Filled.Message
    Route.EventRelay -> Icons.Default.Webhook
    Route.Settings -> Icons.Default.Settings
    Route.General -> Icons.Default.Tune
    Route.Account -> Icons.Default.Person
    Route.NewChat -> Icons.Default.Add
    Route.NewGroup -> Icons.Default.GroupAdd
    is Route.AgentChat, is Route.GroupChat -> Icons.AutoMirrored.Filled.Message
}

@Composable
fun ConnectionStatus(model: AppModel) {
    val (status, color) = when {
        model.liveStatus.isNotEmpty() -> model.liveStatus to MaterialTheme.colorScheme.error
        model.loading -> "Syncing…" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Connected" to Color(0xFF16A34A)
    }
    Row(
        modifier = Modifier
            .padding(top = 2.dp),
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
                    Button(onClick = { model.navigate(Route.General) }) {
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
        model.groupDraft == null &&
        model.route != Route.General

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
