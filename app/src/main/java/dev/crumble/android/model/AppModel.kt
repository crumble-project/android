package dev.crumble.android.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.crumble.android.generated.BotAvatarDefinition
import dev.crumble.android.generated.LearnedChange
import dev.crumble.android.generated.MemorySource
import dev.crumble.android.generated.ScriptRun
import dev.crumble.android.generated.SkillPackage
import dev.crumble.android.generated.BotRequest
import dev.crumble.android.generated.CallRequest
import dev.crumble.android.generated.CredentialRequest
import dev.crumble.android.generated.Credentials
import dev.crumble.android.generated.LoginRequest
import dev.crumble.android.generated.Logins
import dev.crumble.android.generated.PasswordManager
import dev.crumble.android.generated.Capabilities
import dev.crumble.android.generated.CodingJob
import dev.crumble.android.generated.GroupChat
import dev.crumble.android.generated.HeldAction
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.APIError
import dev.crumble.android.network.CHANGES_PAGE
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.UrlConnectionTransport
import dev.crumble.android.network.domain
import dev.crumble.android.network.edited
import dev.crumble.android.network.friendlyError
import dev.crumble.android.storage.CredentialStore
import dev.crumble.android.storage.InMemoryCredentialStore
import dev.crumble.android.ui.BotState
import dev.crumble.android.ui.placeholderAvatar
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/** The website's sidebar pages, reached from the account menu. Everything else lives under Settings. */
val WORKSPACE_PAGES: List<Route> get() = listOf(Route.Memory, Route.Tasks, Route.Activity)

/** The website's settings sections, grouped the way Android's Settings groups its rows. */
val SETTINGS_GROUPS: List<List<Route>> get() = listOf(
    listOf(Route.General),
    listOf(Route.Skills, Route.Avatars),
)

val SETTINGS_PAGES: List<Route> get() = SETTINGS_GROUPS.flatten()

sealed class Route {
    data class AgentChat(val id: String) : Route()
    data class GroupChat(val id: String) : Route()
    data object Memory : Route()
    data object Skills : Route()
    data object Tasks : Route()
    data object Activity : Route()
    data object Avatars : Route()
    data object Settings : Route()
    data object General : Route()
    data object Account : Route()
    data object NewChat : Route()
    data object NewGroup : Route()

    /** Where Back leads. Null is Home. */
    val parent: Route?
        get() = when {
            this in SETTINGS_PAGES -> Settings
            this in WORKSPACE_PAGES || this == Settings -> Account
            this == NewGroup -> NewChat
            else -> null
        }

    val title: String
        get() = when (this) {
            is AgentChat -> "Chat"
            is GroupChat -> "Group chat"
            Memory -> "Memory"
            Skills -> "Skills"
            Tasks -> "Automations"
            Activity -> "Activity"
            Avatars -> "Avatars"
            Settings -> "Settings"
            General -> "General"
            Account -> "Account"
            NewChat -> "New chat"
            NewGroup -> "New group chat"
        }
}

data class AgentDraft(
    val id: UUID = UUID.randomUUID(),
    val isNew: Boolean,
    val agentID: String,
    var name: String = "",
    var description: String = "",
    var enabled: Boolean = true,
    var provider: String = "",
    var model: String = "",
    var instructions: String = "You are a thoughtful, capable personal assistant.",
    var avatar: BotAvatarDefinition? = null,
    var thinking: String = "off",
    var temperature: String = "",
    var maxOutput: String = "",
    var toolsMemory: Boolean = true,
    var toolsDelegate: Boolean = false,
    var mcpServers: Set<String> = emptySet(),
    var skills: List<Skill> = emptyList(),
    var sharedSkills: Set<String> = emptySet(),
    /** The bot request this draft edits; saving approves the request with the edits. */
    val botRequestID: String? = null,
    /** Tools the proposal asks for beyond the toggles every draft has. */
    var otherTools: Set<String> = emptySet(),
    /** Null follows the account's default. */
    var webSearchProvider: String? = null,
    /** Empty follows the bot's own provider and model. */
    var desktopProvider: String = "",
    var desktopModel: String = "",
    /** What the bot had when the editor opened; a granted tool stays visible when the server stops offering it. */
    val initialTools: Set<String> = emptySet(),
) {
    val chosenDesktopModel: ModelChoice?
        get() = desktopModel.trim().nilIfEmpty?.takeIf { desktopProvider.isNotEmpty() }?.let { ModelChoice(desktopProvider, it) }
}

data class MemoryDraft(
    val id: UUID = UUID.randomUUID(),
    val memoryID: String,
    var content: String,
    /** The owner is fixed when editing; null means shared. */
    var agentID: String? = null,
)

data class SkillDraft(
    val id: UUID = UUID.randomUUID(),
    val skillID: String,
    /** Null for a shared library skill; fixed bot owner for personal skills. */
    var agentID: String? = null,
    var name: String = "",
    var instructions: String = "",
    /** Bots that use a shared skill. */
    var usedBy: Set<String> = emptySet(),
)

/** The Avatars page: a definition being tried, and the bot it saves to, if any. */
data class AvatarPage(
    val botID: String = "",
    val value: BotAvatarDefinition = placeholderAvatar("crumble"),
    val saving: Boolean = false,
    val saved: Boolean = false,
)

data class TaskDraft(
    val id: UUID = UUID.randomUUID(),
    val scheduleID: String,
    var agentID: String,
    var name: String = "",
    var prompt: String = "",
    var kind: String = "prompt",
    var command: String = "",
    var timeoutSeconds: String = "600",
    var notify: Boolean = true,
    var onEvent: Boolean = false,
    var eventSourceID: String = "",
    var repeats: Boolean = false,
    /** The owner chose Custom, so a cron that happens to fit a preset stays in the raw field. */
    var customCron: Boolean = false,
    var cron: String = "0 9 * * *",
    var runDate: Instant = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MINUTES),
    var timezone: String = ZoneId.systemDefault().id,
    var enabled: Boolean = true,
)

private fun defaultAppScope(): CoroutineScope {
    val dispatcher = try {
        // Test if Main dispatcher is available in this environment
        Dispatchers.Main.immediate.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
        Dispatchers.Main.immediate
    } catch (_: Throwable) {
        Dispatchers.Unconfined
    }
    return CoroutineScope(SupervisorJob() + dispatcher)
}

private const val DECISION_POLL_MS = 5_000L
private const val CALL_TRANSCRIPT_POLL_MS = 2_000L
private const val CALL_TRANSCRIPT_PAGE_SIZE = 200
private const val BOT_STATUS_POLL_MS = 5_000L
private const val JOB_REFRESH_MS = 3_000L
private const val IDLE_JOB_REFRESH_MS = 15_000L
private const val DESKTOP_STATUS_MS = 10_000L
private const val DESKTOP_HAND_BACK_ATTEMPTS = 3
private const val JOB_PAGE_SIZE = 100
private const val JOB_TRANSCRIPT_PAGE_SIZE = 200
private const val GROUP_PAGE_SIZE = 100
private const val RECENT_CHAT_PREFIX = "recent-chat:"
private const val COMPOSER_DRAFT_PREFIX = "composer-draft:"
private const val DRAFT_SAVE_DELAY_MS = 500L
private const val THEME_KEY = "theme"
private val DRAFT_TOOLS = setOf("memory", "delegate")
private const val STILL_SAVING = "That change is still saving. Try again in a moment."
private const val BOT_RECORD = "agent:"
private const val GROUP_RECORD = "group:"
private const val MEMORY_RECORD = "memory:"
private const val SCHEDULE_RECORD = "schedule:"
private const val SKILL_RECORD = "skill:"
private const val BOT_SKILL_RECORD = "agent-skill:"

/**
 * The app's state and the actions that change it. Network work runs in coroutines on the model's
 * scope; results from a replaced connection or a previous agent are dropped.
 */
@Stable
class AppModel(
    client: APIClient? = null,
    private val credentials: CredentialStore = InMemoryCredentialStore(),
    private val notifier: ReplyNotifier = NoOpNotifier,
    private val scope: CoroutineScope = defaultAppScope(),
    environment: Map<String, String> = emptyMap(),
    loadCredentials: Boolean = client == null,
    private val transport: HttpTransport = UrlConnectionTransport,
    private val onSignOut: suspend (APIClient) -> Unit = {},
    private val onSessionReset: () -> Unit = {},
    private val onNotificationsChanged: () -> Unit = {},
) {
    var serverURL by mutableStateOf("http://127.0.0.1:7337")
    var token by mutableStateOf("")
    var email by mutableStateOf("")
    var heldActions by mutableStateOf<List<HeldAction>>(emptyList())
    var codingJobs by mutableStateOf<List<CodingJob>>(emptyList())
    var botRequests by mutableStateOf<List<BotRequest>>(emptyList())
    var callRequests by mutableStateOf<List<CallRequest>>(emptyList())
    var callTranscript by mutableStateOf<CallTranscript?>(null)
    private var callTranscriptJob: Job? = null
    var decisionsLoaded by mutableStateOf(false)
    var decisionsFailed by mutableStateOf(false)
    var decidingIDs by mutableStateOf<Set<String>>(emptySet())
    var decisionErrors by mutableStateOf<Map<String, String>>(emptyMap())
    private var decisionsJob: Job? = null
    private var decisionsKey = ""
    var jobs by mutableStateOf<List<JobSummary>>(emptyList())
    var jobsLoaded by mutableStateOf(false)
    var jobsFailed by mutableStateOf(false)
    var showingJobs by mutableStateOf(false)
    var showingBotDetails by mutableStateOf(false)
    /** Bots the last status check found running; the selected bot's stream is fresher while it is open. */
    var workingBots by mutableStateOf<Set<String>>(emptySet())
    var jobPanel by mutableStateOf<JobPanel?>(null)
    private var jobsFollow: Job? = null
    private var chatJobsLoad: Job? = null
    private var chatJobsKey = ""
    private var jobPanelVersion = 0
    var botDesktop by mutableStateOf<BotDesktop?>(null)
        private set
    var desktopPreview by mutableStateOf<DesktopPreview?>(null)
        private set
    private var desktopAPI: APIClient? = null
    private var desktopFollow: Job? = null
    private var desktopTake: Job? = null
    private var desktopVersion = 0
    private var desktopPreviewPoll: Job? = null
    private var desktopPreviewVersion = 0
    private var desktopPreviewLink = 0
    var password by mutableStateOf("")
    var client: APIClient? by mutableStateOf(client)
        private set
    private var generation = 0
    var signingOut by mutableStateOf(false)
        private set

    var agents by mutableStateOf<List<Agent>>(emptyList())
    /** Null is home, the bot overview. */
    var route by mutableStateOf<Route?>(null)
    var selectedAgentID by mutableStateOf("")
        private set
    private var selectionVersion = 0
    private var awaitingInitialSnapshot = false
    private val recentChats = mutableMapOf<String, ChatSnapshot>()

    /** Names the server and account, so what one session stored never reaches another. */
    private fun sessionDigest(): String {
        val api = client
        val identity = "${api?.baseURL}\n${api?.token}"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun cacheKey(id: String) = "$RECENT_CHAT_PREFIX${sessionDigest()}:$id"

    private fun cachedChat(id: String): ChatSnapshot? {
        val key = cacheKey(id)
        return recentChats[key] ?: runCatching {
            credentials.read(key)?.let { CrumbleJson.decodeFromString(ChatSnapshot.serializer(), it) }
        }.getOrNull()?.also { recentChats[key] = it }
    }

    private fun forgetCachedChat(id: String) {
        val key = cacheKey(id)
        recentChats.remove(key)
        credentials.delete(key)
    }

    /** Keep only recent confirmed rows; uploads and transient run state are never persisted here. */
    private fun cacheRecentChat() {
        if (selectedAgentID.isEmpty()) return
        val confirmed = messages.filter { it.deliveryState == MessageDeliveryState.confirmed }
        val tail = confirmed.takeLast(5)
        // Keep an entry intact so the history cursor cannot skip another row from that entry.
        val boundary = tail.firstOrNull()?.entryId
        val retained = if (boundary == null) tail else confirmed.dropWhile { it.entryId != boundary }
        val cached = ChatSnapshot(
            agentId = selectedAgentID,
            messages = retained,
            hasMore = hasMore || confirmed.size > retained.size,
            oldestEntryId = retained.firstOrNull()?.entryId ?: oldestEntryID.nilIfEmpty,
        )
        val key = cacheKey(selectedAgentID)
        if (recentChats[key] == cached) return
        recentChats[key] = cached
        runCatching { credentials.write(key, CrumbleJson.encodeToString(ChatSnapshot.serializer(), cached)) }
    }

    var snapshot by mutableStateOf<ChatSnapshot?>(null)
    var messages by mutableStateOf<List<Message>>(emptyList())
    var hasMore by mutableStateOf(false)
    var oldestEntryID by mutableStateOf("")
    var loadingOlder by mutableStateOf(false)
    var expanded by mutableStateOf<Set<String>>(emptySet())

    private var composerText by mutableStateOf("")

    /** The selected bot's text. Assigning it saves it, so nothing that changes the selection may write it. */
    var draft: String
        get() = composerText
        set(value) {
            if (value == composerText) return
            composerText = value
            saveDraft(botDraft(selectedAgentID), value)
        }
    var attachments by mutableStateOf<List<PendingAttachment>>(emptyList())
    var replyTo by mutableStateOf<Message?>(null)

    /** The other bots' composers; the selected bot's lives in `draft`, `attachments`, and `replyTo`. */
    private val parkedComposers = mutableMapOf<String, ChatComposer>()

    /** Text waiting to reach the store, by key; empty means delete. Attachments and reply targets are never stored. */
    private val unsavedDrafts = mutableMapOf<String, String>()
    private var draftSave: Job? = null

    private fun botDraft(id: String) = composerDraftKey("bot", id)

    private fun groupDraftKey(id: String) = composerDraftKey("group", id)

    private fun composerDraftKey(kind: String, id: String): String? =
        if (id.isEmpty() || client?.token.isNullOrEmpty()) null else "$COMPOSER_DRAFT_PREFIX${sessionDigest()}:$kind:$id"

    // Encrypting on every keystroke would slow typing, so the store sees the text once it settles.
    private fun saveDraft(key: String?, text: String) {
        unsavedDrafts[key ?: return] = text
        draftSave?.cancel()
        draftSave = scope.launch {
            delay(DRAFT_SAVE_DELAY_MS)
            saveDrafts()
        }
    }

    /** Runs when the app leaves the screen too, because the system may end the process without warning. */
    fun saveDrafts() {
        draftSave?.cancel()
        for ((key, text) in unsavedDrafts) {
            runCatching { if (text.isEmpty()) credentials.delete(key) else credentials.write(key, text) }
        }
        unsavedDrafts.clear()
    }

    private fun savedDraft(key: String?): String {
        if (key == null) return ""
        return unsavedDrafts[key] ?: runCatching { credentials.read(key) }.getOrNull().orEmpty()
    }

    private fun forgetDrafts() {
        draftSave?.cancel()
        unsavedDrafts.clear()
        credentials.deletePrefixed(COMPOSER_DRAFT_PREFIX)
    }

    private data class PendingSend(
        val content: String,
        val replyTo: String,
        val attachments: List<PendingAttachment>,
    )

    private data class PendingPreview(
        val requestID: String,
        val text: String,
    )

    /** Optimistic rows and their upload payloads survive changing the selected bot. */
    private val pendingMessages = mutableMapOf<String, MutableList<Message>>()
    private val pendingSends = mutableMapOf<String, MutableMap<String, PendingSend>>()
    private val pendingPreviews = mutableMapOf<String, PendingPreview>()

    var groupChats by mutableStateOf<List<GroupChat>>(emptyList())
    var groupsFailed by mutableStateOf(false)
    var groupConversation by mutableStateOf<GroupConversation?>(null)
        private set
    var groupDraft by mutableStateOf<GroupDraft?>(null)

    /** Drafts and unconfirmed messages stay with their group while the owner is elsewhere. */
    var groupComposers by mutableStateOf<Map<String, ChatComposer>>(emptyMap())
        private set
    var groupOutgoing by mutableStateOf<Map<String, List<Message>>>(emptyMap())
        private set
    private val groupSends = mutableMapOf<String, PendingSend>()
    private var groupStream: Job? = null
    private var groupVersion = 0

    /** Sent image bytes, keyed by "entryId/index"; they never change once sent. */
    var attachmentBytes by mutableStateOf<Map<String, ByteArray>>(emptyMap())
        private set

    private var activeRequests = 0
    var pendingMutations by mutableStateOf<Set<String>>(emptySet())
        private set
    var mutationRevision = 0
        private set

    /** What each pending mutation changes, as "kind:id"; a read keeps these records as they are here. */
    private val activeRecords = mutableMapOf<String, Set<String>>()

    /** The revision each record's last mutation settled at, so a read can tell it started too early. */
    private val settledRecords = mutableMapOf<String, Int>()
    var loading by mutableStateOf(false)
    var errorText by mutableStateOf("")
    var notice by mutableStateOf("")
    var streamStatus by mutableStateOf("")
    var savingConnection by mutableStateOf(false)
        private set
    private var streamJob: Job? = null
    private var activityJob: Job? = null

    var agentMemories by mutableStateOf<Map<String, List<Memory>>>(emptyMap())
    private var memoryLoadVersion = 0
    var sharedMemories by mutableStateOf<List<Memory>>(emptyList())
    /** Why an owner's memories didn't load, by bot ID, or SKILLS_SHARED for shared memory. */
    var memoryErrors by mutableStateOf<Map<String, String>>(emptyMap())
    /** Null shows every owner; otherwise SKILLS_SHARED or a bot's ID. */
    var memoryScope by mutableStateOf<String?>(null)
    var sharedSkills by mutableStateOf<List<SharedSkill>>(emptyList())
    var agentSkills by mutableStateOf<Map<String, List<SkillPackage>>>(emptyMap())
    var agentSkillErrors by mutableStateOf<Map<String, String>>(emptyMap())
    var selectedSkills by mutableStateOf<Set<String>>(emptySet())
    var bulkReport by mutableStateOf<List<BulkMessage>>(emptyList())
    var checkingSkillUpdates by mutableStateOf(false)
        private set
    var skillCheckFailures by mutableStateOf<List<String>>(emptyList())
    var skillInstall by mutableStateOf<SkillInstallForm?>(null)
    private var skillBrowseVersion = 0
    var installedSkill by mutableStateOf<InstalledSkillView?>(null)
    var skillUpdate by mutableStateOf<SkillUpdateView?>(null)
    var changes by mutableStateOf<List<LearnedChange>>(emptyList())
    var showingChanges by mutableStateOf(false)
    var changesLoading by mutableStateOf(false)
        private set
    var changesHasMore by mutableStateOf(false)
        private set
    var changesError by mutableStateOf("")
    private var changesVersion = 0
    var avatarPage by mutableStateOf(AvatarPage())
    var schedules by mutableStateOf<List<Schedule>>(emptyList())
    var schedulesLoaded by mutableStateOf(false)
    var schedulesError by mutableStateOf("")
    var executions by mutableStateOf<List<Execution>>(emptyList())
    var scriptRuns by mutableStateOf<Map<String, List<ScriptRun>>>(emptyMap())
    var taskRunsLoading by mutableStateOf(false)
    var taskRunsError by mutableStateOf("")
    private var taskRunsVersion = 0
    var taskTab by mutableStateOf(TaskTab.Tasks)
        private set
    var capabilities by mutableStateOf<Capabilities?>(null)
    var eventSources by mutableStateOf<List<EventSource>>(emptyList())
    var eventSourcesLoaded by mutableStateOf(false)
    var eventSourcesError by mutableStateOf("")
    private var eventSourcesVersion = 0
    var deletingEventSources by mutableStateOf<Set<String>>(emptySet())
    var eventSourceErrors by mutableStateOf<Map<String, String>>(emptyMap())
    var activityFeed by mutableStateOf(ActivityFeedState())
    val activities: List<ActivityRecord>
        get() = activityFeed.items
    private var activityLoadVersion = 0
    var mcpServers by mutableStateOf<List<MCPServer>>(emptyList())
    var providers by mutableStateOf<List<ProviderStatus>>(emptyList())
    var models by mutableStateOf<List<ModelSummary>>(emptyList())
    var desktopModels by mutableStateOf<List<ModelSummary>>(emptyList())

    var agentDraft by mutableStateOf<AgentDraft?>(null)
    var memoryDraft by mutableStateOf<MemoryDraft?>(null)
    var taskDraft by mutableStateOf<TaskDraft?>(null)
    var eventSourceDraft by mutableStateOf<EventSourceDraft?>(null)
    var skillDraft by mutableStateOf<SkillDraft?>(null)
    var showingPalette by mutableStateOf(false)
    var theme by mutableStateOf(ThemeChoice.of(credentials.read(THEME_KEY)) ?: ThemeChoice.System)
        private set
    private var accountTheme = theme
    private var themeSaving = false
    private var themeChanges = 0
    private val notificationSettings = NotificationSettings(credentials)
    var replyNotifications by mutableStateOf(notificationSettings.replies)
        private set
    var notificationSound by mutableStateOf(notificationSettings.sound)
        private set
    /** Android's own verdict, reported by the activity. */
    var notificationsAllowed by mutableStateOf(true)
    var botCredentials by mutableStateOf<Map<String, Credentials>>(emptyMap())
    var botLogins by mutableStateOf<Map<String, Logins>>(emptyMap())
    var credentialForm by mutableStateOf<CredentialForm?>(null)
    var loginForm by mutableStateOf<LoginForm?>(null)
    var freshSecretRequests by mutableStateOf<Set<String>>(emptySet())
    var passwordManagers by mutableStateOf<List<PasswordManager>>(emptyList())

    /** How a bot's requests for one kind of secret are read and answered; credentials and logins behave alike. */
    private class SecretOps<D>(
        val kind: String,
        val read: suspend (APIClient, String) -> D,
        val decline: suspend (APIClient, String, String) -> Unit,
        val withoutRequest: (D, String) -> D,
        val restored: (D, D, String) -> D,
        val lists: () -> Map<String, D>,
        val store: (Map<String, D>) -> Unit,
    )

    private val credentialOps = SecretOps(
        kind = "credential",
        read = { api, id -> api.credentials(id) },
        decline = { api, id, name -> api.declineCredential(id, name) },
        withoutRequest = { list, name -> list.copy(requests = list.requests.filterNot { it.name == name }) },
        restored = { list, before, name -> list.restored(before, name) },
        lists = { botCredentials },
        store = { botCredentials = it },
    )

    private val loginOps = SecretOps(
        kind = "login",
        read = { api, id -> api.logins(id) },
        decline = { api, id, name -> api.declineLogin(id, name) },
        withoutRequest = { list, name -> list.copy(requests = list.requests.filterNot { it.name == name }) },
        restored = { list, before, name -> list.restored(before, name) },
        lists = { botLogins },
        store = { botLogins = it },
    )

    private val secretRevisions = mutableMapOf<String, Int>()

    var confirmingDelete by mutableStateOf(false)


    var appActive: Boolean = true

    init {
        if (loadCredentials) {
            val savedUrl = credentials.read("server-url")
            if (!savedUrl.isNullOrEmpty()) serverURL = savedUrl
            val savedToken = credentials.read("server-token")
            if (savedToken != null) token = savedToken
            credentials.read("account-email")?.let { email = it }
        }
        val envUrl = environment["CRUMBLE_URL"]?.trim()
        if (!envUrl.isNullOrEmpty()) {
            serverURL = envUrl.trimmingTrailingSlashes
        }
        val envToken = environment["CRUMBLE_TOKEN"]
        if (!envToken.isNullOrEmpty()) {
            token = envToken
        }
        if (this.client == null) {
            this.client = APIClient(baseURL = serverURL, token = token)
        }
    }

    /** The live stream the sidebar reports: the open group chat's, or else the bot chat's. */
    val liveStatus: String
        get() = groupConversation?.streamStatus ?: streamStatus

    val selectedAgent: Agent?
        get() = agents.firstOrNull { it.id == selectedAgentID }

    fun botState(agent: Agent): BotState {
        if (!agent.enabled) return BotState.sleeping
        return if (isWorking(agent.id)) BotState.working else BotState.idle
    }

    /** No server event announces a turn, so bots without an open stream come from `refreshBotStatuses`. */
    fun isWorking(agentID: String): Boolean {
        val live = snapshot?.takeIf { agentID == selectedAgentID && route !is Route.GroupChat }
        return live?.isRunning ?: (agentID in workingBots)
    }

    /** Reads every bot's status. A failed read keeps what was known rather than calling the bot idle. */
    suspend fun refreshBotStatuses() {
        val api = client ?: return
        val gen = generation
        val ids = agents.filter { it.enabled && !it.id.startsWith("pending:") }.map { it.id }
        val results = coroutineScope {
            ids.map { id -> async { id to attempt { api.chat(id, limit = 1) } } }.awaitAll()
        }
        if (generation != gen) return
        val running = results.mapNotNull { (id, result) -> result.getOrNull()?.let { id to it.isRunning } }.toMap()
        val known = agents.mapTo(HashSet()) { it.id }
        workingBots = (workingBots.filterTo(HashSet()) { running[it] != false } + running.filterValues { it }.keys)
            .filterTo(HashSet()) { it in known }
    }

    /** Keeps statuses fresh for as long as the caller shows the bot list. */
    suspend fun followBotStatuses() {
        while (currentCoroutineContext().isActive) {
            if (appActive) refreshBotStatuses()
            delay(BOT_STATUS_POLL_MS)
        }
    }

    // MARK: Requests

    private fun request(work: suspend (APIClient) -> (() -> Unit)?) {
        val currentClient = client
        if (currentClient == null) {
            errorText = "Connect to your server in Settings."
            return
        }
        val gen = generation
        activeRequests += 1
        loading = true
        errorText = ""
        scope.launch {
            try {
                val apply = work(currentClient)
                if (generation != gen) return@launch
                finishRequest()
                apply?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != gen) return@launch
                finishRequest()
                errorText = friendlyError(e)
            }
        }
    }

    private fun finishRequest() {
        activeRequests = maxOf(0, activeRequests - 1)
        loading = activeRequests > 0
    }

    /** Applies local intent immediately; failed requests restore only the affected record. */
    fun optimisticMutation(
        key: String,
        update: () -> Unit,
        rollback: () -> Unit,
        fail: (String) -> Unit = { errorText = it },
        touches: Set<String> = emptySet(),
        onBusy: () -> Unit = { errorText = STILL_SAVING },
        work: suspend (APIClient) -> (() -> Unit)?,
    ) {
        val currentClient = client
        if (currentClient == null) {
            errorText = "Connect to your server in Settings."
            return
        }
        if (pendingMutations.contains(key)) {
            onBusy()
            return
        }
        pendingMutations = pendingMutations + key
        activeRecords[key] = touches + key
        val gen = generation
        mutationRevision += 1
        update()
        activeRequests += 1
        loading = true
        errorText = ""
        scope.launch {
            try {
                val confirm = work(currentClient)
                if (generation != gen) return@launch
                settleMutation(key)
                finishRequest()
                confirm?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != gen) return@launch
                settleMutation(key)
                finishRequest()
                rollback()
                fail(friendlyError(e))
            }
        }
    }

    private fun settleMutation(key: String) {
        pendingMutations = pendingMutations - key
        mutationRevision += 1
        activeRecords.remove(key).orEmpty().forEach { settledRecords[it] = mutationRevision }
    }

    /** Whether a read from `revision` may predate a change that finished, so it must fetch again. */
    private fun settledSince(revision: Int, vararg kinds: String): Boolean =
        settledRecords.any { (record, at) -> at > revision && kinds.any(record::startsWith) }

    /** The server's list, with records that have a mutation on its way kept as they are here. */
    private fun <T> withLocalChanges(loaded: List<T>, local: List<T>, kind: String, idOf: (T) -> String): List<T> {
        val changing = activeRecords.values.flatten().filter { it.startsWith(kind) }.map { it.removePrefix(kind) }.toSet()
        if (changing.isEmpty()) return loaded
        val mine = local.associateBy(idOf)
        val known = loaded.mapTo(HashSet(), idOf)
        return loaded.mapNotNull { if (idOf(it) in changing) mine[idOf(it)] else it } +
            local.filter { idOf(it) in changing && idOf(it) !in known }
    }

    private fun applyBots(loaded: List<Agent>) {
        agents = withLocalChanges(loaded, agents, BOT_RECORD, Agent::id)
    }

    fun showNotice(text: String) {
        notice = text
        scope.launch {
            delay(2500L)
            if (notice == text) notice = ""
        }
    }

    // MARK: Agents and chat

    fun refresh() {
        loadAccount()
        loadGroupChats()
        loadBotList()
    }

    private fun loadBotList() {
        val revision = mutationRevision
        request { api ->
            val loaded = api.agents()
            return@request {
                if (settledSince(revision, BOT_RECORD)) {
                    loadBotList()
                } else {
                    applyBots(loaded)
                    var chosen = selectedAgentID
                    if (agents.none { it.id == chosen }) {
                        chosen = agents.firstOrNull { !it.id.startsWith("pending:") }?.id ?: ""
                    }
                    val inConversation = route is Route.AgentChat || route is Route.GroupChat
                    if (chosen.isEmpty() || (!inConversation && chosen != selectedAgentID)) {
                        cancelStream()
                        switchComposer(chosen)
                        selectedAgentID = chosen
                        snapshot = null
                        messages = emptyList()
                    } else if (route is Route.AgentChat) {
                        selectAgent(chosen)
                    }
                    // A reconnect emptied the open page, and its reads may need the bots that just arrived.
                    route?.takeUnless { inConversation }?.let(::openPanel)
                }
            }
        }
    }

    fun navigate(to: Route?) {
        when (to) {
            null -> openHome()
            is Route.AgentChat -> {
                if (to.id != selectedAgentID || route != to) {
                    selectAgent(to.id)
                }
            }
            is Route.GroupChat -> if (route != to) openGroupChat(to.id)
            else -> openPanel(to)
        }
    }

    /** Parks what the owner is writing to the selected bot and picks up what they left with `id`. */
    private fun switchComposer(id: String) {
        if (id == selectedAgentID) return
        val current = ChatComposer(draft, attachments, replyTo)
        if (current != ChatComposer()) parkedComposers[selectedAgentID] = current
        val next = parkedComposers.remove(id) ?: ChatComposer(draft = savedDraft(botDraft(id)))
        composerText = next.draft
        attachments = next.attachments
        replyTo = next.replyTo
    }

    /** Opens the bot's one continuous chat and follows its event stream. */
    fun selectAgent(id: String) {
        if (route != Route.AgentChat(id)) clearSecretDrafts()
        if (id != selectedAgentID) showingBotDetails = false
        closeGroupChat()
        stopChat()
        // A refresh reselects the same bot, which must not take the desktop away from its owner.
        if (botDesktop?.agentID != id) closeBotDesktop()
        selectionVersion += 1
        val version = selectionVersion
        switchComposer(id)
        selectedAgentID = id
        route = Route.AgentChat(id)
        heldActions = emptyList()
        codingJobs = emptyList()
        botRequests = emptyList()
        callRequests = emptyList()
        decisionsLoaded = false
        decisionsFailed = false
        decisionErrors = emptyMap()
        freshSecretRequests = emptySet()
        jobs = emptyList()
        jobsLoaded = false
        jobsFailed = false
        if (agentDraft?.botRequestID != null) agentDraft = null
        val cached = cachedChat(id)
        snapshot = cached?.copy(messages = null)
        messages = cached?.messages.orEmpty() + pendingMessages[id].orEmpty()
        hasMore = cached?.hasMore ?: false
        oldestEntryID = cached?.oldestEntryId ?: ""
        awaitingInitialSnapshot = true
        loadingOlder = false
        // A stream begins with a fresh snapshot. Reopening a cached chat needs no extra GET.
        if (cached != null) {
            client?.let { api -> streamJob = scope.launch { streamChat(api, id, version) } }
            return
        }
        request { api ->
            val snap = api.chat(id, limit = 5)
            return@request {
                if (selectionVersion == version && selectedAgentID == id) {
                    applySnapshot(snap)
                    streamJob = scope.launch { streamChat(api, id, version) }
                }
            }
        }
    }

    /** Keeps older pages the owner already loaded; snapshots only carry the newest page. */
    fun applySnapshot(incoming: ChatSnapshot) {
        val incomingMessages = incoming.messages ?: emptyList()
        val acknowledged = incomingMessages.mapNotNull { it.requestId }.toSet()
        if (acknowledged.isNotEmpty()) {
            // A send that only looked failed arrived after all, so its restored draft must not go out again.
            val restored = pendingMessages[selectedAgentID].orEmpty()
                .filter { it.deliveryState == MessageDeliveryState.failed && it.requestId in acknowledged }
                .mapNotNull { pendingSends[selectedAgentID]?.get(it.requestId) }
            if (restored.any(::composerHolds)) clearComposer()
            pendingMessages[selectedAgentID]?.removeAll { acknowledged.contains(it.requestId ?: "") }
            for (reqId in acknowledged) {
                pendingSends[selectedAgentID]?.remove(reqId)
                if (pendingPreviews[selectedAgentID]?.requestID == reqId) {
                    pendingPreviews.remove(selectedAgentID)
                }
            }
        }
        val local = pendingMessages[selectedAgentID] ?: emptyList()
        val confirmed = if (awaitingInitialSnapshot) {
            // Cached rows may be far behind. Replace them to avoid a hole in the history cursor.
            hasMore = incoming.hasMore ?: false
            oldestEntryID = incoming.oldestEntryId ?: ""
            awaitingInitialSnapshot = false
            incomingMessages
        } else {
            mergeMessages(
                messages.filter { it.deliveryState == MessageDeliveryState.confirmed },
                incomingMessages,
            )
        }
        messages = confirmed + local
        snapshot = incoming.copy(messages = null)
        cacheRecentChat()
        followDecisions()
        refreshChatJobs()
    }

    fun cancelStream() {
        stopChat()
        closeBotDesktop()
    }

    private fun stopChat() {
        snapshot?.let { workingBots = if (it.isRunning) workingBots + selectedAgentID else workingBots - selectedAgentID }
        streamJob?.cancel()
        streamJob = null
        streamStatus = ""
        decisionsJob?.cancel()
        decisionsKey = ""
        closeCallTranscript()
        closeJobs()
    }

    private fun hasPendingDecision(): Boolean =
        messages.any { message ->
            message.heldAction?.let { ref -> heldActions.any { it.id == ref.id && it.status == "pending" } } == true ||
                message.jobQuestion?.let { ref -> codingJobs.any { it.question?.id == ref.id } } == true ||
                message.botRequest?.let { ref -> botRequests.any { it.id == ref.id && it.status == "pending" } } == true ||
                message.callRequest?.let { ref -> callRequests.any { it.id == ref.id && (it.status == "pending" || it.status == "calling") } } == true ||
                message.credentialRequest?.let { ref -> botCredentials[selectedAgentID]?.requests?.any { ref.answers(it.name, it.requestedAt) } } == true ||
                message.loginRequest?.let { ref -> botLogins[selectedAgentID]?.requests?.any { ref.answers(it.name, it.requestedAt) } } == true
        }

    private fun isCallNotice(message: Message) =
        message.role == "user" && (message.origin?.kind == "call" || message.origin?.kind == "call_request")

    private fun concernsCalls(message: Message) = message.callRequest != null || isCallNotice(message)

    private fun secretRefs() = messages.flatMap {
        listOfNotNull(it.credentialRequest?.let(::credentialReadKey), it.loginRequest?.let(::loginReadKey))
    }

    private suspend fun loadDecisions(api: APIClient, agentID: String) {
        try {
            val held = if (messages.any { it.heldAction != null }) api.heldActions(agentID) else emptyList()
            val jobs = if (messages.any { it.jobQuestion != null }) api.codingJobs(agentID) else emptyList()
            val requests = if (messages.any { it.botRequest != null }) api.botRequests(agentID) else emptyList()
            val calls = if (messages.any(::concernsCalls)) api.callRequests(agentID) else emptyList()
            val asked = secretRefs().toSet()
            val credentialMark = secretMark("credential", agentID)
            val loginMark = secretMark("login", agentID)
            val credentialList = if (messages.any { it.credentialRequest != null }) api.credentials(agentID) else null
            val loginList = if (messages.any { it.loginRequest != null }) api.logins(agentID) else null
            if (selectedAgentID != agentID) return
            heldActions = held
            codingJobs = jobs
            botRequests = requests
            callRequests = calls
            if (credentialList != null && secretSettled("credential", agentID, credentialMark)) botCredentials = botCredentials + (agentID to credentialList)
            if (loginList != null && secretSettled("login", agentID, loginMark)) botLogins = botLogins + (agentID to loginList)
            freshSecretRequests = asked
            val editing = agentDraft?.botRequestID
            if (editing != null && requests.none { it.id == editing && it.status == "pending" }) agentDraft = null
            decisionsLoaded = true
            decisionsFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (selectedAgentID == agentID) decisionsFailed = true
        }
    }

    // Another client, a stopped job, or a restart can settle a decision, so keep reading while one waits.
    private fun followDecisions() {
        val refs = messages.flatMap {
            listOfNotNull(it.heldAction?.id, it.jobQuestion?.id, it.botRequest?.id, it.callRequest?.id, it.id.takeIf { _ -> isCallNotice(it) })
        } + secretRefs()
        val key = selectedAgentID + "\n" + refs.joinToString("\n")
        val api = client
        if (refs.isEmpty() || key == decisionsKey || api == null) return
        val agentID = selectedAgentID
        decisionsKey = key
        decisionsJob?.cancel()
        decisionsJob = scope.launch {
            do {
                loadDecisions(api, agentID)
                if (decisionsFailed || hasPendingDecision()) delay(DECISION_POLL_MS)
            } while (isActive && (decisionsFailed || hasPendingDecision()))
        }
    }

    private fun decide(id: String, work: suspend (APIClient, String) -> (() -> Unit)?) {
        val api = client ?: return
        if (id in decidingIDs) return
        val agentID = selectedAgentID
        val gen = generation
        decidingIDs = decidingIDs + id
        decisionErrors = decisionErrors - id
        scope.launch {
            try {
                val confirm = work(api, agentID)
                if (generation == gen) confirm?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == gen) decisionErrors = decisionErrors + (id to friendlyError(e))
            } finally {
                decidingIDs = decidingIDs - id
            }
            if (generation == gen) loadDecisions(api, agentID)
        }
    }

    fun decideHeldAction(id: String, approve: Boolean, reason: String = "") = decide(id) { api, agentID ->
        if (approve) api.approveHeldAction(agentID, id) else api.declineHeldAction(agentID, id, reason.trim())
        null
    }

    fun answerJobQuestion(job: String, questionID: String, text: String) {
        val gen = generation
        decide(questionID) { api, agentID ->
            api.answerCodingJob(agentID, job, questionID, text)
            if (jobPanel != null) loadJobs(api, agentID, gen)
            null
        }
    }

    fun decideBotRequest(id: String, approve: Boolean) = decide(id) { api, agentID ->
        if (approve) {
            val created = api.approveBotRequest(agentID, id)
            return@decide { addCreatedBot(created) }
        }
        api.declineBotRequest(agentID, id)
        null
    }

    fun decideCallRequest(id: String, approve: Boolean) = decide(id) { api, agentID ->
        val decided = if (approve) api.approveCallRequest(agentID, id) else api.declineCallRequest(agentID, id)
        return@decide {
            if (selectedAgentID == agentID) callRequests = callRequests.map { if (it.id == decided.id) decided else it }
        }
    }

    /** Reads the call's transcript, then follows it every two seconds while the call goes on. */
    fun openCallTranscript(requestID: String) {
        val api = client ?: return
        val agentID = selectedAgentID
        val gen = generation
        callTranscriptJob?.cancel()
        callTranscript = CallTranscript(agentID, requestID)
        callTranscriptJob = scope.launch {
            do {
                // Checked before the read, so a call that ends meanwhile still gets one last read.
                val calling = callRequests.any { it.id == requestID && it.status == "calling" }
                try {
                    val page = api.callRequestTranscript(agentID, requestID, CALL_TRANSCRIPT_PAGE_SIZE)
                    if (generation != gen || selectedAgentID != agentID) return@launch
                    callTranscript = CallTranscript(agentID, requestID, callLines(page.messages.orEmpty()), loaded = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (generation != gen || selectedAgentID != agentID) return@launch
                    callTranscript = callTranscript?.copy(loaded = true, failed = true)
                }
                if (calling) delay(CALL_TRANSCRIPT_POLL_MS)
            } while (isActive && calling)
        }
    }

    fun closeCallTranscript() {
        callTranscriptJob?.cancel()
        callTranscriptJob = null
        callTranscript = null
    }

    private fun addCreatedBot(created: Agent) {
        if (agents.none { it.id == created.id }) agents = agents + created
    }

    /** Opens the bot editor on a pending request's proposal; its save button approves the edits. */
    fun openBotRequestEditor(id: String) {
        val request = botRequests.firstOrNull { it.id == id && it.status == "pending" } ?: return
        val proposal = request.proposal
        val tools = proposal.tools.orEmpty()
        val draft = AgentDraft(
            isNew = true,
            agentID = "",
            name = proposal.name,
            description = proposal.description.orEmpty(),
            enabled = proposal.enabled ?: true,
            provider = proposal.provider.orEmpty(),
            model = proposal.model.orEmpty(),
            instructions = proposal.instructions.orEmpty(),
            avatar = proposal.avatar.domain(),
            thinking = proposal.inference?.thinkingLevel ?: "off",
            temperature = proposal.inference?.temperature?.toString().orEmpty(),
            maxOutput = proposal.inference?.maxOutputTokens?.toString().orEmpty(),
            toolsMemory = "memory" in tools,
            toolsDelegate = "delegate" in tools,
            mcpServers = proposal.mcpServers.orEmpty().toSet(),
            skills = proposal.skills.orEmpty().map { Skill(name = it.name, instructions = it.instructions) },
            sharedSkills = proposal.sharedSkills.orEmpty().toSet(),
            botRequestID = id,
            otherTools = tools.toSet() - DRAFT_TOOLS,
            webSearchProvider = proposal.webSearchProvider,
            desktopProvider = proposal.desktopModel?.provider.orEmpty(),
            desktopModel = proposal.desktopModel?.model.orEmpty(),
            initialTools = tools.toSet(),
        )
        errorText = ""
        decisionErrors = decisionErrors - id
        agentDraft = draft
        loadEditorData(draft.provider)
    }

    private fun approveEditedBotRequest(id: String, draft: AgentDraft, input: Agent) {
        val request = botRequests.firstOrNull { it.id == id } ?: return
        decide(id) { api, agentID ->
            val created = api.approveBotRequest(agentID, id, request.proposal.edited(input))
            return@decide {
                addCreatedBot(created)
                if (agentDraft?.id == draft.id) agentDraft = null
            }
        }
    }

    // MARK: Jobs

    private suspend fun loadJobs(api: APIClient, agentID: String, gen: Int = generation) {
        fun current() = generation == gen && selectedAgentID == agentID
        try {
            val loaded = coroutineScope {
                val coding = async { api.jobs(agentID, JobKind.coding) }
                val desktop = async { api.jobs(agentID, JobKind.desktop) }
                coding.await() + desktop.await()
            }
            if (!current()) return
            jobs = loaded
            jobsLoaded = true
            jobsFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (current()) jobsFailed = true
        }
    }

    // A job card's status only changes when a job message arrives; the panel follows progress itself.
    private fun refreshChatJobs() {
        val api = client ?: return
        val count = messages.count { jobOf(it) != null || isJobOrigin(it.origin?.kind) }
        val key = "$selectedAgentID\n$count"
        if (messages.none { jobOf(it) != null } || key == chatJobsKey) return
        val agentID = selectedAgentID
        chatJobsKey = key
        chatJobsLoad?.cancel()
        chatJobsLoad = scope.launch { loadJobs(api, agentID) }
    }

    private suspend fun loadJobHistory(api: APIClient, agentID: String, job: JobRef, version: Int) {
        try {
            val pending = jobPanel?.takeIf { jobPanelVersion == version }?.items
                ?.filterIsInstance<JobItem.Step>()?.filter { it.result == null }?.map { it.id }?.toMutableSet() ?: return
            val page = api.jobHistory(agentID, job, before = null, limit = JOB_PAGE_SIZE)
            val refreshed = page.items.associateByTo(mutableMapOf()) { it.id }
            pending.removeAll(refreshed.keys)
            var next = page.oldestEntryId.takeIf { page.hasMore }
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty() && next != null && visited.add(next)) {
                val older = api.jobHistory(agentID, job, before = next, limit = JOB_PAGE_SIZE)
                older.items.filter { it.id in pending }.forEach { refreshed[it.id] = it }
                pending.removeAll(refreshed.keys)
                next = older.oldestEntryId.takeIf { older.hasMore }
            }
            val panel = jobPanel?.takeIf { jobPanelVersion == version } ?: return
            jobPanel = panel.copy(
                messages = mergeJobPages(panel.messages, page.messages),
                items = mergeJobItems(panel.items.map { refreshed[it.id] ?: it }, page.items),
                hasMore = if (panel.loaded) panel.hasMore else page.hasMore == true,
                oldestEntryID = if (panel.loaded) panel.oldestEntryID else page.oldestEntryId,
                loaded = true,
                failed = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            jobPanel = jobPanel?.takeIf { jobPanelVersion == version }?.copy(failed = true) ?: jobPanel
        }
    }

    private fun followJobs() {
        val api = client ?: return
        val agentID = selectedAgentID
        jobsFollow?.cancel()
        jobsFollow = scope.launch {
            var seenStatus: String? = null
            while (isActive) {
                loadJobs(api, agentID)
                val panel = jobPanel
                val status = panel?.let { open -> jobs.firstOrNull { it.ref == open.job }?.status }
                if (panel != null && (!panel.loaded || panel.failed || jobWorking(status) || status != seenStatus)) {
                    loadJobHistory(api, agentID, panel.job, jobPanelVersion)
                }
                seenStatus = status
                val watched = if (panel != null) jobWorking(status) else jobs.any { jobWorking(it.status) }
                delay(if (watched) JOB_REFRESH_MS else IDLE_JOB_REFRESH_MS)
            }
        }
    }

    fun openBotDetails() {
        showingBotDetails = true
        schedulesError = ""
        loadSchedules()
        loadEventSources()
    }

    fun openJobList() {
        showingJobs = true
        followJobs()
    }

    fun closeJobList() {
        showingJobs = false
        if (jobPanel == null) jobsFollow?.cancel()
    }

    fun openJob(job: JobRef) {
        jobPanelVersion += 1
        jobPanel = JobPanel(job.copy(status = null))
        followJobs()
    }

    fun closeJob() {
        jobPanelVersion += 1
        jobPanel = null
        if (!showingJobs) jobsFollow?.cancel()
    }

    fun retryJobHistory() = followJobs()

    private fun closeJobs() {
        jobsFollow?.cancel()
        chatJobsLoad?.cancel()
        chatJobsKey = ""
        jobPanelVersion += 1
        jobPanel = null
        showingJobs = false
    }

    fun loadEarlierJobActivity() {
        val panel = jobPanel ?: return
        val api = client ?: return
        val before = panel.oldestEntryID ?: return
        if (panel.loadingEarlier) return
        val agentID = selectedAgentID
        val version = jobPanelVersion
        jobPanel = panel.copy(loadingEarlier = true)
        scope.launch {
            try {
                val page = api.jobHistory(agentID, panel.job, before, JOB_PAGE_SIZE)
                val current = jobPanel?.takeIf { jobPanelVersion == version } ?: return@launch
                val known = current.messages.map { it.id }.toSet()
                val knownItems = current.items.map { it.id }.toSet()
                jobPanel = current.copy(
                    messages = page.messages.filterNot { it.id in known } + current.messages,
                    items = page.items.filterNot { it.id in knownItems } + current.items,
                    hasMore = page.hasMore == true,
                    oldestEntryID = page.oldestEntryId,
                    loadingEarlier = false,
                    failed = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                jobPanel = jobPanel?.takeIf { jobPanelVersion == version }?.copy(loadingEarlier = false, failed = true) ?: jobPanel
            }
        }
    }

    /** Reads every page of the open job's transcript, then hands its Markdown to `write`. */
    fun downloadJobTranscript(write: suspend (String) -> Unit) {
        val panel = jobPanel ?: return
        val api = client ?: return
        if (panel.downloading) return
        val agentID = selectedAgentID
        val version = jobPanelVersion
        jobPanel = panel.copy(downloading = true, downloadFailed = false)
        scope.launch {
            val failed = try {
                val pages = mutableListOf<List<Message>>()
                var before: String? = null
                do {
                    val page = api.jobHistory(agentID, panel.job, before, JOB_TRANSCRIPT_PAGE_SIZE)
                    pages.add(page.messages.orEmpty())
                    before = page.oldestEntryId.takeIf { page.hasMore == true }
                } while (before != null)
                write(jobMarkdown(panel.job, pages.asReversed().flatten()))
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            jobPanel = jobPanel?.takeIf { jobPanelVersion == version }?.copy(downloading = false, downloadFailed = failed) ?: jobPanel
        }
    }

    fun openBotDesktop(agentID: String = selectedAgentID) {
        val api = client ?: return
        if (agentID.isEmpty() || botDesktop?.agentID == agentID) return
        closeBotDesktop()
        desktopVersion += 1
        val version = desktopVersion
        desktopAPI = api
        botDesktop = BotDesktop(agentID)
        desktopFollow = scope.launch { followDesktopControl(api, agentID, version) }
        reconnectDesktopViewer()
        syncDesktopPreview()
    }

    fun reconnectDesktopViewer() {
        val desktop = botDesktop ?: return
        val api = desktopAPI ?: return
        if (desktop.viewer.connecting) return
        val version = desktopVersion
        fun finish(link: DesktopLink) {
            if (version == desktopVersion) botDesktop = botDesktop?.copy(viewer = link)
        }
        finish(DesktopLink(connecting = true))
        scope.launch {
            try {
                finish(DesktopLink(api.desktopViewerURL(api.createDesktopViewer(desktop.agentID))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finish(DesktopLink(error = friendlyError(e)))
            }
        }
    }

    /** Taking control pauses the bot's desktop job, so it reads as done only once the server confirms. */
    fun setDesktopHolder(holder: String) {
        val desktop = botDesktop ?: return
        val api = desktopAPI ?: return
        if (desktop.control == null || desktop.taking != null) return
        val version = desktopVersion
        botDesktop = desktop.copy(taking = holder, takeError = "")
        desktopTake = scope.launch {
            try {
                val control = api.setDesktopHolder(desktop.agentID, holder)
                if (version == desktopVersion) {
                    botDesktop = botDesktop?.let { it.copy(control = newerControl(it.control, control), taking = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (version == desktopVersion) botDesktop = botDesktop?.copy(taking = null, takeError = friendlyError(e))
            }
        }
    }

    /** The viewer's connection ended or its page failed; the link is spent. */
    fun desktopViewerLost(url: String) {
        botDesktop?.takeIf { it.viewer.url == url }?.let { botDesktop = it.copy(viewer = DesktopLink(error = DESKTOP_UNAVAILABLE)) }
        desktopPreview?.takeIf { it.viewer.url == url }?.let { desktopPreview = it.copy(viewer = DesktopLink(error = DESKTOP_UNAVAILABLE)) }
    }

    /** Leaving hands control back so the bot's job can continue. `leaving` must outlive the screen. */
    fun closeBotDesktop(leaving: CoroutineScope = scope) {
        val desktop = botDesktop ?: return
        val api = desktopAPI
        desktopVersion += 1
        botDesktop = null
        desktopAPI = null
        desktopFollow?.cancel()
        desktopFollow = null
        val take = desktopTake
        desktopTake = null
        if (api != null && (desktop.owner || desktop.taking == DESKTOP_OWNER)) {
            leaving.launch {
                // A take still in flight must land first, or it would leave the owner holding a closed desktop.
                take?.join()
                handBackDesktop(api, desktop.agentID)
            }
        }
        syncDesktopPreview()
    }

    private suspend fun handBackDesktop(api: APIClient, agentID: String) {
        repeat(DESKTOP_HAND_BACK_ATTEMPTS) { attempt ->
            try {
                api.setDesktopHolder(agentID, DESKTOP_BOT)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (attempt < DESKTOP_HAND_BACK_ATTEMPTS - 1) delay(1_000L shl attempt)
            }
        }
        errorText = DESKTOP_HAND_BACK_FAILED
    }

    private suspend fun followDesktopControl(api: APIClient, agentID: String, version: Int) {
        var backoffMs = 500L
        while (scope.isActive) {
            try {
                api.desktopEvents(agentID) { control ->
                    if (version != desktopVersion) return@desktopEvents
                    backoffMs = 500L
                    botDesktop = botDesktop?.let { it.copy(control = newerControl(it.control, control)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is APIError && e.statusCode == 401) {
                    if (version == desktopVersion) botDesktop = botDesktop?.copy(takeError = friendlyError(e))
                    return
                }
            }
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 8_000L)
        }
    }

    /** Machine state has no live event, so the preview polls it while it is on screen. */
    fun watchDesktopPreview(agentID: String) {
        val api = client ?: return
        if (desktopPreview?.agentID == agentID) return
        stopDesktopPreview()
        val version = desktopPreviewVersion
        desktopPreview = DesktopPreview(agentID)
        desktopPreviewPoll = scope.launch {
            while (isActive) {
                val running = try {
                    api.desktopRunning(agentID)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    desktopPreview?.running ?: false
                }
                if (version != desktopPreviewVersion) return@launch
                desktopPreview = desktopPreview?.copy(running = running)
                syncDesktopPreview()
                delay(DESKTOP_STATUS_MS)
            }
        }
    }

    fun stopDesktopPreview() {
        desktopPreviewVersion += 1
        desktopPreviewLink += 1
        desktopPreviewPoll?.cancel()
        desktopPreviewPoll = null
        desktopPreview = null
    }

    fun wakeDesktop() {
        desktopPreview = desktopPreview?.copy(asked = true)
        syncDesktopPreview()
    }

    /** Until asked, the link only watches: it neither boots the machine nor keeps it awake. */
    private fun syncDesktopPreview() {
        val preview = desktopPreview ?: return
        val api = client ?: return
        if (!preview.awake || botDesktop != null) {
            if (!preview.viewer.idle) {
                desktopPreviewLink += 1
                desktopPreview = preview.copy(viewer = DesktopLink())
            }
            return
        }
        if (!preview.viewer.idle) return
        val version = desktopPreviewLink
        fun finish(link: DesktopLink) {
            if (version == desktopPreviewLink) desktopPreview = desktopPreview?.copy(viewer = link)
        }
        finish(DesktopLink(connecting = true))
        scope.launch {
            try {
                finish(DesktopLink(api.desktopViewerURL(api.createDesktopViewer(preview.agentID, passive = !preview.asked))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finish(DesktopLink(error = friendlyError(e)))
            }
        }
    }

    private suspend fun streamChat(api: APIClient, id: String, version: Int) {
        var backoffMs = 500L
        while (scope.isActive) {
            try {
                api.chatEvents(id, limit = 5) { snap ->
                    if (selectionVersion != version || client !== api) return@chatEvents
                    applySnapshot(snap)
                    streamStatus = ""
                    errorText = ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (selectionVersion != version) return
                if (e is APIError && e.statusCode == 401) {
                    errorText = friendlyError(e)
                    streamStatus = "Disconnected"
                    return
                }
                streamStatus = "Reconnecting…"
            }
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 8_000L)
        }
    }

    /** Follows server-wide activity for notifications, reconnecting as the chat stream does. */
    fun followActivity() {
        activityJob?.cancel()
        val api = client ?: return
        val gen = generation
        activityJob = scope.launch {
            var backoffMs = 500L
            while (isActive && generation == gen) {
                try {
                    api.activityEvents { event ->
                        if (generation != gen) return@activityEvents
                        handleActivity(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is APIError && e.statusCode == 401) return@launch
                }
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 8_000L)
                // Groups may have changed while the stream was down.
                if (generation == gen) loadGroupChats()
            }
        }
    }

    fun handleActivity(event: ActivityEvent) {
        if (event.type == "chat-preview" && event.lastMessage != null && pendingPreviews[event.agentId] == null) {
            val idx = agents.indexOfFirst { it.id == event.agentId }
            if (idx >= 0) {
                val updated = agents.toMutableList()
                updated[idx] = updated[idx].copy(lastMessage = event.lastMessage)
                agents = updated
            }
        }
        val context = NotificationContext(appActive = appActive, route = route)
        notifier.handle(event, context)
    }

    fun loadOlder() {
        if (awaitingInitialSnapshot || loadingOlder || !hasMore || oldestEntryID.isEmpty()) return
        val id = selectedAgentID
        val before = oldestEntryID
        val version = selectionVersion
        loadingOlder = true
        request { api ->
            try {
                val page = api.chatHistory(id, before = before, limit = 100)
                return@request {
                    if (selectionVersion == version) {
                        loadingOlder = false
                        messages = mergeMessages(messages, page.messages ?: emptyList())
                        hasMore = page.hasMore ?: false
                        oldestEntryID = page.oldestEntryId ?: ""
                    }
                }
            } catch (e: Throwable) {
                if (selectionVersion == version) loadingOlder = false
                throw e
            }
        }
    }

    fun sendMessage() {
        if (client == null || snapshot == null) return
        val content = draft.trim()
        val files = attachments
        if (content.isEmpty() && files.isEmpty()) return
        val id = selectedAgentID
        val reply = replyTo?.entryId ?: ""
        val failed = pendingMessages[id].orEmpty().firstOrNull { message ->
            val record = pendingSends[id]?.get(message.requestId)
            message.deliveryState == MessageDeliveryState.failed && record != null && composerHolds(record) &&
                (reply.isEmpty() || reply == record.replyTo)
        }
        if (failed != null) {
            redeliver(failed, restoreDraft = true)
            return
        }
        val requestID = newRequestID()
        val optimistic = Message(
            id = "local:$requestID",
            role = "user",
            content = JsonPrimitive(content),
            replyTo = reply.nilIfEmpty,
            replyPreview = replyTo?.text?.nilIfEmpty,
            timestamp = System.currentTimeMillis(),
            attachments = files.map {
                AttachmentInfo(
                    name = it.name,
                    mimeType = it.mimeType,
                    size = it.data.size,
                    kind = if (it.isImage) "image" else "file",
                )
            },
            requestId = requestID,
            deliveryState = MessageDeliveryState.sending,
        )
        clearComposer()
        pendingMessages.getOrPut(id) { mutableListOf() }.add(optimistic)
        val record = PendingSend(content = content, replyTo = reply, attachments = files)
        pendingSends.getOrPut(id) { mutableMapOf() }[requestID] = record
        val previewText = content.nilIfEmpty ?: files.firstOrNull()?.let { "Attachment: ${it.name}" } ?: ""
        pendingPreviews[id] = PendingPreview(requestID = requestID, text = previewText)
        val agentIndex = agents.indexOfFirst { it.id == id }
        if (agentIndex >= 0) {
            val updated = agents.toMutableList()
            updated[agentIndex] = updated[agentIndex].copy(lastMessage = previewText)
            agents = updated
        }
        messages = messages + optimistic
        deliverMessage(id, requestID, record, restoreDraft = true)
    }

    /** Re-sends a failed optimistic row with its original idempotency key and attachments. */
    fun retryMessage(message: Message) = redeliver(message, restoreDraft = false)

    private fun redeliver(message: Message, restoreDraft: Boolean) {
        val requestID = message.requestId ?: return
        if (client == null || message.deliveryState != MessageDeliveryState.failed) return
        val id = selectedAgentID
        val record = pendingSends[id]?.get(requestID) ?: return
        if (!markMessage(id, requestID, MessageDeliveryState.sending)) return
        if (composerHolds(record)) clearComposer()
        deliverMessage(id, requestID, record, restoreDraft)
    }

    private fun composerHolds(record: PendingSend) =
        draft.trim() == record.content && attachments.map { it.id } == record.attachments.map { it.id }

    private fun clearComposer() {
        draft = ""
        attachments = emptyList()
        replyTo = null
    }

    /** Sets a row's state wherever it is shown; false once a snapshot has already confirmed it. */
    private fun markMessage(id: String, requestID: String, state: MessageDeliveryState): Boolean {
        val list = pendingMessages[id] ?: return false
        val index = list.indexOfFirst { it.requestId == requestID }
        if (index < 0) return false
        list[index] = list[index].copy(deliveryState = state)
        if (selectedAgentID == id) {
            messages = messages.map { if (it.requestId == requestID) it.copy(deliveryState = state) else it }
        }
        return true
    }

    private fun deliverMessage(id: String, requestID: String, record: PendingSend, restoreDraft: Boolean) {
        val api = client ?: return
        val gen = generation
        scope.launch {
            try {
                api.sendChatMessage(
                    agentID = id,
                    content = record.content,
                    requestID = requestID,
                    replyTo = record.replyTo,
                    attachments = record.attachments.map { it.outgoing },
                )
                if (generation != gen) return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != gen || !markMessage(id, requestID, MessageDeliveryState.failed)) return@launch
                if (selectedAgentID == id) errorText = "Message was not confirmed: ${friendlyError(e)}"
                if (restoreDraft) restoreComposer(id, record)
            }
            if (pendingPreviews[id]?.requestID == requestID) pendingPreviews.remove(id)
        }
    }

    /** Text the owner wrote since sending is never overwritten; the failed row still holds the message. */
    private fun restoreComposer(id: String, record: PendingSend) {
        if (selectedAgentID == id) {
            if (draft.isEmpty() && attachments.isEmpty()) {
                draft = record.content
                attachments = record.attachments
            }
            return
        }
        val parked = parkedComposers[id] ?: ChatComposer()
        if (parked.draft.isEmpty() && parked.attachments.isEmpty()) {
            parkedComposers[id] = parked.copy(draft = record.content, attachments = record.attachments)
            saveDraft(botDraft(id), record.content)
        }
    }

    fun addAttachment(attachment: PendingAttachment) {
        if (attachments.size >= AttachmentLimits.COUNT) {
            errorText = "A message can carry up to ${AttachmentLimits.COUNT} files."
            return
        }
        attachments = attachments + attachment
    }

    fun removeAttachment(id: UUID) {
        attachments = attachments.filterNot { it.id == id }
    }

    fun attachmentImageBytes(message: Message, index: Int): ByteArray? {
        val entryID = message.entryId
        if (!entryID.isNullOrEmpty()) {
            return attachmentBytes["$entryID/$index"]
        }
        val requestID = message.requestId ?: return null
        val files = pendingSends[selectedAgentID]?.get(requestID)?.attachments ?: return null
        if (index !in files.indices || !files[index].isImage) return null
        return files[index].data
    }

    suspend fun loadAttachmentImage(entryID: String, index: Int) {
        val key = "$entryID/$index"
        if (attachmentBytes.containsKey(key)) return
        val currentClient = client ?: return
        if (entryID.startsWith("local:")) return
        val agent = selectedAgentID
        val gen = generation
        try {
            val bytes = currentClient.attachment(agent, entryID = entryID, index = index)
            if (bytes.isNotEmpty() && generation == gen) {
                attachmentBytes = attachmentBytes + (key to bytes)
            }
        } catch (_: Throwable) {
        }
    }

    fun resetPendingMessages() {
        pendingMessages.clear()
        pendingSends.clear()
        pendingPreviews.clear()
        messages = emptyList()
        parkedComposers.clear()
        clearComposer()
    }

    fun abortChat() {
        val id = selectedAgentID
        optimisticMutation(
            key = "abort:$id",
            update = { showNotice("Stopping…") },
            rollback = {},
            onBusy = {},
            work = { api ->
                api.abortChat(id)
                null
            },
        )
    }

    // MARK: Group chats

    /** Reads the sidebar's list. A failure keeps the rows already shown. */
    fun loadGroupChats() {
        val api = client ?: return
        val gen = generation
        val revision = mutationRevision
        scope.launch {
            try {
                val loaded = api.groupChats()
                if (generation != gen) return@launch
                groupsFailed = false
                if (settledSince(revision, GROUP_RECORD)) loadGroupChats()
                else groupChats = withLocalChanges(loaded, groupChats, GROUP_RECORD, GroupChat::id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == gen) groupsFailed = true
            }
        }
    }

    /** Opens a group chat and follows its event stream. */
    fun openGroupChat(id: String) {
        if (id.startsWith("pending:")) return
        closeGroupChat()
        clearSecretDrafts()
        route = Route.GroupChat(id)
        if (id !in groupComposers) {
            savedDraft(groupDraftKey(id)).nilIfEmpty?.let { groupComposers = groupComposers + (id to ChatComposer(draft = it)) }
        }
        val api = client ?: return
        val version = groupVersion
        groupConversation = GroupConversation(id)
        groupStream = scope.launch {
            try {
                val snapshot = api.groupSnapshot(id)
                if (groupVersion == version) applyGroupSnapshot(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (groupVersion != version) return@launch
                if (e is APIError && e.statusCode == 404) return@launch groupGone(id)
                updateConversation(id) { it.copy(loadError = friendlyError(e)) }
            }
            followGroupChat(api, id, version)
        }
    }

    private fun closeGroupChat() {
        groupStream?.cancel()
        groupStream = null
        groupVersion += 1
        groupConversation = null
    }

    private fun updateConversation(id: String, change: (GroupConversation) -> GroupConversation) {
        groupConversation = groupConversation?.let { if (it.groupID == id) change(it) else it }
    }

    private suspend fun followGroupChat(api: APIClient, id: String, version: Int) {
        var backoffMs = 500L
        while (scope.isActive) {
            try {
                api.groupChatEvents(id) { snapshot ->
                    if (groupVersion != version || client !== api) return@groupChatEvents
                    applyGroupSnapshot(snapshot)
                    backoffMs = 500L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (groupVersion != version) return
                if (e is APIError && e.statusCode == 404) return groupGone(id)
                if (e is APIError && e.statusCode == 401) {
                    errorText = friendlyError(e)
                    updateConversation(id) { it.copy(streamStatus = "Disconnected") }
                    return
                }
                updateConversation(id) { it.copy(streamStatus = "Reconnecting…") }
            }
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 8_000L)
        }
    }

    private fun applyGroupSnapshot(incoming: GroupSnapshot) {
        val id = incoming.groupChat.id
        val acknowledged = incoming.messages.mapNotNull { it.requestId }.toSet()
        val waiting = groupOutgoing[id].orEmpty()
        if (waiting.any { it.requestId in acknowledged }) {
            groupOutgoing = groupOutgoing + (id to waiting.filterNot { it.requestId in acknowledged })
            acknowledged.forEach { groupSends.remove(it) }
        }
        val editing = "group:$id" in pendingMutations
        updateConversation(id) { current ->
            val merged = current.merging(incoming)
            if (editing) merged.copy(group = current.group) else merged
        }
        if (!editing) writeGroup(incoming.groupChat, adding = true)
    }

    private fun writeGroup(group: GroupChat, adding: Boolean = false) {
        if (groupChats.any { it.id == group.id }) {
            if (group !in groupChats) groupChats = groupChats.map { if (it.id == group.id) group else it }
        } else if (adding) {
            groupChats = groupChats + group
        }
        updateConversation(group.id) { it.copy(group = group) }
    }

    /** Another client deleted the group: its stream answers 404, and the chat is no longer anywhere. */
    private fun groupGone(id: String) {
        dropGroup(id)
        if (route == Route.GroupChat(id)) leaveGroupChat()
    }

    private fun dropGroup(id: String) {
        groupChats = groupChats.filterNot { it.id == id }
        groupComposers = groupComposers - id
        saveDraft(groupDraftKey(id), "")
        groupOutgoing[id].orEmpty().forEach { message -> groupSends.remove(message.requestId) }
        groupOutgoing = groupOutgoing - id
        if (groupDraft?.groupID == id) groupDraft = null
    }

    private fun leaveGroupChat() {
        closeGroupChat()
        val next = agents.firstOrNull { it.id == selectedAgentID } ?: agents.firstOrNull()
        if (next != null) selectAgent(next.id) else route = null
    }

    private fun resetGroupChats() {
        closeGroupChat()
        if (route is Route.GroupChat) route = null
        groupChats = emptyList()
        groupsFailed = false
        groupComposers = emptyMap()
        groupOutgoing = emptyMap()
        groupSends.clear()
        groupDraft = null
    }

    fun groupComposer(id: String): ChatComposer = groupComposers[id] ?: ChatComposer()

    fun updateGroupComposer(id: String, change: (ChatComposer) -> ChatComposer) {
        val current = groupComposer(id)
        val next = change(current)
        groupComposers = groupComposers + (id to next)
        if (next.draft != current.draft) saveDraft(groupDraftKey(id), next.draft)
    }

    fun addGroupAttachment(id: String, attachment: PendingAttachment) {
        if (groupComposer(id).attachments.size >= AttachmentLimits.COUNT) {
            errorText = "A message can carry up to ${AttachmentLimits.COUNT} files."
            return
        }
        updateGroupComposer(id) { it.copy(attachments = it.attachments + attachment) }
    }

    /** Shows the message at once; it stays until a snapshot confirms it by request ID. */
    fun sendGroupMessage() {
        val conversation = groupConversation?.takeIf { it.group != null } ?: return
        val id = conversation.groupID
        val composer = groupComposer(id)
        val content = composer.draft.trim()
        if (content.isEmpty() && composer.attachments.isEmpty()) return
        val requestID = newRequestID()
        val reply = composer.replyTo
        groupSends[requestID] = PendingSend(content, reply?.entryId.orEmpty(), composer.attachments)
        val optimistic = Message(
            id = "local:$requestID",
            role = "user",
            content = JsonPrimitive(content),
            replyTo = reply?.entryId,
            replyPreview = reply?.text?.nilIfEmpty,
            timestamp = System.currentTimeMillis(),
            attachments = composer.attachments.map {
                AttachmentInfo(it.name, it.mimeType, it.data.size, if (it.isImage) "image" else "file")
            },
            requestId = requestID,
            deliveryState = MessageDeliveryState.sending,
        )
        groupOutgoing = groupOutgoing + (id to groupOutgoing[id].orEmpty() + optimistic)
        updateGroupComposer(id) { ChatComposer() }
        deliverGroupMessage(id, requestID)
    }

    /** Re-sends a failed row with its original request ID, so the server can tell it is the same message. */
    fun retryGroupMessage(message: Message) {
        val requestID = message.requestId ?: return
        val id = groupConversation?.groupID ?: return
        if (message.deliveryState == MessageDeliveryState.failed) deliverGroupMessage(id, requestID)
    }

    private fun markGroupMessage(id: String, requestID: String, state: MessageDeliveryState) {
        groupOutgoing = groupOutgoing + (id to groupOutgoing[id].orEmpty().map {
            if (it.requestId == requestID) it.copy(deliveryState = state) else it
        })
    }

    private fun deliverGroupMessage(id: String, requestID: String) {
        val api = client ?: return
        val record = groupSends[requestID] ?: return
        val gen = generation
        markGroupMessage(id, requestID, MessageDeliveryState.sending)
        updateConversation(id) { it.copy(sendError = null) }
        scope.launch {
            try {
                api.sendGroupMessage(id, record.content, requestID, record.replyTo, record.attachments.map { it.outgoing })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A snapshot may already show the message, or the group may be gone.
                if (generation != gen || groupOutgoing[id].orEmpty().none { it.requestId == requestID }) return@launch
                markGroupMessage(id, requestID, MessageDeliveryState.failed)
                updateConversation(id) { it.copy(sendError = "Message was not confirmed: ${friendlyError(e)}") }
            }
        }
    }

    /** The server reports the stopped turn in the transcript, so success needs no local change. */
    fun stopGroupChat() {
        val id = groupConversation?.groupID ?: return
        optimisticMutation(
            key = "group-stop:$id",
            update = { showNotice("Stopping…") },
            rollback = {},
            onBusy = {},
            work = { api ->
                api.stopGroupChat(id)
                null
            },
        )
    }

    fun loadEarlierGroupMessages() {
        val conversation = groupConversation ?: return
        val api = client ?: return
        val before = conversation.oldestEntryID
        if (before == null || conversation.loadingOlder || !conversation.hasMore) return
        val id = conversation.groupID
        val version = groupVersion
        updateConversation(id) { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val page = api.groupHistory(id, before, GROUP_PAGE_SIZE)
                if (groupVersion == version) updateConversation(id) { it.adding(page) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (groupVersion != version) return@launch
                updateConversation(id) { it.copy(loadingOlder = false) }
                errorText = friendlyError(e)
            }
        }
    }

    fun groupAttachmentImageBytes(message: Message, index: Int): ByteArray? {
        val entryID = message.entryId
        if (!entryID.isNullOrEmpty()) return attachmentBytes["$entryID/$index"]
        val file = groupSends[message.requestId]?.attachments?.getOrNull(index)
        return file?.takeIf { it.isImage }?.data
    }

    // Group entry IDs embed the group's ID, so they cannot collide with a bot's in `attachmentBytes`.
    suspend fun loadGroupAttachmentImage(entryID: String, index: Int) {
        val key = "$entryID/$index"
        val api = client ?: return
        val id = groupConversation?.groupID ?: return
        if (attachmentBytes.containsKey(key)) return
        val gen = generation
        try {
            val bytes = api.groupAttachment(id, entryID, index)
            if (bytes.isNotEmpty() && generation == gen) attachmentBytes = attachmentBytes + (key to bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun openNewGroup(memberIDs: List<String>) {
        errorText = ""
        groupDraft = GroupDraft(memberIDs = memberIDs)
    }

    /** Opens the form at once, then moves it to the server's version unless the owner already edited it. */
    fun openGroupEditor(id: String) {
        val group = groupChats.firstOrNull { it.id == id } ?: groupConversation?.group?.takeIf { it.id == id } ?: return
        val draft = GroupDraft(groupID = id, name = group.name, memberIDs = group.memberIds)
        errorText = ""
        groupDraft = draft
        val api = client ?: return
        val gen = generation
        scope.launch {
            try {
                val latest = api.groupChat(id)
                if (generation != gen || "group:$id" in pendingMutations) return@launch
                writeGroup(latest)
                if (groupDraft == draft) groupDraft = draft.copy(name = latest.name, memberIDs = latest.memberIds)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun toggleGroupMember(botID: String, on: Boolean) {
        val draft = groupDraft ?: return
        val full = draft.memberIDs.size >= MAX_GROUP_MEMBERS
        if (on && (full || botID in draft.memberIDs)) return
        groupDraft = draft.copy(memberIDs = if (on) draft.memberIDs + botID else draft.memberIDs - botID)
    }

    fun saveGroup() {
        val draft = groupDraft ?: return
        val name = draft.name.trim()
        if (!isValidGroup(name, draft.memberIDs)) return
        val isNew = draft.groupID.isEmpty()
        val id = if (isNew) "pending:${newRequestID()}" else draft.groupID
        val previous = groupChats.firstOrNull { it.id == id }
        val provisional = GroupChat(id, name, draft.memberIDs, previous?.createdAt.orEmpty(), previous?.updatedAt.orEmpty())
        val originalRoute = route
        optimisticMutation(
            key = "group:$id",
            update = {
                writeGroup(provisional, adding = true)
                groupDraft = null
            },
            rollback = {
                if (previous != null) writeGroup(previous) else groupChats = groupChats.filterNot { it.id == id }
                if (groupDraft == null && route == originalRoute) groupDraft = draft
            },
            work = { api ->
                val saved = if (isNew) api.createGroupChat(name, draft.memberIDs) else api.updateGroupChat(id, name, draft.memberIDs)
                return@optimisticMutation {
                    if (isNew) {
                        groupChats = upserted(groupChats, id, saved) { it.id }
                        if (route == originalRoute && groupDraft == null) openGroupChat(saved.id)
                    } else {
                        writeGroup(saved)
                    }
                }
            },
        )
    }

    fun deleteGroup(id: String) {
        val index = groupChats.indexOfFirst { it.id == id }
        if (index < 0 || id.startsWith("pending:")) return
        val removed = groupChats[index]
        val draft = groupDraft
        val wasOpen = route == Route.GroupChat(id)
        var routeAfterDeletion = route
        optimisticMutation(
            key = "group:$id",
            update = {
                groupChats = groupChats.filterNot { it.id == id }
                groupDraft = null
                if (wasOpen) leaveGroupChat()
                routeAfterDeletion = route
            },
            rollback = {
                val list = groupChats.toMutableList()
                list.add(minOf(index, list.size), removed)
                groupChats = list
                if (route == routeAfterDeletion) {
                    if (wasOpen) openGroupChat(id)
                    if (groupDraft == null) groupDraft = draft
                }
            },
            work = { api ->
                api.deleteGroupChat(id)
                return@optimisticMutation { dropGroup(id) }
            },
        )
    }

    // MARK: Agent profile

    fun createAgent() {
        val draft = AgentDraft(
            isNew = true,
            agentID = "",
            avatar = placeholderAvatar(newRequestID()),
            provider = firstConfiguredProvider(),
            toolsDelegate = true,
            otherTools = setOf("web"),
        )
        agentDraft = draft
        loadEditorData(draft.provider)
    }

    fun openEditor(agentID: String? = null) {
        val targetId = agentID ?: selectedAgentID
        val agent = agents.firstOrNull { it.id == targetId } ?: return
        val tools = agent.tools ?: emptyList()
        val draft = AgentDraft(
            isNew = false,
            agentID = agent.id,
            name = agent.name,
            description = agent.description ?: "",
            instructions = agent.instructions ?: "",
            model = agent.model ?: "",
            provider = agent.provider ?: "",
            enabled = agent.enabled,
            avatar = agent.avatar,
            thinking = agent.inference?.thinkingLevel ?: "off",
            temperature = agent.inference?.temperature?.toString() ?: "",
            maxOutput = agent.inference?.maxOutputTokens?.toString() ?: "",
            toolsMemory = tools.contains("memory"),
            toolsDelegate = tools.contains("delegate"),
            skills = agent.skills ?: emptyList(),
            sharedSkills = (agent.sharedSkills ?: emptyList()).toSet(),
            mcpServers = (agent.mcpServers ?: emptyList()).toSet(),
            otherTools = tools.toSet() - DRAFT_TOOLS,
            webSearchProvider = agent.webSearchProvider,
            desktopProvider = agent.desktopModel?.provider.orEmpty(),
            desktopModel = agent.desktopModel?.model.orEmpty(),
            initialTools = tools.toSet(),
        )
        agentDraft = draft
        loadEditorData(draft.provider)
    }

    private fun firstConfiguredProvider() = providers.firstOrNull { it.configured }?.provider.orEmpty()

    private fun loadEditorData(provider: String) {
        models = emptyList()
        desktopModels = emptyList()
        loadEditorLists()
        loadProviders()
        loadCapabilities()
        loadModels(provider)
        agentDraft?.desktopProvider?.nilIfEmpty?.let(::loadDesktopModels)
    }

    private fun loadEditorLists() {
        val revision = mutationRevision
        request { api ->
            coroutineScope {
                val serversDeferred = async { api.mcpServers() }
                val skillsDeferred = async { api.sharedSkills() }
                val loadedServers = serversDeferred.await()
                val loadedSkills = skillsDeferred.await()
                return@coroutineScope {
                    if (settledSince(revision, SKILL_RECORD)) {
                        loadEditorLists()
                    } else {
                        mcpServers = loadedServers
                        sharedSkills = withLocalChanges(loadedSkills, sharedSkills, SKILL_RECORD, SharedSkill::id)
                    }
                }
            }
        }
    }

    fun loadProviders() {
        request { api ->
            val loaded = api.providerAccounts()
            return@request {
                providers = loaded.map { it.domain() }
                val chosen = firstConfiguredProvider()
                if (agentDraft?.provider?.isEmpty() == true && chosen.isNotEmpty()) {
                    agentDraft = agentDraft?.copy(provider = chosen)
                    loadModels(chosen)
                }
            }
        }
    }

    fun loadModels(providerName: String) {
        if (providerName.isEmpty()) return
        request { api ->
            val loaded = api.models(provider = providerName)
            return@request {
                if (agentDraft?.provider == providerName) {
                    models = loaded
                    if (agentDraft?.model?.isEmpty() == true && loaded.isNotEmpty()) {
                        agentDraft = agentDraft?.copy(model = loaded.first().id)
                    }
                }
            }
        }
    }

    fun changeEditorProvider(newProvider: String) {
        agentDraft = agentDraft?.copy(provider = newProvider, model = "")
        models = emptyList()
        loadModels(newProvider)
    }

    private fun loadDesktopModels(provider: String) {
        confirmed(
            start = {},
            work = { api -> api.models(provider = provider) },
            finish = { result ->
                result.onSuccess { if (agentDraft?.desktopProvider == provider) desktopModels = it }
            },
        )
    }

    fun changeEditorDesktopProvider(provider: String) {
        agentDraft = agentDraft?.copy(desktopProvider = provider, desktopModel = "")
        desktopModels = emptyList()
        if (provider.isNotEmpty()) loadDesktopModels(provider)
    }

    /** The levels the editor's model takes. The picked level stays in the draft, so changing back restores it. */
    val editorLevels: List<String>
        get() = levelsOf(models.firstOrNull { it.id == agentDraft?.model?.trim() })

    val editorLevel: String
        get() = clampLevel(agentDraft?.thinking?.ifEmpty { "off" } ?: "off", editorLevels)

    val modelHint: String
        get() {
            if (providers.none { it.configured }) return "No provider is set up yet. Add one in the web app."
            val p = providers.firstOrNull { it.provider == agentDraft?.provider }
            if (p != null && !p.configured) {
                return "Add an API key for this provider in the web app to load its models."
            }
            return if (models.isEmpty()) "Type a model ID." else "${models.size} models available."
        }

    fun saveAgentEditor() {
        val draft = agentDraft ?: return
        val name = draft.name.trim()
        if (name.isEmpty()) {
            errorText = "Give your agent a name."
            return
        }
        val chosenModel = draft.model.trim()
        if (draft.provider.isEmpty() || chosenModel.isEmpty()) {
            errorText = "Choose a provider and model."
            return
        }
        val tools = mutableListOf<String>()
        if (draft.toolsMemory) tools.add("memory")
        if (draft.toolsDelegate) tools.add("delegate")
        tools.addAll(draft.otherTools)
        if (draft.skills.any { it.name.trim().isEmpty() || it.instructions.trim().isEmpty() }) {
            errorText = "Each skill needs a name and instructions."
            return
        }
        var temperatureVal: Double? = null
        if (draft.temperature.isNotEmpty()) {
            val v = draft.temperature.toDoubleOrNull()
            if (v == null || v !in 0.0..2.0) {
                errorText = "Temperature must be between 0 and 2."
                return
            }
            temperatureVal = v
        }
        var maxOutputVal: Int? = null
        if (draft.maxOutput.isNotEmpty()) {
            val v = draft.maxOutput.toIntOrNull()
            if (v == null || v !in 1..1_000_000) {
                errorText = "Output limit must be between 1 and 1,000,000 tokens."
                return
            }
            maxOutputVal = v
        }
        val inference = Inference(
            thinkingLevel = editorLevel,
            temperature = temperatureVal,
            maxOutputTokens = maxOutputVal,
        )
        val servers = draft.mcpServers.sorted()
        val shared = draft.sharedSkills.filter { id ->
            sharedSkills.any { it.id == id && !it.isDefault }
        }.sorted()

        val input = Agent(
            name = name,
            description = draft.description.nilIfEmpty,
            instructions = draft.instructions.nilIfEmpty,
            avatar = draft.avatar,
            enabled = draft.enabled,
            provider = draft.provider,
            model = chosenModel,
            tools = tools,
            skills = draft.skills,
            sharedSkills = shared,
            mcpServers = servers,
            inference = inference,
            webSearchProvider = draft.webSearchProvider,
            desktopModel = draft.chosenDesktopModel,
        )
        draft.botRequestID?.let { requestID ->
            errorText = ""
            approveEditedBotRequest(requestID, draft, input.copy(sharedSkills = draft.sharedSkills.sorted()))
            return
        }
        val isNew = draft.isNew
        val id = if (isNew) "pending:${newRequestID()}" else draft.agentID
        val old = agents.firstOrNull { it.id == id }
        // The editor only shows skills, and the server treats a submitted list as a full replacement.
        val provisional = input.copy(
            id = id,
            lastMessage = old?.lastMessage,
            skills = old?.skills ?: input.skills,
            sharedSkills = old?.sharedSkills ?: input.sharedSkills,
            // The server derives these and ignores what clients send.
            delegates = old?.delegates,
            sharedMemory = old?.sharedMemory,
        )
        val originalRoute = route

        optimisticMutation(
            key = "agent:$id",
            update = {
                val idx = agents.indexOfFirst { it.id == id }
                agents = if (idx >= 0) {
                    agents.toMutableList().also { it[idx] = provisional }
                } else {
                    agents + provisional
                }
                agentDraft = null
            },
            rollback = {
                val idx = agents.indexOfFirst { it.id == id }
                agents = if (old != null && idx >= 0) {
                    agents.toMutableList().also { it[idx] = it[idx].undoingEdit(provisional, old) }
                } else {
                    agents.filterNot { it.id == id }
                }
                if (agentDraft == null && route == originalRoute) {
                    agentDraft = draft
                }
            },
            work = { api ->
                val saved: Agent = if (isNew) {
                    api.createAgent(input)
                } else {
                    val patch = mapOf<String, JsonElement>(
                        "name" to JsonPrimitive(name),
                        "description" to JsonPrimitive(draft.description),
                        "instructions" to JsonPrimitive(draft.instructions),
                        "provider" to JsonPrimitive(draft.provider),
                        "model" to JsonPrimitive(chosenModel),
                        "avatar" to CrumblePatchJson.encodeToJsonElement(draft.avatar ?: placeholderAvatar(id)),
                        "enabled" to JsonPrimitive(draft.enabled),
                        "tools" to JsonArray(tools.map { JsonPrimitive(it) }),
                        "mcpServers" to JsonArray(servers.map { JsonPrimitive(it) }),
                        "inference" to CrumbleJson.encodeToJsonElement(Inference.serializer(), inference),
                        "webSearchProvider" to JsonPrimitive(input.webSearchProvider),
                        // Null clears it; an absent key would keep the old model.
                        "desktopModel" to CrumblePatchJson.encodeToJsonElement(input.desktopModel),
                    )
                    api.updateAgent(id, patch = patch)
                }
                return@optimisticMutation {
                    val idx = agents.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        val lastMsg = agents[idx].lastMessage
                        agents = upserted(agents, id, saved.copy(lastMessage = saved.lastMessage ?: lastMsg)) { it.id }
                    }
                    if (isNew && route == originalRoute && agentDraft == null) {
                        selectAgent(saved.id)
                    }
                }
            },
        )
    }

    fun duplicateSelected() {
        val id = agentDraft?.agentID ?: selectedAgentID
        val orig = agents.firstOrNull { it.id == id } ?: return
        if (id.startsWith("pending:")) return
        val temporaryID = "pending:${newRequestID()}"
        val copy = orig.copy(id = temporaryID, name = "${orig.name} copy", lastMessage = null)
        val originalRoute = route
        val originalDraft = agentDraft
        optimisticMutation(
            key = "agent:$temporaryID",
            update = {
                agents = agents + copy
                agentDraft = null
            },
            rollback = {
                agents = agents.filterNot { it.id == temporaryID }
                if (agentDraft == null && route == originalRoute) {
                    agentDraft = originalDraft
                }
            },
            work = { api ->
                val created = api.duplicateAgent(id)
                return@optimisticMutation {
                    val idx = agents.indexOfFirst { it.id == temporaryID }
                    if (idx >= 0) {
                        agents = upserted(agents, temporaryID, created) { it.id }
                    }
                    if (route == originalRoute && agentDraft == null) {
                        selectAgent(created.id)
                        openEditor()
                    }
                }
            },
        )
    }

    fun deleteSelected() {
        val id = agentDraft?.agentID ?: selectedAgentID
        if (id.startsWith("pending:")) return
        val index = agents.indexOfFirst { it.id == id }
        if (index < 0) return
        val removed = agents[index]
        val memories = agentMemories[id]
        val wasSelected = selectedAgentID == id
        val originalDraft = agentDraft
        val originalRoute = route
        var routeAfterDeletion = route
        optimisticMutation(
            key = "agent:$id",
            update = {
                agents = agents.filterNot { it.id == id }
                agentMemories = agentMemories - id
                agentDraft = null
                if (wasSelected) {
                    cancelStream()
                    selectionVersion += 1
                    switchComposer("")
                    selectedAgentID = ""
                    snapshot = null
                    messages = emptyList()
                    if (route is Route.AgentChat) {
                        val next = agents.firstOrNull()
                        if (next != null) selectAgent(next.id) else route = null
                    }
                }
                routeAfterDeletion = route
            },
            rollback = {
                val list = agents.toMutableList()
                list.add(minOf(index, list.size), removed)
                agents = list
                if (memories != null) {
                    agentMemories = agentMemories + (id to memories)
                }
                if (wasSelected && route == routeAfterDeletion) {
                    selectAgent(id)
                }
                if (agentDraft == null && route == originalRoute) {
                    agentDraft = originalDraft
                }
            },
            work = { api ->
                api.deleteAgent(id)
                return@optimisticMutation {
                    forgetCachedChat(id)
                    parkedComposers.remove(id)
                    saveDraft(botDraft(id), "")
                }
            },
        )
    }

    // MARK: Workspace panels

    private fun openHome() {
        leaveRoute()
        route = null
    }

    private fun leaveRoute() {
        if (route == Route.General) closeSettings()
        closeGroupChat()
        clearSecretDrafts()
        memoryDraft = null
        taskDraft = null
        skillDraft = null
    }

    fun openPanel(panel: Route) {
        leaveRoute()
        if (panel == Route.Activity && route != panel) activityFeed = ActivityFeedState()
        route = panel
        when (panel) {
            Route.Memory -> loadMemories()
            Route.Skills -> loadSkills()
            Route.Tasks -> {
                loadSchedules()
                loadCapabilities()
                loadEventSources()
            }
            Route.Activity -> loadActivity()
            Route.Avatars -> loadAgentsForPanel()
            Route.Settings, Route.General, Route.Account, Route.NewChat, Route.NewGroup -> Unit
            is Route.AgentChat, is Route.GroupChat -> Unit
        }
    }

    private suspend fun <T> attempt(work: suspend () -> T): Result<T> = try {
        Result.success(work())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun loadSchedules() {
        val revision = mutationRevision
        request { api ->
            val items = try {
                api.schedules()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                schedulesError = friendlyError(e)
                throw e
            }
            return@request {
                if (settledSince(revision, SCHEDULE_RECORD)) {
                    loadSchedules()
                } else {
                    schedules = withLocalChanges(items, schedules, SCHEDULE_RECORD, Schedule::id)
                    schedulesLoaded = true
                    schedulesError = ""
                    if (taskTab == TaskTab.Runs) loadTaskRuns()
                }
            }
        }
    }

    fun filterActivity(kind: String? = activityFeed.kind, botID: String? = activityFeed.botID) {
        if (kind == activityFeed.kind && botID == activityFeed.botID) return
        activityFeed = ActivityFeedState(kind = kind, botID = botID)
        loadActivity()
    }

    private fun loadActivity(keepOlder: Boolean = false) {
        val api = client
        if (api == null) {
            errorText = "Connect to your server in Settings."
            return
        }
        activityLoadVersion += 1
        val version = activityLoadVersion
        val gen = generation
        val asked = activityFeed
        activityFeed = asked.copy(loading = true, loadingOlder = false, error = "", olderError = "")
        fun current() = version == activityLoadVersion && generation == gen
        scope.launch {
            try {
                val page = api.activityFeed(asked.kind, asked.botID, limit = ACTIVITY_PAGE_SIZE)
                if (current()) activityFeed = activityFeed.withFirstPage(page, keepOlder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current()) activityFeed = activityFeed.copy(loading = false, error = friendlyError(e))
            }
        }
    }

    /** Fetches the next page; a failure keeps what is loaded and lets the owner try again. */
    fun loadOlderActivity() {
        val api = client ?: return
        val feed = activityFeed
        val cursor = feed.nextCursor ?: return
        if (feed.loading || feed.loadingOlder) return
        activityLoadVersion += 1
        val version = activityLoadVersion
        val gen = generation
        activityFeed = feed.copy(loadingOlder = true, olderError = "")
        fun current() = version == activityLoadVersion && generation == gen
        scope.launch {
            try {
                val page = api.activityFeed(feed.kind, feed.botID, cursor, ACTIVITY_PAGE_SIZE)
                if (current()) activityFeed = activityFeed.withOlderPage(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current()) activityFeed = activityFeed.copy(loadingOlder = false, olderError = friendlyError(e))
            }
        }
    }

    private fun loadMemories() {
        memoryLoadVersion += 1
        val version = memoryLoadVersion
        val mutationVersion = mutationRevision
        request { api ->
            coroutineScope {
                val bots = api.agents()
                val sharedDeferred = async { attempt { api.memories(null) } }
                val personal = bots.map { bot -> async { bot.id to attempt { api.memories(bot.id) } } }.awaitAll()
                val sharedItems = sharedDeferred.await()
                return@coroutineScope {
                    if (memoryLoadVersion == version && settledSince(mutationVersion, BOT_RECORD, MEMORY_RECORD)) {
                        loadMemories()
                    } else if (memoryLoadVersion == version) {
                        applyBots(bots)
                        sharedItems.onSuccess { sharedMemories = withLocalChanges(it, sharedMemories, MEMORY_RECORD, Memory::id) }
                        agentMemories = personal.mapNotNull { (id, result) ->
                            val local = agentMemories[id]
                            val merged = result.getOrNull()?.let { withLocalChanges(it, local.orEmpty(), MEMORY_RECORD, Memory::id) }
                            (merged ?: local)?.let { id to it }
                        }.toMap()
                        memoryErrors = (personal + (SKILLS_SHARED to sharedItems))
                            .mapNotNull { (id, result) -> result.exceptionOrNull()?.let { id to friendlyError(it) } }
                            .toMap()
                    }
                }
            }
        }
    }

    private fun loadSkills() {
        val mutationVersion = mutationRevision
        request { api ->
            coroutineScope {
                val skillsDeferred = async { api.sharedSkills() }
                val loadedBots = api.agents()
                val personal = loadedBots.map { bot -> async { bot.id to attempt { api.botSkills(bot.id) } } }.awaitAll()
                val loadedSkills = skillsDeferred.await()
                return@coroutineScope {
                    if (settledSince(mutationVersion, BOT_RECORD, SKILL_RECORD, BOT_SKILL_RECORD)) {
                        loadSkills()
                    } else {
                        applyBots(loadedBots)
                        sharedSkills = withLocalChanges(loadedSkills, sharedSkills, SKILL_RECORD, SharedSkill::id)
                        agentSkills = personal.mapNotNull { (id, result) ->
                            val local = agentSkills[id]
                            val merged = result.getOrNull()?.let { withLocalChanges(it, local.orEmpty(), BOT_SKILL_RECORD, SkillPackage::id) }
                            (merged ?: local)?.let { id to it }
                        }.toMap()
                        agentSkillErrors = personal
                            .mapNotNull { (id, result) -> result.exceptionOrNull()?.let { id to friendlyError(it) } }
                            .toMap()
                    }
                }
            }
        }
    }

    /** Refetches what the current page shows, keeping open forms. */
    private fun reloadPage() {
        when (route) {
            Route.Memory -> loadMemories()
            Route.Skills -> loadSkills()
            Route.Activity -> loadActivity(keepOlder = true)
            else -> Unit
        }
    }

    /** For work another system decides: `finish` gets the answer, and nothing reads as done before it. */
    private fun <T> confirmed(start: () -> Unit, work: suspend (APIClient) -> T, finish: (Result<T>) -> Unit) {
        val api = client
        if (api == null) {
            errorText = "Connect to your server in Settings."
            return
        }
        val gen = generation
        start()
        scope.launch {
            val result = attempt { work(api) }
            if (generation == gen) finish(result)
        }
    }

    /** Carries the entry's owner into the form so edits never depend on the active chat. */
    fun openMemoryForm(id: String, agentID: String? = null) {
        val items = if (agentID != null) agentMemories[agentID].orEmpty() else sharedMemories
        val existing = items.firstOrNull { it.id == id }
        memoryDraft = MemoryDraft(memoryID = id, content = existing?.content ?: "", agentID = agentID)
    }

    fun saveMemory() {
        val form = memoryDraft ?: return
        val agent = form.agentID
        val content = form.content.trim()
        if (content.isEmpty()) {
            errorText = "Memory content is required."
            return
        }
        val isNew = form.memoryID.isEmpty()
        val provisionalID = if (isNew) "pending-memory-${newRequestID()}" else form.memoryID
        val currentList = if (agent == null) sharedMemories else agentMemories[agent].orEmpty()
        val old = currentList.firstOrNull { it.id == form.memoryID }
        val provisional = old?.copy(content = content)
            ?: Memory(id = provisionalID, content = content, agentId = agent, source = MemorySource(kind = "owner"), updatedAt = Instant.now().toString())

        optimisticMutation(
            key = "memory:$provisionalID",
            update = {
                memoryDraft = null
                if (agent == null) {
                    val idx = sharedMemories.indexOfFirst { it.id == provisionalID || it.id == form.memoryID }
                    sharedMemories = if (idx >= 0) {
                        sharedMemories.toMutableList().also { it[idx] = provisional }
                    } else {
                        sharedMemories + provisional
                    }
                } else {
                    val items = agentMemories[agent].orEmpty().toMutableList()
                    val idx = items.indexOfFirst { it.id == provisionalID || it.id == form.memoryID }
                    if (idx >= 0) items[idx] = provisional else items.add(provisional)
                    agentMemories = agentMemories + (agent to items)
                }
            },
            rollback = {
                setMemories(agent, recordRestored(memoriesOf(agent), currentList, provisionalID) { it.id })
                if (route == Route.Memory && memoryDraft == null) {
                    memoryDraft = form
                }
            },
            work = { api ->
                val saved = if (isNew) {
                    api.addMemory(agent, content = content)
                } else {
                    api.updateMemory(agent, memoryID = form.memoryID, content = content)
                }
                return@optimisticMutation {
                    if (agent == null) {
                        if (sharedMemories.any { it.id == provisionalID }) {
                            sharedMemories = upserted(sharedMemories, provisionalID, saved) { it.id }
                        }
                    } else {
                        val items = agentMemories[agent].orEmpty()
                        if (items.any { it.id == provisionalID }) {
                            agentMemories = agentMemories + (agent to upserted(items, provisionalID, saved) { it.id })
                        }
                    }
                }
            },
        )
    }

    private fun memoriesOf(owner: String?) = if (owner == null) sharedMemories else agentMemories[owner].orEmpty()

    private fun setMemories(owner: String?, items: List<Memory>) {
        if (owner == null) sharedMemories = items else agentMemories = agentMemories + (owner to items)
    }

    fun deleteMemory(id: String, agentID: String? = null) {
        val currentList = if (agentID == null) sharedMemories else agentMemories[agentID].orEmpty()
        val oldIndex = currentList.indexOfFirst { it.id == id }
        val old = if (oldIndex >= 0) currentList[oldIndex] else null
        optimisticMutation(
            key = "memory:$id",
            update = {
                if (agentID == null) {
                    sharedMemories = sharedMemories.filterNot { it.id == id }
                } else {
                    val updated = agentMemories[agentID].orEmpty().filterNot { it.id == id }
                    agentMemories = agentMemories + (agentID to updated)
                }
            },
            rollback = {
                if (old == null) return@optimisticMutation
                if (agentID == null) {
                    val list = sharedMemories.toMutableList()
                    list.add(minOf(if (oldIndex >= 0) oldIndex else list.size, list.size), old)
                    sharedMemories = list
                } else {
                    val list = agentMemories[agentID].orEmpty().toMutableList()
                    list.add(minOf(if (oldIndex >= 0) oldIndex else list.size, list.size), old)
                    agentMemories = agentMemories + (agentID to list)
                }
            },
            work = { api ->
                api.deleteMemory(agentID, memoryID = id)
                null
            },
        )
    }

    fun openTaskForm(id: String, agentID: String? = null) {
        if (agents.isEmpty()) {
            errorText = "Create an agent before adding a task."
            return
        }
        val fallback = if (agents.any { it.id == selectedAgentID }) selectedAgentID else agents[0].id
        var form = TaskDraft(scheduleID = id, agentID = agentID ?: fallback)
        val schedule = schedules.firstOrNull { it.id == id }
        if (schedule != null) {
            form = form.copy(
                agentID = schedule.agentId,
                name = schedule.name,
                prompt = schedule.prompt,
                kind = schedule.kind,
                command = schedule.command.orEmpty(),
                timeoutSeconds = schedule.timeoutSeconds.toString(),
                notify = schedule.notify,
                onEvent = schedule.eventSourceId != null,
                eventSourceID = schedule.eventSourceId.orEmpty(),
                repeats = !schedule.cron.isNullOrEmpty(),
                customCron = !schedule.cron.isNullOrEmpty() && recurrenceOf(schedule.cron) == null,
                cron = if (!schedule.cron.isNullOrEmpty()) schedule.cron else form.cron,
                timezone = schedule.timezone ?: ZoneId.systemDefault().id,
                enabled = schedule.enabled,
            )
            if (schedule.runAt != null) {
                val date = taskRunDate(schedule.runAt)
                if (date == null) {
                    errorText = "This task has an invalid run time."
                    return
                }
                form = form.copy(runDate = date)
            }
        }
        taskDraft = form
    }

    fun saveTask() {
        val form = taskDraft ?: return
        val name = form.name.trim()
        val prompt = form.prompt.trim()
        val timezone = form.timezone.trim()
        val isScript = form.kind == "script"
        val cron = if (!form.onEvent && form.repeats) form.cron.trim() else ""
        val runAt = if (!form.onEvent && !form.repeats) form.runDate.toIso8601SecondString() else ""
        val sourceID = if (form.onEvent) form.eventSourceID else ""
        if (form.agentID.isEmpty()) {
            errorText = "Choose which bot runs this task."
            return
        }
        val validationError = validateTask(
            name = name,
            prompt = prompt,
            cron = cron,
            runAt = runAt,
            timezone = timezone,
            kind = form.kind,
            command = form.command,
            timeoutSeconds = form.timeoutSeconds,
            onEvent = form.onEvent,
            eventSourceID = sourceID,
        )
        if (validationError != null) {
            errorText = validationError
            return
        }
        val timeoutSeconds = if (isScript) form.timeoutSeconds.trim().toLong() else 600L
        val payload = LinkedHashMap<String, JsonElement>().apply {
            put("agentId", JsonPrimitive(form.agentID))
            put("name", JsonPrimitive(name))
            put("kind", JsonPrimitive(form.kind))
            put("prompt", JsonPrimitive(if (isScript) "" else prompt))
            put("command", if (isScript) JsonPrimitive(form.command) else JsonNull)
            put("timeoutSeconds", JsonPrimitive(timeoutSeconds))
            put("notify", JsonPrimitive(form.notify))
            put("timezone", JsonPrimitive(timezone))
            put("enabled", JsonPrimitive(form.enabled))
        }
        val isNew = form.scheduleID.isEmpty()
        val provisionalID = if (isNew) "pending-schedule-${newRequestID()}" else form.scheduleID
        val old = schedules.firstOrNull { it.id == form.scheduleID }
        val provisional = Schedule(
            id = provisionalID,
            agentId = form.agentID,
            name = name,
            cron = cron.ifEmpty { null },
            prompt = if (isScript) "" else prompt,
            timezone = timezone,
            runAt = runAt.ifEmpty { null },
            enabled = form.enabled,
            createdByAgentId = old?.createdByAgentId,
            kind = form.kind,
            command = if (isScript) form.command else null,
            timeoutSeconds = timeoutSeconds,
            notify = form.notify,
            eventSourceId = sourceID.ifEmpty { null },
            description = "Saving…",
            timing = "Saving…",
            // Without a next run a one-off reads as finished and leaves the list before it is saved.
            nextRunAt = runAt.ifEmpty { old?.nextRunAt.takeIf { cron.isNotEmpty() } },
        )
        val original = schedules

        optimisticMutation(
            key = "schedule:$provisionalID",
            update = {
                taskDraft = null
                val idx = schedules.indexOfFirst { it.id == provisionalID || it.id == form.scheduleID }
                schedules = if (idx >= 0) {
                    schedules.toMutableList().also { it[idx] = provisional }
                } else {
                    schedules + provisional
                }
            },
            rollback = {
                schedules = recordRestored(schedules, original, provisionalID) { it.id }
                if (route == Route.Tasks && taskDraft == null) {
                    taskDraft = form
                }
            },
            work = { api ->
                val trigger = mapOf("cron" to cron, "runAt" to runAt, "eventSourceId" to sourceID)
                val saved = if (isNew) {
                    trigger.filterValues { it.isNotEmpty() }.forEach { (key, value) -> payload[key] = JsonPrimitive(value) }
                    api.createSchedule(payload)
                } else {
                    trigger.forEach { (key, value) -> payload[key] = if (value.isEmpty()) JsonNull else JsonPrimitive(value) }
                    api.updateSchedule(form.scheduleID, patch = payload)
                }
                return@optimisticMutation {
                    if (schedules.any { it.id == provisionalID }) {
                        schedules = upserted(schedules, provisionalID, saved) { it.id }
                    }
                }
            },
        )
    }

    /** Picks the bot for the open form; a bot without coding jobs can't keep a script. */
    fun pickTaskBot(agentID: String) {
        val form = taskDraft ?: return
        val canScript = canRunScripts(capabilities, agents.firstOrNull { it.id == agentID })
        taskDraft = form.copy(agentID = agentID, kind = if (canScript) form.kind else "prompt")
    }

    fun toggleSchedule(id: String) {
        val old = schedules.firstOrNull { it.id == id } ?: return
        fun replace(schedule: Schedule) {
            schedules = schedules.map { if (it.id == id) schedule else it }
        }
        val enabled = !old.enabled
        optimisticMutation(
            key = "schedule:$id",
            update = { replace(old.copy(enabled = enabled, timing = if (enabled) old.description else "Paused · ${old.description}")) },
            rollback = { replace(old) },
            work = { api ->
                val saved = api.updateSchedule(id, patch = mapOf("enabled" to JsonPrimitive(enabled)))
                return@optimisticMutation { replace(saved) }
            },
        )
    }

    fun schedulePending(id: String): Boolean = id.startsWith("pending-") || pendingMutations.contains("schedule:$id")

    fun selectTaskTab(tab: TaskTab) {
        taskTab = tab
        when (tab) {
            TaskTab.Tasks -> Unit
            TaskTab.Runs -> loadTaskRuns()
            TaskTab.EventSources -> loadEventSources()
        }
    }

    private fun loadCapabilities() {
        confirmed(
            start = {},
            work = { api -> api.capabilities() },
            finish = { result -> result.onSuccess { capabilities = it } },
        )
    }

    private fun loadTaskRuns() {
        taskRunsVersion += 1
        val version = taskRunsVersion
        val scripts = schedules.filter { it.kind == "script" && !it.id.startsWith("pending-") }
        confirmed(
            start = { taskRunsLoading = true },
            work = { api ->
                coroutineScope {
                    val history = async { attempt { api.executions() } }
                    val runs = scripts.map { script -> async { script.id to attempt { api.scriptRuns(script.id) } } }.awaitAll()
                    history.await() to runs
                }
            },
            finish = { result ->
                if (version == taskRunsVersion) {
                    taskRunsLoading = false
                    result
                        .onSuccess { (history, runs) ->
                            history.onSuccess { executions = it }
                            scriptRuns = runs.mapNotNull { (id, loaded) ->
                                (loaded.getOrNull() ?: scriptRuns[id])?.let { id to it }
                            }.toMap()
                            taskRunsError = history.exceptionOrNull()?.let { friendlyError(it) }
                                ?: if (runs.any { it.second.isFailure }) "Some script runs could not be loaded." else ""
                        }
                        .onFailure { taskRunsError = friendlyError(it) }
                }
            },
        )
    }

    private fun loadEventSources() {
        eventSourcesVersion += 1
        val version = eventSourcesVersion
        confirmed(
            start = {},
            work = { api -> api.eventSources() },
            finish = { result ->
                if (version == eventSourcesVersion) {
                    result
                        .onSuccess {
                            eventSources = it
                            eventSourcesLoaded = true
                            eventSourcesError = ""
                        }
                        .onFailure { eventSourcesError = friendlyError(it) }
                }
            },
        )
    }

    fun openEventSourceForm() {
        eventSourceDraft = EventSourceDraft()
    }

    /** The relay decides, and its address is shown once, so a pending sheet can't be dismissed. */
    fun closeEventSourceForm() {
        if (eventSourceDraft?.saving != true) eventSourceDraft = null
    }

    /** The relay creates the source, so it joins the list only once the server confirms. */
    fun saveEventSource() {
        val form = eventSourceDraft ?: return
        val name = form.name.trim()
        if (name.isEmpty() || form.saving) return
        confirmed(
            start = { eventSourceDraft = form.copy(saving = true, error = "") },
            work = { api -> api.createEventSource(name) },
            finish = { result ->
                result
                    .onSuccess { created ->
                        eventSourceDraft = eventSourceDraft?.copy(saving = false, created = created)
                        eventSources = eventSources.filterNot { it.id == created.id } +
                            EventSource(created.id, created.name, created.createdAt, created.usedBy)
                        loadEventSources()
                    }
                    .onFailure { eventSourceDraft = eventSourceDraft?.copy(saving = false, error = friendlyError(it)) }
            },
        )
    }

    /** The relay removes the address, so the row stays pending until the server confirms. */
    fun deleteEventSource(id: String) {
        if (deletingEventSources.contains(id)) return
        confirmed(
            start = {
                deletingEventSources = deletingEventSources + id
                eventSourceErrors = eventSourceErrors - id
            },
            work = { api -> api.deleteEventSource(id) },
            finish = { result ->
                deletingEventSources = deletingEventSources - id
                result
                    .onSuccess {
                        eventSources = eventSources.filterNot { it.id == id }
                        loadEventSources()
                    }
                    .onFailure { eventSourceErrors = eventSourceErrors + (id to friendlyError(it)) }
            },
        )
    }

    fun runSchedule(id: String) {
        val routeAtStart = route
        val owner = schedules.firstOrNull { it.id == id }?.agentId
        showNotice("Starting task…")
        optimisticMutation(
            key = "schedule-run:$id",
            onBusy = {},
            update = {},
            rollback = { showNotice("Task could not be started") },
            work = { api ->
                val execution = api.runSchedule(id)
                return@optimisticMutation {
                    showNotice("Task started")
                    // A script run's admission names only the run, not the bot.
                    val agent = execution.agentId ?: owner
                    if (route == routeAtStart && routeAtStart == Route.Tasks && !agent.isNullOrEmpty()) {
                        selectAgent(agent)
                    }
                }
            },
        )
    }

    fun deleteSchedule(id: String) {
        val index = schedules.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = schedules[index]
        optimisticMutation(
            key = "schedule:$id",
            update = { schedules = schedules.filterNot { it.id == id } },
            rollback = {
                val list = schedules.toMutableList()
                list.add(minOf(index, list.size), old)
                schedules = list
            },
            work = { api ->
                api.deleteSchedule(id)
                null
            },
        )
    }

    /** Servers a bot may use: the shared ones and its own. */
    fun usableServers(forAgentID: String): List<MCPServer> =
        mcpServers.filter { it.agentId == null || it.agentId == forAgentID }

    fun openSkillForm(id: String, agentID: String? = null) {
        var form = SkillDraft(skillID = id, agentID = agentID)
        if (agentID == null) {
            val skill = sharedSkills.firstOrNull { it.id == id }
            if (skill != null) form = form.copy(name = skill.name, instructions = skill.instructions, usedBy = botsUsing(agents, id))
        } else {
            val skill = agentSkills[agentID].orEmpty().firstOrNull { it.id == id }
            if (skill != null) form = form.copy(name = skill.name, instructions = skill.instructions)
        }
        skillDraft = form
    }

    /** Keeps the bot's inline skills current: saving the bot replaces its skills with that list. */
    private fun setAgentSkills(agentID: String, skills: List<SkillPackage>) {
        agentSkills = agentSkills + (agentID to skills)
        agents = agents.map { agent -> if (agent.id == agentID) agent.copy(skills = skills.map { it.asInline() }) else agent }
    }

    private fun setSharedSkillsOf(agentID: String, change: (List<String>) -> List<String>) {
        agents = agents.map { agent ->
            if (agent.id == agentID) agent.copy(sharedSkills = change(agent.sharedSkills.orEmpty())) else agent
        }
    }

    fun saveSkill() {
        val form = skillDraft ?: return
        val name = form.name.trim()
        val instructions = form.instructions.trim()
        if (name.isEmpty() || instructions.isEmpty()) {
            errorText = "Each skill needs a name and instructions."
            return
        }
        val isNew = form.skillID.isEmpty()
        if (form.agentID != null) {
            saveAgentSkill(form, name, instructions, isNew)
            return
        }
        val provisionalID = if (isNew) "pending-skill-${newRequestID()}" else form.skillID
        val oldSkills = sharedSkills
        val old = oldSkills.firstOrNull { it.id == form.skillID }
        val provisional = old?.copy(name = name, instructions = instructions) ?: SharedSkill(id = provisionalID, name = name, instructions = instructions, source = "shared")
        val using = botsUsing(agents, form.skillID)
        val added = form.usedBy - using
        val dropped = using - form.usedBy
        fun assign(id: String) {
            for (bot in added) setSharedSkillsOf(bot) { (it + id).distinct().sorted() }
            for (bot in dropped) setSharedSkillsOf(bot) { it - id }
        }
        fun unassign(id: String, bots: Set<String>) {
            for (bot in bots) setSharedSkillsOf(bot) { if (bot in added) it - id else (it + id).distinct().sorted() }
        }

        optimisticMutation(
            key = "skill:$provisionalID",
            touches = (added + dropped).map { "$BOT_RECORD$it" }.toSet(),
            update = {
                skillDraft = null
                sharedSkills = upserted(sharedSkills, provisionalID, provisional) { it.id }
                assign(provisionalID)
            },
            rollback = {
                sharedSkills = recordRestored(sharedSkills, oldSkills, provisionalID) { it.id }
                unassign(provisionalID, added + dropped)
                if (route == Route.Skills && skillDraft == null) {
                    skillDraft = form
                }
            },
            work = { api ->
                val saved = if (isNew) {
                    api.createSharedSkill(name = name, instructions = instructions)
                } else {
                    api.updateSharedSkill(form.skillID, name = name, instructions = instructions)
                }
                val failed = coroutineScope {
                    (added + dropped).map { bot ->
                        async { bot to attempt { api.assignSkill(saved.id, bot, bot in added) } }
                    }.awaitAll().filter { (_, result) -> result.isFailure }
                }
                return@optimisticMutation {
                    sharedSkills = upserted(sharedSkills, provisionalID, provisional.copy(id = saved.id, name = saved.name, instructions = saved.instructions)) { it.id }
                    if (isNew) {
                        for (bot in added) setSharedSkillsOf(bot) { ids -> ids.map { if (it == provisionalID) saved.id else it } }
                    }
                    // The skill saved, so a failed assignment rolls back alone and retrying won't duplicate it.
                    unassign(saved.id, failed.map { it.first }.toSet())
                    failed.firstOrNull()?.second?.exceptionOrNull()?.let { errorText = friendlyError(it) }
                }
            },
        )
    }

    private fun saveAgentSkill(form: SkillDraft, name: String, instructions: String, isNew: Boolean) {
        val agentID = form.agentID ?: return
        if (agents.none { it.id == agentID }) return
        val provisionalID = if (isNew) "pending-skill-${newRequestID()}" else form.skillID
        val original = agentSkills[agentID].orEmpty()
        val provisional = provisionalSkill(provisionalID, agentID, name, instructions, original.firstOrNull { it.id == form.skillID })
        optimisticMutation(
            key = "agent-skill:$provisionalID",
            touches = setOf("$BOT_RECORD$agentID"),
            update = {
                skillDraft = null
                setAgentSkills(agentID, upserted(agentSkills[agentID].orEmpty(), provisionalID, provisional) { it.id })
            },
            rollback = {
                setAgentSkills(agentID, recordRestored(agentSkills[agentID].orEmpty(), original, provisionalID) { it.id })
                if (route == Route.Skills && skillDraft == null) skillDraft = form
            },
            work = { api ->
                val saved = if (isNew) api.createBotSkill(agentID, name, instructions) else api.updateBotSkill(agentID, form.skillID, name, instructions)
                return@optimisticMutation {
                    setAgentSkills(agentID, upserted(agentSkills[agentID].orEmpty(), provisionalID, saved) { it.id })
                }
            },
        )
    }

    fun deleteAgentSkill(agentID: String, skillID: String) {
        val original = agentSkills[agentID].orEmpty()
        if (original.none { it.id == skillID }) return
        optimisticMutation(
            key = "agent-skill:$skillID",
            touches = setOf("$BOT_RECORD$agentID"),
            update = { setAgentSkills(agentID, agentSkills[agentID].orEmpty().filterNot { it.id == skillID }) },
            rollback = { setAgentSkills(agentID, recordRestored(agentSkills[agentID].orEmpty(), original, skillID) { it.id }) },
            work = { api ->
                api.deleteBotSkill(agentID, skillID)
                null
            },
        )
    }

    fun toggleSkillPin(agentID: String, skillID: String) {
        val original = agentSkills[agentID].orEmpty()
        val skill = original.firstOrNull { it.id == skillID } ?: return
        val pinned = !skill.pinned
        optimisticMutation(
            key = "agent-skill:$skillID",
            touches = setOf("$BOT_RECORD$agentID"),
            update = { setAgentSkills(agentID, upserted(agentSkills[agentID].orEmpty(), skillID, skill.copy(pinned = pinned)) { it.id }) },
            rollback = { setAgentSkills(agentID, recordRestored(agentSkills[agentID].orEmpty(), original, skillID) { it.id }) },
            work = { api ->
                val saved = api.pinBotSkill(agentID, skillID, pinned)
                return@optimisticMutation {
                    setAgentSkills(agentID, upserted(agentSkills[agentID].orEmpty(), skillID, saved) { it.id })
                }
            },
        )
    }

    fun deleteSharedSkill(id: String) {
        val index = sharedSkills.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = sharedSkills[index]
        val affectedAgents = agents.mapNotNull { agent ->
            if (agent.sharedSkills?.contains(id) == true) agent.id to agent.sharedSkills else null
        }
        optimisticMutation(
            key = "skill:$id",
            touches = affectedAgents.map { "$BOT_RECORD${it.first}" }.toSet(),
            update = {
                sharedSkills = sharedSkills.filterNot { it.id == id }
                agents = agents.map { agent ->
                    if (agent.sharedSkills?.contains(id) == true) {
                        agent.copy(sharedSkills = agent.sharedSkills.filterNot { it == id })
                    } else {
                        agent
                    }
                }
            },
            rollback = {
                val list = sharedSkills.toMutableList()
                list.add(minOf(index, list.size), old)
                sharedSkills = list
                val restoredMap = affectedAgents.toMap()
                agents = agents.map { agent ->
                    val orig = restoredMap[agent.id]
                    if (orig != null) agent.copy(sharedSkills = orig) else agent
                }
            },
            work = { api ->
                api.deleteSharedSkill(id)
                null
            },
        )
    }

    fun setSharedSkillEnabled(agentID: String, skillID: String, enabled: Boolean) {
        if (agents.none { it.id == agentID } || sharedSkills.none { it.id == skillID && !it.isDefault }) return
        val current = agents.first { it.id == agentID }.sharedSkills.orEmpty()
        val next = if (enabled) (current + skillID).distinct() else current.filterNot { it == skillID }
        if (next == current) return
        optimisticMutation(
            key = "agent-shared-skills:$agentID",
            touches = setOf("$BOT_RECORD$agentID"),
            update = { agents = agents.map { if (it.id == agentID) it.copy(sharedSkills = next) else it } },
            rollback = { agents = agents.map { if (it.id == agentID) it.copy(sharedSkills = current) else it } },
            work = { api ->
                val patch = mapOf<String, JsonElement>("sharedSkills" to JsonArray(next.map { JsonPrimitive(it) }))
                val saved = api.updateAgent(agentID, patch)
                return@optimisticMutation {
                    agents = agents.map { agent -> if (agent.id == agentID) agent.copy(sharedSkills = saved.sharedSkills) else agent }
                }
            },
        )
    }

    /** An editable shared copy of an installed skill, documents included. */
    fun copySkill(id: String) {
        val skill = sharedSkills.firstOrNull { it.id == id } ?: return
        val provisionalID = "pending-skill-${newRequestID()}"
        val provisional = skill.copy(id = provisionalID, name = "${skill.name} copy".take(100), origin = null, update = null)
        optimisticMutation(
            key = "skill:$provisionalID",
            update = { sharedSkills = sharedSkills + provisional },
            rollback = { sharedSkills = sharedSkills.filterNot { it.id == provisionalID } },
            work = { api ->
                val saved = api.copySharedSkill(api.skillPackage(id))
                return@optimisticMutation { sharedSkills = upserted(sharedSkills, provisionalID, saved) { it.id } }
            },
        )
    }

    fun toggleSkillSelection(ids: List<String>, selected: Boolean) {
        selectedSkills = if (selected) selectedSkills + ids else selectedSkills - ids.toSet()
    }

    /** Deletes apply at once, and shared assignments too. Copies and updates show once the server has them. */
    fun runBulkAction(action: BulkAction, targets: Set<String> = emptySet()) {
        val picked = selectionCatalog(sharedSkills, agents, agentSkills).filter { it.id in selectedSkills }
        if (picked.isEmpty()) return
        val bots = agents.filter { it.id in targets }
        val ids = picked.map { it.id }.toSet()
        val shared = picked.filter { it.botID == null }.map { it.id }.toSet()
        val originalShared = sharedSkills
        val originalPersonal = agentSkills
        val originalAgents = agents
        bulkReport = emptyList()

        fun undo(agentID: String, skills: Set<String>) {
            val before = originalAgents.firstOrNull { it.id == agentID }?.sharedSkills.orEmpty()
            setSharedSkillsOf(agentID) { current ->
                (current - skills + before.filter { it in skills }).distinct().sorted()
            }
        }

        fun restoreDeleted(failed: Set<String>) {
            sharedSkills = restoring(sharedSkills, originalShared, failed) { it.id }
            for ((agentID, skills) in originalPersonal) {
                setAgentSkills(agentID, restoring(agentSkills[agentID].orEmpty(), skills, failed) { it.id })
            }
            for (agent in originalAgents) undo(agent.id, failed intersect shared)
        }

        optimisticMutation(
            key = "skills:bulk",
            touches = shared.map { "$SKILL_RECORD$it" }.toSet() + (ids - shared).map { "$BOT_SKILL_RECORD$it" } +
                (if (action == BulkAction.delete) agents else bots).map { "$BOT_RECORD${it.id}" },
            update = {
                when (action) {
                    BulkAction.delete -> {
                        sharedSkills = sharedSkills.filterNot { it.id in ids }
                        for ((agentID, skills) in agentSkills) setAgentSkills(agentID, skills.filterNot { it.id in ids })
                        for (agent in agents) setSharedSkillsOf(agent.id) { it - shared }
                    }
                    BulkAction.assign -> for (bot in bots) setSharedSkillsOf(bot.id) { (it + shared).distinct().sorted() }
                    BulkAction.remove -> for (bot in bots) setSharedSkillsOf(bot.id) { it - shared }
                    BulkAction.update -> Unit
                }
            },
            rollback = {
                when (action) {
                    BulkAction.delete -> restoreDeleted(ids)
                    BulkAction.assign, BulkAction.remove -> for (bot in bots) undo(bot.id, shared)
                    BulkAction.update -> Unit
                }
            },
            work = { api ->
                val result = applyBulkAction(api, action, picked, bots)
                return@optimisticMutation {
                    if (action == BulkAction.delete) restoreDeleted(result.failed)
                    for ((skill, bot) in result.failedAssignments) if (skill in shared) undo(bot, setOf(skill))
                    selectedSkills = result.failed
                    bulkReport = result.messages
                    loadSkills()
                }
            },
        )
    }

    fun checkSkillUpdates() {
        if (checkingSkillUpdates) return
        confirmed(
            start = {
                checkingSkillUpdates = true
                skillCheckFailures = emptyList()
            },
            work = { api -> api.checkSkillUpdates() },
            finish = { result ->
                checkingSkillUpdates = false
                result
                    .onSuccess { skillCheckFailures = updateCheckFailures(it) }
                    .onFailure { skillCheckFailures = listOf(friendlyError(it)) }
                loadSkills()
            },
        )
    }

    fun openSkillInstall() {
        skillBrowseVersion += 1
        skillInstall = SkillInstallForm()
    }

    fun closeSkillInstall() {
        skillBrowseVersion += 1
        skillInstall = null
    }

    fun changeSkillLocation(location: String) {
        val form = skillInstall ?: return
        skillBrowseVersion += 1
        skillInstall = form.copy(location = location, listing = null, picked = emptySet(), browsing = false, error = "")
    }

    /** A late answer for an older location never replaces what the owner typed since. */
    fun findSkills() {
        val form = skillInstall ?: return
        val location = form.location.trim()
        if (location.isEmpty()) return
        skillBrowseVersion += 1
        val version = skillBrowseVersion
        confirmed(
            start = { skillInstall = form.copy(browsing = true, error = "") },
            work = { api -> api.browseSkills(location) },
            finish = { result ->
                val current = skillInstall
                if (current != null && skillBrowseVersion == version) {
                    skillInstall = result.fold(
                        onSuccess = { current.copy(listing = it, picked = newSkillPaths(it), browsing = false) },
                        onFailure = { current.copy(listing = null, browsing = false, error = friendlyError(it)) },
                    )
                }
            },
        )
    }

    fun toggleSkillCandidate(path: String, selected: Boolean) {
        val form = skillInstall ?: return
        skillInstall = form.copy(picked = if (selected) form.picked + path else form.picked - path)
    }

    fun toggleInstallBot(id: String, selected: Boolean) {
        val form = skillInstall ?: return
        skillInstall = form.copy(botIDs = if (selected) form.botIDs + id else form.botIDs - id)
    }

    /** Depends on GitHub, so the sheet stays open and pending until the server confirms. */
    fun installSkills() {
        val form = skillInstall ?: return
        val listing = form.listing ?: return
        if (form.installing || form.picked.isEmpty()) return
        confirmed(
            start = { skillInstall = form.copy(installing = true, error = "") },
            work = { api -> api.installSkills(listing, form.picked, form.botIDs) },
            finish = { result ->
                result
                    .onSuccess {
                        skillInstall = null
                        loadSkills()
                    }
                    .onFailure { skillInstall = skillInstall?.copy(installing = false, error = friendlyError(it)) }
            },
        )
    }

    fun openInstalledSkill(id: String) {
        confirmed(
            start = { installedSkill = InstalledSkillView(id) },
            work = { api -> api.skillPackage(id) },
            finish = { result ->
                installedSkill = installedSkill?.takeIf { it.skillID == id }?.let { view ->
                    result.fold({ view.copy(full = it) }, { view.copy(error = friendlyError(it)) })
                }
            },
        )
    }

    fun openSkillUpdate(id: String) {
        installedSkill = null
        confirmed(
            start = { skillUpdate = SkillUpdateView(id) },
            work = { api ->
                coroutineScope {
                    val current = async { attempt { api.skillPackage(id) } }
                    val pending = async { attempt { api.pendingSkillUpdate(id) } }
                    current.await() to pending.await()
                }
            },
            finish = { result ->
                val current = result.getOrNull()?.first
                val pending = result.getOrNull()?.second
                val failure = result.exceptionOrNull() ?: pending?.exceptionOrNull() ?: current?.exceptionOrNull()
                skillUpdate = skillUpdate?.takeIf { it.skillID == id }?.copy(
                    current = current?.getOrNull(),
                    pending = pending?.getOrNull(),
                    error = failure?.let(::friendlyError).orEmpty(),
                )
            },
        )
    }

    /** The server applies what it read from GitHub, so the row changes only once it confirms. */
    fun applySkillUpdate() {
        val view = skillUpdate ?: return
        if (view.pending == null || view.applying) return
        val id = view.skillID
        confirmed(
            start = { skillUpdate = view.copy(applying = true, error = "") },
            work = { api -> api.applySkillUpdate(id) },
            finish = { result ->
                result
                    .onSuccess { saved ->
                        sharedSkills = upserted(sharedSkills, id, saved.asShared()) { it.id }
                        skillUpdate = null
                        loadSkills()
                    }
                    .onFailure { failure -> skillUpdate = skillUpdate?.copy(applying = false, error = friendlyError(failure)) }
            },
        )
    }

    // MARK: Change history

    fun openChanges() {
        showingChanges = true
        loadChanges(before = null)
    }

    fun closeChanges() {
        changesVersion += 1
        changesLoading = false
        showingChanges = false
    }

    fun loadMoreChanges() {
        if (!changesLoading) changes.lastOrNull()?.let { loadChanges(before = it.id) }
    }

    private fun loadChanges(before: String?) {
        changesVersion += 1
        val version = changesVersion
        confirmed(
            start = {
                changesLoading = true
                changesError = ""
            },
            work = { api -> api.changes(before) },
            finish = { result ->
                if (changesVersion == version) {
                    changesLoading = false
                    result
                        .onSuccess { page ->
                            changes = if (before == null) page else (changes + page).distinctBy { it.id }
                            changesHasMore = page.size == CHANGES_PAGE
                        }
                        .onFailure { changesError = friendlyError(it) }
                }
            },
        )
    }

    private fun revertSkillRecord(change: LearnedChange): () -> Unit {
        val id = change.targetId
        val restored = change.beforeSkill
        val owner = change.agentId
        if (owner != null) {
            val original = agentSkills[owner].orEmpty()
            setAgentSkills(owner, upserted(original, id, restored) { it.id })
            return { setAgentSkills(owner, recordRestored(agentSkills[owner].orEmpty(), original, id) { it.id }) }
        }
        val original = sharedSkills
        sharedSkills = upserted(original, id, restored?.asShared()) { it.id }
        return { sharedSkills = recordRestored(sharedSkills, original, id) { it.id } }
    }

    /** Applies the state before `change` to its record and returns how to undo that. */
    private fun revertRecord(change: LearnedChange): () -> Unit {
        if (change.target == "skill") return revertSkillRecord(change)
        val owner = change.agentId
        val original = memoriesOf(owner)
        setMemories(owner, upserted(original, change.targetId, change.beforeMemory) { it.id })
        return { setMemories(owner, recordRestored(memoriesOf(owner), original, change.targetId) { it.id }) }
    }

    fun revertChange(change: LearnedChange) {
        var undo: () -> Unit = {}
        optimisticMutation(
            key = "change:${change.id}",
            touches = setOf(
                when {
                    change.target != "skill" -> "$MEMORY_RECORD${change.targetId}"
                    change.agentId != null -> "$BOT_SKILL_RECORD${change.targetId}"
                    else -> "$SKILL_RECORD${change.targetId}"
                },
            ),
            update = { undo = revertRecord(change) },
            rollback = { undo() },
            work = { api ->
                val reverted = api.revertChange(change.id)
                return@optimisticMutation {
                    changes = listOf(reverted) + changes
                    reloadPage()
                }
            },
        )
    }

    // MARK: Avatars

    fun pickAvatarBot(id: String) {
        val page = avatarPage
        if (page.saving) return
        val bot = agents.firstOrNull { it.id == id }
        avatarPage = page.copy(botID = id, value = bot?.let { it.avatar ?: placeholderAvatar(it.id) } ?: page.value, saved = false)
    }

    fun changeAvatar(value: BotAvatarDefinition) {
        if (!avatarPage.saving) avatarPage = avatarPage.copy(value = value, saved = false)
    }

    /** The page locks while saving: a PATCH can't be cancelled, and edits could save out of order. */
    fun saveAvatar() {
        val page = avatarPage
        val id = page.botID
        if (page.saving) return
        val old = agents.firstOrNull { it.id == id } ?: return
        fun setAvatar(avatar: BotAvatarDefinition?) {
            agents = agents.map { if (it.id == id) it.copy(avatar = avatar) else it }
        }
        optimisticMutation(
            key = "agent-avatar:$id",
            touches = setOf("$BOT_RECORD$id"),
            update = {
                setAvatar(page.value)
                avatarPage = page.copy(saving = true, saved = false)
            },
            rollback = {
                setAvatar(old.avatar)
                avatarPage = avatarPage.copy(saving = false)
            },
            work = { api ->
                val saved = api.updateAgent(id, mapOf("avatar" to CrumblePatchJson.encodeToJsonElement(page.value)))
                return@optimisticMutation {
                    setAvatar(saved.avatar)
                    avatarPage = avatarPage.copy(saving = false, saved = true)
                }
            },
        )
    }

    // MARK: Settings

    /** Applies at once. One save runs at a time, so the account ends on the newest choice. */
    fun chooseTheme(next: ThemeChoice) {
        if (next == theme) return
        themeChanges += 1
        adoptTheme(next)
        if (client?.token.isNullOrEmpty()) accountTheme = next
        else if (!themeSaving) saveTheme()
    }

    private fun adoptTheme(next: ThemeChoice) {
        theme = next
        credentials.write(THEME_KEY, next.value)
    }

    private fun saveTheme() {
        val sending = theme
        confirmed(
            start = { themeSaving = true },
            work = { api -> api.updateTheme(sending.value) },
            finish = { result ->
                themeSaving = false
                result.onSuccess { accountTheme = sending }
                if (theme != sending) saveTheme()
                else result.onFailure {
                    adoptTheme(accountTheme)
                    errorText = friendlyError(it)
                }
            },
        )
    }

    fun changeReplyNotifications(on: Boolean) {
        replyNotifications = on
        notificationSettings.replies = on
        onNotificationsChanged()
    }

    fun changeNotificationSound(on: Boolean) {
        notificationSound = on
        notificationSettings.sound = on
    }

    fun runPalette(action: PaletteAction) {
        showingPalette = false
        when (action) {
            is PaletteAction.OpenBot -> navigate(Route.AgentChat(action.id))
            is PaletteAction.OpenGroup -> navigate(Route.GroupChat(action.id))
            PaletteAction.NewBot -> createAgent()
            PaletteAction.NewGroup -> navigate(Route.NewGroup)
            is PaletteAction.OpenPage -> navigate(action.route)
            is PaletteAction.SetTheme -> chooseTheme(action.theme)
        }
    }

    fun closeSettings() {
        errorText = ""
        password = ""
        inviteCode = ""
    }

    fun saveSettings() {
        if (savingConnection) return
        val rawURL = serverURL.trim().trimmingTrailingSlashes
        val trimmedToken = token.trim()
        val validUrl = try {
            val u = java.net.URI(rawURL)
            !u.host.isNullOrEmpty() && (u.scheme == "http" || u.scheme == "https")
        } catch (_: Exception) {
            false
        }
        if (!validUrl) {
            errorText = "Enter a valid server URL."
            return
        }
        val trimmedEmail = email.trim()
        val enteredPassword = password
        val signingUp = accountMode == AccountMode.SignUp
        if (signingUp && !canCreateAccount(accountName, trimmedEmail, enteredPassword)) {
            errorText = "Enter your name, email, and password."
            return
        }
        if (enteredPassword.isEmpty() && trimmedToken.isEmpty()) {
            errorText = "Enter your email and password."
            return
        }
        // A session belongs to one server, so it never goes to a URL the owner just typed.
        if (enteredPassword.isEmpty() && !signingUp && rawURL != client?.baseURL?.trimmingTrailingSlashes) {
            errorText = "Enter your password to connect to this server."
            return
        }
        val newName = accountName.trim()
        val invite = inviteToken(inviteCode)
        savingConnection = true
        errorText = ""
        scope.launch {
            var created = false
            try {
                val session = when {
                    signingUp -> APIClient(rawURL, "", transport).signUp(newName, trimmedEmail, enteredPassword, invite).also { created = true }
                    enteredPassword.isEmpty() -> trimmedToken
                    else -> APIClient(rawURL, "", transport).signIn(trimmedEmail, enteredPassword)
                }
                val candidate = APIClient(rawURL, session, transport)
                candidate.agents()
                val replaced = client?.takeIf { it.token.isNotEmpty() && it.token != session }
                // The server keeps one device row per installation, so this must land before the new session registers.
                replaced?.let { old -> quietly { onSignOut(old) } }
                credentials.write("server-token", session)
                credentials.write("account-email", trimmedEmail)
                credentials.write("server-url", rawURL)
                token = session
                resetConnectionState()
                client = candidate
                if (route == Route.General) route = null
                showNotice("Connected")
                refresh()
                followActivity()
                replaced?.let { old -> scope.launch { quietly { old.signOut() } } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (created) {
                    // The account and any invite are spent, so a retry must sign in rather than sign up.
                    resetAccountForm()
                    errorText = "Your account was created, but connecting failed: ${friendlyError(e)}"
                } else {
                    errorText = friendlyError(e)
                }
            } finally {
                savingConnection = false
            }
        }
    }

    /** Ending a replaced session is best effort: it expires on its own, and the new one must still connect. */
    private suspend fun quietly(work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Drops everything that belonged to the old session, so nothing carries over to the next one. */
    private fun resetConnectionState() {
        cancelStream()
        generation += 1
        pendingMutations = emptySet()
        mutationRevision += 1
        activeRecords.clear()
        settledRecords.clear()
        resetPendingMessages()
        resetGroupChats()
        resetSecrets()
        resetAccountForm()
        recentChats.clear()
        attachmentBytes = emptyMap()
        credentials.deletePrefixed(RECENT_CHAT_PREFIX)
        forgetDrafts()
        awaitingInitialSnapshot = false
        activityFeed = ActivityFeedState()
        agents = emptyList()
        agentMemories = emptyMap()
        memoryErrors = emptyMap()
        memoryScope = null
        sharedMemories = emptyList()
        sharedSkills = emptyList()
        agentSkills = emptyMap()
        agentSkillErrors = emptyMap()
        selectedSkills = emptySet()
        bulkReport = emptyList()
        checkingSkillUpdates = false
        skillCheckFailures = emptyList()
        skillInstall = null
        installedSkill = null
        skillUpdate = null
        changes = emptyList()
        showingChanges = false
        changesLoading = false
        changesHasMore = false
        changesError = ""
        avatarPage = AvatarPage()
        schedules = emptyList()
        schedulesLoaded = false
        schedulesError = ""
        showingBotDetails = false
        showingPalette = false
        themeSaving = false
        accountTheme = theme
        workingBots = emptySet()
        desktopModels = emptyList()
        executions = emptyList()
        scriptRuns = emptyMap()
        taskRunsLoading = false
        taskRunsError = ""
        taskTab = TaskTab.Tasks
        capabilities = null
        eventSources = emptyList()
        eventSourcesLoaded = false
        eventSourcesError = ""
        deletingEventSources = emptySet()
        eventSourceErrors = emptyMap()
        eventSourceDraft = null
        mcpServers = emptyList()
        providers = emptyList()
        models = emptyList()
        agentDraft = null
        memoryDraft = null
        taskDraft = null
        skillDraft = null
        snapshot = null
        messages = emptyList()
        activeRequests = 0
        loading = false
        errorText = ""
        selectedAgentID = ""
        password = ""
        onSessionReset()
    }

    /** Ends the session here and on the server. The device forgets it even if the server can't be reached. */
    fun signOut() {
        val api = client ?: return
        if (signingOut || savingConnection) return
        signingOut = true
        errorText = ""
        scope.launch {
            try {
                val revoked = try {
                    onSignOut(api)
                    api.signOut()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: APIError) {
                    e.statusCode == 401
                } catch (_: Exception) {
                    false
                }
                credentials.delete("server-token")
                credentials.delete("account-email")
                resetConnectionState()
                token = ""
                email = ""
                client = APIClient(api.baseURL, "", transport)
                if (revoked) showNotice("Signed out")
                else errorText = "Signed out of this device. The server couldn't be reached, so the session may stay active there."
            } finally {
                signingOut = false
            }
        }
    }

    // MARK: Credentials and logins

    private fun secretMark(kind: String, botID: String) = secretRevisions["$kind:$botID"] ?: 0

    private fun touchSecrets(kind: String, botID: String) {
        secretRevisions["$kind:$botID"] = secretMark(kind, botID) + 1
    }

    /** Whether a read started at `mark` may replace local state: nothing changed since, and nothing is on its way. */
    private fun secretSettled(kind: String, botID: String, mark: Int): Boolean {
        val prefix = if (botID.isEmpty()) "$kind:" else "$kind:$botID:"
        return secretMark(kind, botID) == mark && pendingMutations.none { it.startsWith(prefix) }
    }

    /** Typed secrets last only as long as the screen they were typed on. */
    private fun clearSecretDrafts() {
        credentialForm = null
        loginForm = null
    }

    private fun resetSecrets() {
        clearSecretDrafts()
        botCredentials = emptyMap()
        botLogins = emptyMap()
        freshSecretRequests = emptySet()
        passwordManagers = emptyList()
    }

    private fun <D> editSecrets(ops: SecretOps<D>, botID: String, change: (D) -> D) {
        val current = ops.lists()[botID] ?: return
        ops.store(ops.lists() + (botID to change(current)))
    }

    private fun <D> reloadSecrets(ops: SecretOps<D>, botID: String) {
        val api = client ?: return
        val gen = generation
        val mark = secretMark(ops.kind, botID)
        scope.launch {
            attempt { ops.read(api, botID) }.onSuccess { list ->
                if (generation == gen && secretSettled(ops.kind, botID, mark)) ops.store(ops.lists() + (botID to list))
            }
        }
    }

    // Restored requests must be followed again: the loop stops once none waits.
    private fun resumeDecisions() {
        if (route !is Route.AgentChat) return
        decisionsKey = ""
        followDecisions()
    }

    private fun canSubmit(key: String): Boolean {
        if (client == null) {
            errorText = "Connect to your server in Settings."
            return false
        }
        return key !in pendingMutations
    }

    private fun <D, I> submitSecret(
        ops: SecretOps<D>,
        botID: String,
        name: String,
        /** The local change to show now, or null when the outcome must wait for a password manager. */
        provisional: ((D) -> D)?,
        save: suspend (APIClient) -> I,
        saved: (D, I) -> D,
        confirmed: () -> Unit,
        recover: (String) -> Unit,
    ) {
        val before = ops.lists()[botID]
        optimisticMutation(
            key = secretKey(ops.kind, botID, name),
            update = {
                touchSecrets(ops.kind, botID)
                if (provisional != null) editSecrets(ops, botID, provisional)
            },
            rollback = {
                touchSecrets(ops.kind, botID)
                if (provisional != null && before != null) editSecrets(ops, botID) { ops.restored(it, before, name) }
            },
            fail = {
                recover(it)
                resumeDecisions()
            },
            work = { api ->
                val item = save(api)
                return@optimisticMutation {
                    touchSecrets(ops.kind, botID)
                    editSecrets(ops, botID) { saved(it, item) }
                    confirmed()
                    reloadSecrets(ops, botID)
                }
            },
        )
    }

    private fun submitCredential(form: CredentialForm, confirmed: () -> Unit, recover: (String) -> Unit) {
        val fields = credentialFields(form)
        submitSecret(
            ops = credentialOps,
            botID = form.owner,
            name = form.name,
            provisional = if (form.readsPasswordManager) null else { list -> list.withCredential(provisionalCredential(form)) },
            save = { api -> api.saveCredential(form.owner, form.name, fields) },
            saved = { list, item -> list.withCredential(item) },
            confirmed = confirmed,
            recover = recover,
        )
    }

    fun openCredentialForm(botID: String, request: CredentialRequest) {
        credentialForm = credentialForm(botID, request)
        loadPasswordManagers()
    }

    fun closeCredentialForm() {
        credentialForm = null
    }

    /** A plain value applies at once; a new password manager reference stays pending until Crumble has read it. */
    fun saveCredential() {
        val form = credentialForm ?: return
        if (!form.ready || !canSubmit(secretKey("credential", form.owner, form.name))) return
        val waits = form.readsPasswordManager
        val from = route
        credentialForm = if (waits) form.copy(saving = true, error = "") else null
        val open = { credentialForm?.let { it.saving && it.owner == form.owner && it.name == form.name } == true }
        submitCredential(
            form,
            confirmed = { if (open()) credentialForm = null },
            recover = { message ->
                val reopen = !waits && credentialForm == null && loginForm == null && route == from
                if (open() || reopen) credentialForm = form.copy(error = message) else errorText = message
            },
        )
    }

    fun openLoginForm(botID: String, request: LoginRequest) {
        loginForm = loginForm(botID, request)
        loadPasswordManagers()
    }

    fun closeLoginForm() {
        loginForm = null
    }

    fun saveLogin() {
        val form = loginForm ?: return
        if (!form.ready || !canSubmit(secretKey("login", form.owner, form.name))) return
        val waits = form.readsPasswordManager
        val from = route
        val fields = loginFields(form)
        loginForm = if (waits) form.copy(saving = true, error = "") else null
        val open = { loginForm?.let { it.saving && it.owner == form.owner && it.name == form.name } == true }
        submitSecret(
            ops = loginOps,
            botID = form.owner,
            name = form.name,
            provisional = if (waits) null else { list -> list.withLogin(provisionalLogin(form)) },
            save = { api -> api.saveLogin(form.owner, form.name, fields) },
            saved = { list, item -> list.withLogin(item) },
            confirmed = { if (open()) loginForm = null },
            recover = { message ->
                val reopen = !waits && credentialForm == null && loginForm == null && route == from
                if (open() || reopen) loginForm = form.copy(error = message) else errorText = message
            },
        )
    }

    private fun <D> declineSecretRequest(ops: SecretOps<D>, botID: String, name: String) {
        val key = secretKey(ops.kind, botID, name)
        val before = ops.lists()[botID]
        optimisticMutation(
            key = key,
            update = {
                touchSecrets(ops.kind, botID)
                decisionErrors = decisionErrors - key
                editSecrets(ops, botID) { ops.withoutRequest(it, name) }
            },
            rollback = {
                touchSecrets(ops.kind, botID)
                if (before != null) editSecrets(ops, botID) { ops.restored(it, before, name) }
            },
            fail = { message ->
                if (route == Route.AgentChat(botID)) decisionErrors = decisionErrors + (key to message) else errorText = message
                resumeDecisions()
            },
            work = { api ->
                ops.decline(api, botID, name)
                return@optimisticMutation {
                    touchSecrets(ops.kind, botID)
                    reloadSecrets(ops, botID)
                }
            },
        )
    }

    // Never a delete: a saved item and a request for more under its name can coexist.
    fun declineCredentialRequest(botID: String, name: String) = declineSecretRequest(credentialOps, botID, name)

    fun declineLoginRequest(botID: String, name: String) = declineSecretRequest(loginOps, botID, name)

    // MARK: Password managers

    val connectedManagers: List<PasswordManager> get() = passwordManagers.filter { it.connected }

    fun loadPasswordManagers() {
        val api = client ?: return
        val gen = generation
        scope.launch {
            attempt { api.passwordManagers() }.onSuccess { if (generation == gen) passwordManagers = it }
        }
    }

    private fun loadAgentsForPanel() {
        val revision = mutationRevision
        request { api ->
            val loaded = api.agents()
            return@request {
                if (settledSince(revision, BOT_RECORD)) loadAgentsForPanel() else applyBots(loaded)
            }
        }
    }

    // MARK: Account

    var accountMode by mutableStateOf(AccountMode.SignIn)
    var accountName by mutableStateOf("")
    var inviteCode by mutableStateOf("")

    fun switchAccountMode() {
        accountMode = if (accountMode == AccountMode.SignIn) AccountMode.SignUp else AccountMode.SignIn
        errorText = ""
    }

    private fun resetAccountForm() {
        accountMode = AccountMode.SignIn
        accountName = ""
        inviteCode = ""
    }

    private fun loadAccount() {
        if (client == null) return
        val changes = themeChanges
        confirmed(
            start = {},
            work = { api -> api.accountSession() },
            finish = { result ->
                result.onSuccess {
                    // The account's theme wins unless the owner chose one since this read began.
                    ThemeChoice.of(it.theme)?.takeIf { changes == themeChanges && !themeSaving }?.let { saved ->
                        accountTheme = saved
                        if (saved != theme) adoptTheme(saved)
                    }
                }
            },
        )
    }
}
