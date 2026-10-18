package dev.crumble.android.model

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.APIError
import dev.crumble.android.network.friendlyError
import dev.crumble.android.network.oauthResult
import dev.crumble.android.storage.CredentialStore
import dev.crumble.android.storage.InMemoryCredentialStore
import dev.crumble.android.ui.BotState
import dev.crumble.android.ui.agentAvatarSeed
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

sealed class Route {
    data class AgentChat(val id: String) : Route()
    data object Memory : Route()
    data object Skills : Route()
    data object Tasks : Route()
    data object Activity : Route()
    data object Integrations : Route()

    val title: String
        get() = when (this) {
            is AgentChat -> "Chat"
            Memory -> "Memory"
            Skills -> "Skills"
            Tasks -> "Tasks"
            Activity -> "Activity"
            Integrations -> "Integrations"
        }
}

data class AgentDraft(
    val id: UUID = UUID.randomUUID(),
    val isNew: Boolean,
    val agentID: String,
    var name: String = "",
    var description: String = "",
    var folder: String = "Personal",
    var enabled: Boolean = true,
    var provider: String = "melious",
    var model: String = "",
    var instructions: String = "You are a thoughtful, capable personal assistant.",
    var avatar: String = "bot:${newRequestID()}",
    var avatarChoices: List<String> = emptyList(),
    var thinking: String = "off",
    var temperature: String = "",
    var maxOutput: String = "",
    var toolsMemory: Boolean = true,
    var toolsDelegate: Boolean = false,
    var toolsSchedule: Boolean = false,
    var toolsCodemode: Boolean = false,
    var sharedMemory: Boolean = false,
    var delegates: Set<String> = emptySet(),
    var mcpServers: Set<String> = emptySet(),
    var skills: List<Skill> = emptyList(),
    var sharedSkills: Set<String> = emptySet(),
) {
    /** Offers new bots to pick from, keeping the current one first. */
    fun withShuffledAvatars(): AgentDraft {
        val choices = mutableListOf(agentAvatarSeed(Agent(id = agentID, avatar = avatar)))
        while (choices.size < 7) {
            choices.add(newRequestID())
        }
        return copy(avatarChoices = choices)
    }
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
)

data class TaskDraft(
    val id: UUID = UUID.randomUUID(),
    val scheduleID: String,
    var agentID: String,
    var name: String = "",
    var prompt: String = "",
    var repeats: Boolean = false,
    var cron: String = "0 9 * * *",
    var runDate: Instant = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MINUTES),
    var timezone: String = ZoneId.systemDefault().id,
    var enabled: Boolean = true,
)

data class MCPDraft(
    val id: UUID = UUID.randomUUID(),
    val serverID: String,
    /** The bot a new server belongs to, or null for a shared one; fixed once the server exists. */
    var agentID: String? = null,
    var name: String = "",
    var url: String = "",
    var token: String = "",
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
) {
    var serverURL by mutableStateOf("http://127.0.0.1:7337")
    var token by mutableStateOf("")
    var client: APIClient? by mutableStateOf(client)
        private set
    private var generation = 0

    var agents by mutableStateOf<List<Agent>>(emptyList())
    var route by mutableStateOf<Route?>(null)
    var selectedAgentID by mutableStateOf("")
        private set
    private var selectionVersion = 0
    private var awaitingInitialSnapshot = false
    private val recentChats = mutableMapOf<String, ChatSnapshot>()

    private fun cacheKey(id: String): String {
        val api = client
        val identity = "${api?.baseURL}\n${api?.token}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return "recent-chat:$digest:$id"
    }

    private fun cachedChat(id: String): ChatSnapshot? {
        val key = cacheKey(id)
        return recentChats[key] ?: runCatching {
            credentials.read(key)?.let { CrumbleJson.decodeFromString(ChatSnapshot.serializer(), it) }
        }.getOrNull()?.also { recentChats[key] = it }
    }

    /** Keep only recent confirmed rows; uploads and transient run state are never persisted here. */
    private fun cacheRecentChat() {
        if (selectedAgentID.isEmpty()) return
        val confirmed = messages.filter { it.deliveryState == MessageDeliveryState.confirmed }
        val tail = confirmed.takeLast(5)
        // Keep an entry intact so the history cursor cannot skip another row from that entry.
        val boundary = tail.first().entryId
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

    var draft by mutableStateOf("")
    var attachments by mutableStateOf<List<PendingAttachment>>(emptyList())
    var replyTo by mutableStateOf<Message?>(null)
    var sending by mutableStateOf(false)
    private var draftRequestID = ""
    private var failedDraft = ""
    private var failedReplyTo = ""
    private var failedAttachments = emptyList<UUID>()

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

    /** Sent image bytes, keyed by "entryId/index"; they never change once sent. */
    var attachmentBytes by mutableStateOf<Map<String, ByteArray>>(emptyMap())
        private set

    private var activeRequests = 0
    var pendingMutations by mutableStateOf<Set<String>>(emptySet())
        private set
    var mutationRevision = 0
        private set
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
    var sharedSkills by mutableStateOf<List<SharedSkill>>(emptyList())
    var schedules by mutableStateOf<List<Schedule>>(emptyList())
    var executions by mutableStateOf<List<Execution>>(emptyList())
    var activities by mutableStateOf<List<ActivityRecord>>(emptyList())
    var mcpServers by mutableStateOf<List<MCPServer>>(emptyList())
    var providers by mutableStateOf<List<ProviderStatus>>(emptyList())
    var models by mutableStateOf<List<ModelSummary>>(emptyList())

    var agentDraft by mutableStateOf<AgentDraft?>(null)
    var memoryDraft by mutableStateOf<MemoryDraft?>(null)
    var taskDraft by mutableStateOf<TaskDraft?>(null)
    var mcpDraft by mutableStateOf<MCPDraft?>(null)
    var skillDraft by mutableStateOf<SkillDraft?>(null)
    var showingSettings by mutableStateOf(false)

    /** The MCP server whose sign-in flow is currently active. */
    var signingIn by mutableStateOf<String?>(null)
    var confirmingDelete by mutableStateOf(false)

    var provider by mutableStateOf("melious")
    var providerKey by mutableStateOf("")

    var appActive: Boolean = true

    init {
        if (loadCredentials) {
            val savedUrl = credentials.read("server-url")
            if (!savedUrl.isNullOrEmpty()) serverURL = savedUrl
            val savedToken = credentials.read("server-token")
            if (savedToken != null) token = savedToken
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

    val selectedAgent: Agent?
        get() = agents.firstOrNull { it.id == selectedAgentID }

    fun botState(agent: Agent): BotState {
        if (!agent.enabled) return BotState.sleeping
        if (agent.id == selectedAgentID && snapshot?.isRunning == true) return BotState.working
        return BotState.idle
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
        work: suspend (APIClient) -> (() -> Unit)?,
    ) {
        val currentClient = client
        if (currentClient == null) {
            errorText = "Connect to your server in Settings."
            return
        }
        if (pendingMutations.contains(key)) return
        pendingMutations = pendingMutations + key
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
                pendingMutations = pendingMutations - key
                mutationRevision += 1
                finishRequest()
                confirm?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != gen) return@launch
                pendingMutations = pendingMutations - key
                mutationRevision += 1
                finishRequest()
                rollback()
                errorText = friendlyError(e)
            }
        }
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
        val revision = mutationRevision
        request { api ->
            val loadedAgents = api.agents()
            return@request {
                if (mutationRevision == revision && pendingMutations.isEmpty()) {
                    agents = loadedAgents
                    var chosen = selectedAgentID
                    if (loadedAgents.none { it.id == chosen }) {
                        chosen = loadedAgents.firstOrNull()?.id ?: ""
                    }
                    if (chosen.isEmpty()) {
                        cancelStream()
                        selectedAgentID = ""
                        snapshot = null
                        messages = emptyList()
                    } else {
                        selectAgent(chosen)
                    }
                }
            }
        }
    }

    fun navigate(to: Route?) {
        if (to == null) return
        when (to) {
            is Route.AgentChat -> {
                if (to.id != selectedAgentID || route != to) {
                    selectAgent(to.id)
                }
            }
            else -> openPanel(to)
        }
    }

    /** Opens the bot's one continuous chat and follows its event stream. */
    fun selectAgent(id: String) {
        cancelStream()
        selectionVersion += 1
        val version = selectionVersion
        selectedAgentID = id
        route = Route.AgentChat(id)
        val cached = cachedChat(id)
        snapshot = cached?.copy(messages = null)
        messages = cached?.messages.orEmpty() + pendingMessages[id].orEmpty()
        hasMore = cached?.hasMore ?: false
        oldestEntryID = cached?.oldestEntryId ?: ""
        awaitingInitialSnapshot = true
        loadingOlder = false
        replyTo = null
        draft = ""
        attachments = emptyList()
        failedDraft = ""
        failedReplyTo = ""
        failedAttachments = emptyList()
        sending = false
        draftRequestID = ""
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
    }

    fun cancelStream() {
        streamJob?.cancel()
        streamJob = null
        streamStatus = ""
    }

    private suspend fun streamChat(api: APIClient, id: String, version: Int) {
        var backoffMs = 500L
        while (scope.isActive) {
            try {
                api.chatEvents(id, limit = 5) { payload ->
                    val snap = try {
                        CrumbleJson.decodeFromString(ChatSnapshot.serializer(), payload)
                    } catch (_: Exception) {
                        null
                    } ?: return@chatEvents
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
                    api.activityEvents { payload ->
                        val event = try {
                            CrumbleJson.decodeFromString(ActivityEvent.serializer(), payload)
                        } catch (_: Exception) {
                            null
                        } ?: return@activityEvents
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
        val note = notification(forEvent = event, inContext = context) ?: return
        notifier.post(event, title = note.title, body = note.body)
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
        val currentClient = client ?: return
        if (snapshot == null) return
        val content = draft.trim()
        val files = attachments
        if (content.isEmpty() && files.isEmpty()) return
        val id = selectedAgentID
        val reply = replyTo?.entryId ?: ""
        draftRequestID = newRequestID()
        val requestID = draftRequestID
        failedDraft = content
        failedReplyTo = reply
        failedAttachments = files.map { it.id }
        sending = true
        draft = ""
        attachments = emptyList()
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
        pendingMessages.getOrPut(id) { mutableListOf() }.add(optimistic)
        pendingSends.getOrPut(id) { mutableMapOf() }[requestID] = PendingSend(
            content = content,
            replyTo = reply,
            attachments = files,
        )
        val previewText = content.nilIfEmpty ?: files.firstOrNull()?.let { "Attachment: ${it.name}" } ?: ""
        pendingPreviews[id] = PendingPreview(requestID = requestID, text = previewText)
        val agentIndex = agents.indexOfFirst { it.id == id }
        if (agentIndex >= 0) {
            val updated = agents.toMutableList()
            updated[agentIndex] = updated[agentIndex].copy(lastMessage = previewText)
            agents = updated
        }
        if (selectedAgentID == id) {
            messages = messages + optimistic
        }
        replyTo = null
        val gen = generation
        val version = selectionVersion
        scope.launch {
            try {
                currentClient.sendChatMessage(
                    agentID = id,
                    content = content,
                    requestID = requestID,
                    replyTo = reply,
                    attachments = files.map { it.outgoing },
                )
                if (generation != gen) return@launch
                if (pendingPreviews[id]?.requestID == requestID) {
                    pendingPreviews.remove(id)
                }
                if (selectionVersion == version && selectedAgentID == id) {
                    sending = false
                    draftRequestID = ""
                    failedDraft = ""
                    failedReplyTo = ""
                    failedAttachments = emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != gen) return@launch
                if (selectionVersion == version && selectedAgentID == id) {
                    sending = false
                }
                val list = pendingMessages[id]
                if (list == null || list.none { it.requestId == requestID }) return@launch
                if (selectionVersion == version && selectedAgentID == id) {
                    errorText = "Message was not confirmed: ${friendlyError(e)}"
                }
                val idx = list.indexOfFirst { it.requestId == requestID }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(deliveryState = MessageDeliveryState.failed)
                    if (pendingPreviews[id]?.requestID == requestID) {
                        pendingPreviews.remove(id)
                    }
                    if (selectionVersion == version && selectedAgentID == id) {
                        messages = messages.map { msg ->
                            if (msg.requestId == requestID) msg.copy(deliveryState = MessageDeliveryState.failed) else msg
                        }
                    }
                }
                if (selectionVersion == version && selectedAgentID == id) {
                    if (draft.isEmpty() && attachments.isEmpty()) {
                        draft = content
                        attachments = files
                    }
                }
            }
        }
    }

    /** Re-sends a failed optimistic row with its original idempotency key and attachments. */
    fun retryMessage(message: Message) {
        val currentClient = client ?: return
        val requestID = message.requestId ?: return
        if (message.deliveryState != MessageDeliveryState.failed) return
        val record = pendingSends[selectedAgentID]?.get(requestID) ?: return
        val list = pendingMessages[selectedAgentID] ?: return
        val idx = list.indexOfFirst { it.requestId == requestID }
        if (idx < 0) return

        list[idx] = list[idx].copy(deliveryState = MessageDeliveryState.sending)
        messages = messages.map { msg ->
            if (msg.requestId == requestID) msg.copy(deliveryState = MessageDeliveryState.sending) else msg
        }
        if (draft == record.content && attachments.map { it.id } == record.attachments.map { it.id }) {
            draft = ""
            attachments = emptyList()
        }
        sending = true
        val id = selectedAgentID
        val gen = generation
        val version = selectionVersion
        scope.launch {
            try {
                currentClient.sendChatMessage(
                    agentID = id,
                    content = record.content,
                    requestID = requestID,
                    replyTo = record.replyTo,
                    attachments = record.attachments.map { it.outgoing },
                )
                if (generation != gen) return@launch
                if (pendingPreviews[id]?.requestID == requestID) {
                    pendingPreviews.remove(id)
                }
                if (selectionVersion == version && selectedAgentID == id) {
                    sending = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != gen) return@launch
                if (selectionVersion == version && selectedAgentID == id) {
                    sending = false
                }
                val currentList = pendingMessages[id]
                if (currentList == null || currentList.none { it.requestId == requestID }) return@launch
                if (selectionVersion == version && selectedAgentID == id) {
                    errorText = "Message was not confirmed: ${friendlyError(e)}"
                }
                val failIdx = currentList.indexOfFirst { it.requestId == requestID }
                if (failIdx >= 0) {
                    currentList[failIdx] = currentList[failIdx].copy(deliveryState = MessageDeliveryState.failed)
                    if (pendingPreviews[id]?.requestID == requestID) {
                        pendingPreviews.remove(id)
                    }
                    if (selectionVersion == version && selectedAgentID == id) {
                        messages = messages.map { msg ->
                            if (msg.requestId == requestID) msg.copy(deliveryState = MessageDeliveryState.failed) else msg
                        }
                    }
                }
            }
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
        try {
            val bytes = currentClient.attachment(agent, entryID = entryID, index = index)
            if (bytes.isNotEmpty()) {
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
        draftRequestID = ""
        failedDraft = ""
        failedReplyTo = ""
        failedAttachments = emptyList()
    }

    fun abortChat() {
        val id = selectedAgentID
        optimisticMutation(
            key = "abort:$id",
            update = { showNotice("Stopping…") },
            rollback = {},
            work = { api ->
                api.abortChat(id)
                null
            },
        )
    }

    fun compactChat() {
        val id = selectedAgentID
        optimisticMutation(
            key = "compact:$id",
            update = { showNotice("Summarizing older context…") },
            rollback = {},
            work = { api ->
                api.compactChat(id)
                null
            },
        )
    }

    // MARK: Agent profile

    fun createAgent() {
        val draft = AgentDraft(isNew = true, agentID = "").withShuffledAvatars()
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
            avatar = "bot:${agentAvatarSeed(agent)}",
            folder = agent.folder ?: "",
            thinking = agent.inference?.thinkingLevel ?: "off",
            temperature = agent.inference?.temperature?.toString() ?: "",
            maxOutput = agent.inference?.maxOutputTokens?.toString() ?: "",
            toolsMemory = tools.contains("memory"),
            toolsDelegate = tools.contains("delegate"),
            toolsSchedule = tools.contains("schedule"),
            toolsCodemode = tools.contains("codemode"),
            skills = agent.skills ?: emptyList(),
            sharedSkills = (agent.sharedSkills ?: emptyList()).toSet(),
            sharedMemory = agent.sharedMemory ?: false,
            delegates = (agent.delegates ?: emptyList()).toSet(),
            mcpServers = (agent.mcpServers ?: emptyList()).toSet(),
        ).withShuffledAvatars()
        agentDraft = draft
        loadEditorData(draft.provider)
    }

    private fun loadEditorData(provider: String) {
        models = emptyList()
        val revision = mutationRevision
        request { api ->
            coroutineScope {
                val serversDeferred = async { api.mcpServers() }
                val skillsDeferred = async { api.sharedSkills() }
                val loadedServers = serversDeferred.await()
                val loadedSkills = skillsDeferred.await()
                return@coroutineScope {
                    if (mutationRevision == revision && pendingMutations.isEmpty()) {
                        mcpServers = loadedServers
                        sharedSkills = loadedSkills
                    }
                }
            }
        }
        loadProviders()
        loadModels(provider)
    }

    fun loadProviders() {
        request { api ->
            val loaded = api.providers()
            return@request { providers = loaded }
        }
    }

    fun loadModels(providerName: String) {
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

    val providerChoices: List<String>
        get() {
            val ordered = providers.filter { it.configured }.map { it.provider } +
                providers.filter { !it.configured }.map { it.provider }
            val current = agentDraft?.provider ?: ""
            return if (ordered.contains(current) || current.isEmpty()) ordered else listOf(current) + ordered
        }

    val modelHint: String
        get() {
            val p = providers.firstOrNull { it.provider == agentDraft?.provider }
            if (p != null && !p.configured) {
                return "Add an API key for this provider in Settings to load its models."
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
        val tools = mutableListOf<String>()
        if (draft.toolsMemory) tools.add("memory")
        if (draft.toolsDelegate) tools.add("delegate")
        if (draft.toolsSchedule) tools.add("schedule")
        if (draft.toolsCodemode) tools.add("codemode")
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
            thinkingLevel = draft.thinking.ifEmpty { "off" },
            temperature = temperatureVal,
            maxOutputTokens = maxOutputVal,
        )
        val delegates = draft.delegates.sorted()
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
            provider = draft.provider.nilIfEmpty,
            model = draft.model.nilIfEmpty,
            folder = draft.folder.nilIfEmpty,
            tools = tools,
            delegates = delegates,
            skills = draft.skills,
            sharedSkills = shared,
            sharedMemory = draft.sharedMemory,
            mcpServers = servers,
            inference = inference,
        )
        val isNew = draft.isNew
        val id = if (isNew) "pending:${newRequestID()}" else draft.agentID
        val old = agents.firstOrNull { it.id == id }
        val provisional = input.copy(id = id, lastMessage = old?.lastMessage)
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
                    agents.toMutableList().also { it[idx] = old }
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
                        "model" to JsonPrimitive(draft.model),
                        "avatar" to JsonPrimitive(draft.avatar),
                        "folder" to JsonPrimitive(draft.folder),
                        "enabled" to JsonPrimitive(draft.enabled),
                        "tools" to JsonArray(tools.map { JsonPrimitive(it) }),
                        "skills" to CrumbleJson.encodeToJsonElement(ListSerializer(Skill.serializer()), draft.skills),
                        "sharedSkills" to JsonArray(shared.map { JsonPrimitive(it) }),
                        "sharedMemory" to JsonPrimitive(draft.sharedMemory),
                        "mcpServers" to JsonArray(servers.map { JsonPrimitive(it) }),
                        "delegates" to JsonArray(delegates.map { JsonPrimitive(it) }),
                        "inference" to CrumbleJson.encodeToJsonElement(Inference.serializer(), inference),
                    )
                    api.updateAgent(id, patch = patch)
                }
                return@optimisticMutation {
                    val idx = agents.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        val lastMsg = agents[idx].lastMessage
                        val updated = agents.toMutableList()
                        updated[idx] = saved.copy(lastMessage = saved.lastMessage ?: lastMsg)
                        agents = updated
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
                        agents = agents.toMutableList().also { it[idx] = created }
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
            },
            work = { api ->
                api.deleteAgent(id)
                null
            },
        )
    }

    // MARK: Workspace panels

    fun openPanel(panel: Route) {
        route = panel
        memoryDraft = null
        taskDraft = null
        mcpDraft = null
        skillDraft = null
        when (panel) {
            Route.Memory -> {
                memoryLoadVersion += 1
                val version = memoryLoadVersion
                val mutationVersion = mutationRevision
                request { api ->
                    coroutineScope {
                        val bots = api.agents()
                        val sharedDeferred = async { api.memories(null) }
                        val personalPairs = bots.map { bot ->
                            async { bot.id to api.memories(bot.id) }
                        }.awaitAll()
                        val personal = personalPairs.toMap()
                        val sharedItems = sharedDeferred.await()
                        return@coroutineScope {
                            if (memoryLoadVersion == version && mutationRevision == mutationVersion && pendingMutations.isEmpty()) {
                                agents = bots
                                sharedMemories = sharedItems
                                agentMemories = personal
                            }
                        }
                    }
                }
            }
            Route.Skills -> {
                val mutationVersion = mutationRevision
                request { api ->
                    coroutineScope {
                        val botsDeferred = async { api.agents() }
                        val skillsDeferred = async { api.sharedSkills() }
                        val loadedBots = botsDeferred.await()
                        val loadedSkills = skillsDeferred.await()
                        return@coroutineScope {
                            if (mutationRevision == mutationVersion && pendingMutations.isEmpty()) {
                                agents = loadedBots
                                sharedSkills = loadedSkills
                            }
                        }
                    }
                }
            }
            Route.Tasks -> {
                val mutationVersion = mutationRevision
                request { api ->
                    val items = api.schedules()
                    return@request {
                        if (mutationRevision == mutationVersion && pendingMutations.isEmpty()) {
                            schedules = items
                        }
                    }
                }
            }
            Route.Activity -> {
                request { api ->
                    val feed = api.activityFeed()
                    return@request { activities = feed.activities }
                }
            }
            Route.Integrations -> {
                val mutationVersion = mutationRevision
                request { api ->
                    coroutineScope {
                        val botsDeferred = async { api.agents() }
                        val serversDeferred = async { api.mcpServers() }
                        val loadedBots = botsDeferred.await()
                        val loadedServers = serversDeferred.await()
                        return@coroutineScope {
                            if (mutationRevision == mutationVersion && pendingMutations.isEmpty()) {
                                agents = loadedBots
                                mcpServers = loadedServers
                            }
                        }
                    }
                }
            }
            is Route.AgentChat -> Unit
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
        val provisional = Memory(id = provisionalID, content = content, createdAt = null)
        val currentList = if (agent == null) sharedMemories else agentMemories[agent].orEmpty()
        val old = currentList.firstOrNull { it.id == form.memoryID }
        memoryDraft = null

        optimisticMutation(
            key = "memory:$provisionalID",
            update = {
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
                if (agent == null) {
                    val items = sharedMemories.filterNot { it.id == provisionalID }.toMutableList()
                    if (old != null) items.add(old)
                    sharedMemories = items
                } else {
                    val items = agentMemories[agent].orEmpty().filterNot { it.id == provisionalID }.toMutableList()
                    if (old != null) items.add(old)
                    agentMemories = agentMemories + (agent to items)
                }
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
                        val idx = sharedMemories.indexOfFirst { it.id == provisionalID }
                        if (idx >= 0) {
                            sharedMemories = sharedMemories.toMutableList().also { it[idx] = saved }
                        }
                    } else {
                        val items = agentMemories[agent]?.toMutableList()
                        val idx = items?.indexOfFirst { it.id == provisionalID } ?: -1
                        if (items != null && idx >= 0) {
                            items[idx] = saved
                            agentMemories = agentMemories + (agent to items)
                        }
                    }
                }
            },
        )
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
                repeats = !schedule.cron.isNullOrEmpty(),
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
        val cron = if (form.repeats) form.cron.trim() else ""
        val runAt = if (form.repeats) "" else form.runDate.toIso8601SecondString()
        if (form.agentID.isEmpty()) {
            errorText = "Choose which bot runs this task."
            return
        }
        val validationError = validateTask(name = name, prompt = prompt, cron = cron, runAt = runAt, timezone = timezone)
        if (validationError != null) {
            errorText = validationError
            return
        }
        val payload = LinkedHashMap<String, JsonElement>().apply {
            put("agentId", JsonPrimitive(form.agentID))
            put("name", JsonPrimitive(name))
            put("prompt", JsonPrimitive(prompt))
            put("timezone", JsonPrimitive(timezone))
            put("enabled", JsonPrimitive(form.enabled))
        }
        val isNew = form.scheduleID.isEmpty()
        val provisionalID = if (isNew) "pending-schedule-${newRequestID()}" else form.scheduleID
        val provisional = Schedule(
            id = provisionalID,
            agentId = form.agentID,
            name = name,
            cron = if (form.repeats) cron else null,
            prompt = prompt,
            timezone = timezone,
            runAt = if (form.repeats) null else runAt,
            enabled = form.enabled,
            createdByAgentId = null,
        )
        val old = schedules.firstOrNull { it.id == form.scheduleID }
        taskDraft = null

        optimisticMutation(
            key = "schedule:$provisionalID",
            update = {
                val idx = schedules.indexOfFirst { it.id == provisionalID || it.id == form.scheduleID }
                schedules = if (idx >= 0) {
                    schedules.toMutableList().also { it[idx] = provisional }
                } else {
                    schedules + provisional
                }
            },
            rollback = {
                val list = schedules.filterNot { it.id == provisionalID }.toMutableList()
                if (old != null) list.add(old)
                schedules = list
                if (route == Route.Tasks && taskDraft == null) {
                    taskDraft = form
                }
            },
            work = { api ->
                val saved = if (isNew) {
                    if (form.repeats) {
                        payload["cron"] = JsonPrimitive(cron)
                    } else {
                        payload["runAt"] = JsonPrimitive(runAt)
                    }
                    api.createSchedule(payload)
                } else {
                    payload["cron"] = if (cron.isEmpty()) JsonNull else JsonPrimitive(cron)
                    payload["runAt"] = if (form.repeats) JsonNull else JsonPrimitive(runAt)
                    api.updateSchedule(form.scheduleID, patch = payload)
                }
                return@optimisticMutation {
                    val idx = schedules.indexOfFirst { it.id == provisionalID }
                    if (idx >= 0) {
                        schedules = schedules.toMutableList().also { it[idx] = saved }
                    }
                }
            },
        )
    }

    fun runSchedule(id: String) {
        val routeAtStart = route
        showNotice("Starting task…")
        optimisticMutation(
            key = "schedule-run:$id",
            update = {},
            rollback = { showNotice("Task could not be started") },
            work = { api ->
                val execution = api.runSchedule(id)
                return@optimisticMutation {
                    showNotice("Task started")
                    val agent = execution.agentId
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

    /** Opens a server to edit, or a new one owned by `agentID` (null for shared). */
    fun openMCPForm(id: String, agentID: String? = null) {
        var form = MCPDraft(serverID = id, agentID = agentID)
        val server = mcpServers.firstOrNull { it.id == id }
        if (server != null) {
            form = form.copy(
                agentID = server.agentId,
                name = server.name,
                url = server.url,
                enabled = server.enabled,
            )
        }
        mcpDraft = form
    }

    fun saveMCP() {
        val form = mcpDraft ?: return
        val name = form.name.trim()
        val rawURL = form.url.trim()
        val validUrl = try {
            val u = java.net.URI(rawURL)
            !u.host.isNullOrEmpty() && (u.scheme == "http" || u.scheme == "https")
        } catch (_: Exception) {
            false
        }
        if (name.isEmpty() || !validUrl) {
            errorText = "MCP name and an HTTP(S) URL are required."
            return
        }
        val payload = LinkedHashMap<String, JsonElement>().apply {
            put("name", JsonPrimitive(name))
            put("url", JsonPrimitive(rawURL))
            put("enabled", JsonPrimitive(form.enabled))
            if (form.token.isNotEmpty()) put("token", JsonPrimitive(form.token))
        }
        val isNew = form.serverID.isEmpty()
        val provisionalID = if (isNew) "pending-mcp-${newRequestID()}" else form.serverID
        val old = mcpServers.firstOrNull { it.id == form.serverID }
        var provisional = MCPServer(
            id = provisionalID,
            name = name,
            url = rawURL,
            enabled = form.enabled,
            agentId = form.agentID,
            authenticated = false,
            auth = if (form.token.isEmpty()) "none" else "token",
        )
        if (old != null && form.token.isEmpty() && old.url == rawURL) {
            provisional = provisional.copy(authenticated = old.authenticated, auth = old.auth)
        }
        val owner = form.agentID
        mcpDraft = null

        optimisticMutation(
            key = "mcp:$provisionalID",
            update = {
                val idx = mcpServers.indexOfFirst { it.id == provisionalID || it.id == form.serverID }
                mcpServers = if (idx >= 0) {
                    mcpServers.toMutableList().also { it[idx] = provisional }
                } else {
                    mcpServers + provisional
                }
                if (isNew && owner != null) {
                    val agentIdx = agents.indexOfFirst { it.id == owner }
                    if (agentIdx >= 0) {
                        val memberships = (agents[agentIdx].mcpServers ?: emptyList()).toMutableList()
                        if (!memberships.contains(provisionalID)) memberships.add(provisionalID)
                        agents = agents.toMutableList().also {
                            it[agentIdx] = it[agentIdx].copy(mcpServers = memberships)
                        }
                    }
                }
            },
            rollback = {
                val list = mcpServers.filterNot { it.id == provisionalID }.toMutableList()
                if (old != null) list.add(old)
                mcpServers = list
                if (isNew && owner != null) {
                    val agentIdx = agents.indexOfFirst { it.id == owner }
                    if (agentIdx >= 0) {
                        val memberships = agents[agentIdx].mcpServers?.filterNot { it == provisionalID }
                        agents = agents.toMutableList().also {
                            it[agentIdx] = it[agentIdx].copy(mcpServers = memberships)
                        }
                    }
                }
                if (route == Route.Integrations && mcpDraft == null) {
                    mcpDraft = form
                }
            },
            work = { api ->
                if (isNew) {
                    if (owner != null) payload["agentId"] = JsonPrimitive(owner)
                    val saved = api.createMCPServer(payload)
                    return@optimisticMutation {
                        val idx = mcpServers.indexOfFirst { it.id == provisionalID }
                        if (idx >= 0) {
                            mcpServers = mcpServers.toMutableList().also { it[idx] = saved }
                        }
                        if (owner != null) {
                            val agentIdx = agents.indexOfFirst { it.id == owner }
                            if (agentIdx >= 0) {
                                val updatedMemberships = (agents[agentIdx].mcpServers ?: emptyList()).map {
                                    if (it == provisionalID) saved.id else it
                                }
                                agents = agents.toMutableList().also {
                                    it[agentIdx] = it[agentIdx].copy(mcpServers = updatedMemberships)
                                }
                            }
                        }
                    }
                }
                val saved = api.updateMCPServer(form.serverID, patch = payload)
                return@optimisticMutation {
                    val idx = mcpServers.indexOfFirst { it.id == provisionalID }
                    if (idx >= 0) {
                        mcpServers = mcpServers.toMutableList().also { it[idx] = saved }
                    }
                }
            },
        )
    }

    companion object {
        const val oauthCallbackScheme = "dev.crumble.android"
        const val oauthRedirectURI = "$oauthCallbackScheme:/oauth/callback"
    }

    /** Starts an OAuth sign-in flow and opens the authorization URL in the system browser. */
    fun beginMCPSignIn(id: String, openBrowser: (String) -> Unit) {
        val api = client ?: return
        errorText = ""
        signingIn = id
        val genAtStart = generation
        scope.launch {
            try {
                val start = api.startMCPSignIn(id, redirectURI = oauthRedirectURI)
                if (generation != genAtStart) return@launch
                val address = start.authorizationUrl
                if (!address.isNullOrEmpty()) {
                    openBrowser(address)
                } else if (start.authorized == true) {
                    val idx = mcpServers.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        mcpServers = mcpServers.toMutableList().also {
                            it[idx] = it[idx].copy(authenticated = true)
                        }
                    }
                    signingIn = null
                    showNotice("Signed in")
                } else {
                    signingIn = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != genAtStart) return@launch
                signingIn = null
                errorText = friendlyError(e)
            }
        }
    }

    /** Completes an in-flight OAuth sign-in when the system redirects back to `dev.crumble.android:/oauth/callback`. */
    fun handleOAuthCallback(callbackUri: String) {
        val id = signingIn ?: return
        val api = client ?: return
        val genAtStart = generation
        scope.launch {
            try {
                val result = oauthResult(callbackUri)
                if (generation != genAtStart) return@launch
                val saved = api.finishMCPSignIn(id, code = result.code, state = result.state, iss = result.iss)
                if (generation != genAtStart) return@launch
                val idx = mcpServers.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    mcpServers = mcpServers.toMutableList().also { it[idx] = saved }
                }
                showNotice("Signed in")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != genAtStart) return@launch
                errorText = friendlyError(e)
            } finally {
                if (generation == genAtStart && signingIn == id) {
                    signingIn = null
                }
            }
        }
    }

    fun signOutOfMCP(id: String) {
        val index = mcpServers.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = mcpServers[index]
        val signedOut = old.copy(authenticated = false, auth = "none")
        optimisticMutation(
            key = "mcp:$id",
            update = {
                mcpServers = mcpServers.toMutableList().also { it[index] = signedOut }
            },
            rollback = {
                val cur = mcpServers.indexOfFirst { it.id == id }
                if (cur >= 0) {
                    mcpServers = mcpServers.toMutableList().also { it[cur] = old }
                }
            },
            work = { api ->
                val saved = api.signOutOfMCP(id)
                return@optimisticMutation {
                    val cur = mcpServers.indexOfFirst { it.id == id }
                    if (cur >= 0) {
                        mcpServers = mcpServers.toMutableList().also { it[cur] = saved }
                    }
                }
            },
        )
    }

    fun deleteMCP(id: String) {
        val index = mcpServers.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = mcpServers[index]
        val affectedAgents = agents.mapNotNull { agent ->
            if (agent.mcpServers?.contains(id) == true) agent.id to agent.mcpServers else null
        }
        optimisticMutation(
            key = "mcp:$id",
            update = {
                mcpServers = mcpServers.filterNot { it.id == id }
                agents = agents.map { agent ->
                    if (agent.mcpServers?.contains(id) == true) {
                        agent.copy(mcpServers = agent.mcpServers.filterNot { it == id })
                    } else {
                        agent
                    }
                }
            },
            rollback = {
                val list = mcpServers.toMutableList()
                list.add(minOf(index, list.size), old)
                mcpServers = list
                val restoredMap = affectedAgents.toMap()
                agents = agents.map { agent ->
                    val origMemberships = restoredMap[agent.id]
                    if (origMemberships != null) agent.copy(mcpServers = origMemberships) else agent
                }
            },
            work = { api ->
                api.deleteMCPServer(id)
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
            if (skill != null) form = form.copy(name = skill.name, instructions = skill.instructions)
        } else {
            val skill = agents.firstOrNull { it.id == agentID }?.skills.orEmpty().firstOrNull { it.id == id }
            if (skill != null) form = form.copy(name = skill.name, instructions = skill.instructions)
        }
        skillDraft = form
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
        val provisional = SharedSkill(id = provisionalID, name = name, instructions = instructions, source = null, path = null)
        val old = sharedSkills.firstOrNull { it.id == form.skillID }
        skillDraft = null

        optimisticMutation(
            key = "skill:$provisionalID",
            update = {
                val idx = sharedSkills.indexOfFirst { it.id == provisionalID || it.id == form.skillID }
                sharedSkills = if (idx >= 0) {
                    sharedSkills.toMutableList().also { it[idx] = provisional }
                } else {
                    sharedSkills + provisional
                }
            },
            rollback = {
                val list = sharedSkills.filterNot { it.id == provisionalID }.toMutableList()
                if (old != null) list.add(old)
                sharedSkills = list
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
                return@optimisticMutation {
                    val idx = sharedSkills.indexOfFirst { it.id == provisionalID }
                    if (idx >= 0) {
                        sharedSkills = sharedSkills.toMutableList().also { it[idx] = saved }
                    }
                }
            },
        )
    }

    private fun saveAgentSkill(form: SkillDraft, name: String, instructions: String, isNew: Boolean) {
        val agentID = form.agentID ?: return
        val oldSkills = agents.firstOrNull { it.id == agentID }?.skills.orEmpty()
        if (agents.none { it.id == agentID }) return
        val skillID = if (isNew) UUID.randomUUID().toString() else form.skillID
        val updatedSkill = Skill(
            id = skillID,
            name = name,
            instructions = instructions,
        )
        val updatedSkills = if (isNew) oldSkills + updatedSkill else oldSkills.map { if (it.id == form.skillID) updatedSkill else it }
        skillDraft = null
        optimisticMutation(
            key = "agent-skills:$agentID",
            update = { agents = agents.map { if (it.id == agentID) it.copy(skills = updatedSkills) else it } },
            rollback = {
                agents = agents.map { if (it.id == agentID) it.copy(skills = oldSkills) else it }
                if (route == Route.Skills && skillDraft == null) skillDraft = form
            },
            work = { api ->
                val patch = mapOf<String, JsonElement>(
                    "skills" to CrumbleJson.encodeToJsonElement(ListSerializer(Skill.serializer()), updatedSkills),
                )
                val saved = api.updateAgent(agentID, patch)
                return@optimisticMutation {
                    agents = agents.map { current -> if (current.id == agentID) current.copy(skills = saved.skills) else current }
                }
            },
        )
    }

    fun deleteAgentSkill(agentID: String, skillID: String) {
        val oldSkills = agents.firstOrNull { it.id == agentID }?.skills.orEmpty()
        if (agents.none { it.id == agentID }) return
        val updatedSkills = oldSkills.filterNot { it.id == skillID }
        optimisticMutation(
            key = "agent-skills:$agentID",
            update = { agents = agents.map { if (it.id == agentID) it.copy(skills = updatedSkills) else it } },
            rollback = { agents = agents.map { if (it.id == agentID) it.copy(skills = oldSkills) else it } },
            work = { api ->
                val patch = mapOf<String, JsonElement>(
                    "skills" to CrumbleJson.encodeToJsonElement(ListSerializer(Skill.serializer()), updatedSkills),
                )
                val saved = api.updateAgent(agentID, patch)
                return@optimisticMutation {
                    agents = agents.map { current -> if (current.id == agentID) current.copy(skills = saved.skills) else current }
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

    // MARK: Settings

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
        val candidate = APIClient(baseURL = rawURL, token = trimmedToken)
        savingConnection = true
        errorText = ""
        scope.launch {
            try {
                candidate.agents()
                credentials.write("server-token", trimmedToken)
                credentials.write("server-url", rawURL)
                cancelStream()
                generation += 1
                pendingMutations = emptySet()
                mutationRevision += 1
                resetPendingMessages()
                recentChats.clear()
                awaitingInitialSnapshot = false
                agents = emptyList()
                agentMemories = emptyMap()
                sharedMemories = emptyList()
                sharedSkills = emptyList()
                schedules = emptyList()
                executions = emptyList()
                mcpServers = emptyList()
                providers = emptyList()
                models = emptyList()
                signingIn = null
                agentDraft = null
                memoryDraft = null
                taskDraft = null
                mcpDraft = null
                skillDraft = null
                snapshot = null
                messages = emptyList()
                client = candidate
                activeRequests = 0
                loading = false
                errorText = ""
                selectedAgentID = ""
                showingSettings = false
                showNotice("Connected")
                refresh()
                followActivity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorText = friendlyError(e)
            } finally {
                savingConnection = false
            }
        }
    }

    fun saveProviderKey() {
        val currentProvider = provider
        val key = providerKey.trim()
        if (key.isEmpty()) {
            errorText = "Enter an API key."
            return
        }
        optimisticMutation(
            key = "provider:$currentProvider",
            update = { providerKey = "" },
            rollback = {
                if (provider == currentProvider && providerKey.isEmpty()) {
                    providerKey = key
                }
            },
            work = { api ->
                api.setProviderKey(currentProvider, apiKey = key)
                return@optimisticMutation {
                    showNotice("Provider key saved")
                    loadProviders()
                }
            },
        )
    }

    fun removeProviderKey() {
        val currentProvider = provider
        optimisticMutation(
            key = "provider:$currentProvider",
            update = {},
            rollback = {},
            work = { api ->
                api.deleteProviderKey(currentProvider)
                return@optimisticMutation {
                    showNotice("Provider key removed")
                    loadProviders()
                }
            },
        )
    }
}
