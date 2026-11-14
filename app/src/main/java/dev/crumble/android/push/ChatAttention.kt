package dev.crumble.android.push

import dev.crumble.android.network.APIClient
import dev.crumble.android.network.APIError
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ChatAttentionChat(val kind: String, val id: String)

class ChatAttention(private val scope: CoroutineScope) {
    var viewId: String = UUID.randomUUID().toString()
        private set
    private var connection: Connection? = null
    private var disconnectedAPI: WeakReference<APIClient>? = null
    private var view: View? = null
    private var active = false
    private var route: ChatAttentionChat? = null
    private var mounted: ChatAttentionChat? = null

    private val effective: ChatAttentionChat?
        get() = route?.takeIf { active && it == mounted }

    fun update(api: APIClient?, active: Boolean, chat: ChatAttentionChat?) {
        val signedIn = api?.takeIf { it.token.isNotBlank() && it !== disconnectedAPI?.get() }
        if (connection?.api !== signedIn) {
            retire()
            connection?.stop()
            viewId = UUID.randomUUID().toString()
            mounted = null
            if (signedIn != null) disconnectedAPI = null
            connection = signedIn?.let(::Connection)
            view = connection?.let { View(it, viewId) }
        }
        this.active = active
        route = chat
        refresh()
    }

    fun setDisplayedChat(chat: ChatAttentionChat?) {
        mounted = chat
        refresh()
    }

    fun newView() {
        retire()
        viewId = UUID.randomUUID().toString()
        mounted = null
        view = connection?.let { View(it, viewId) }
        refresh()
    }

    fun displayed(api: APIClient, viewId: String, chat: ChatAttentionChat, eventIDs: List<String>) {
        val current = connection ?: return
        if (current.api !== api || this.viewId != viewId || effective != chat || current.unauthorized) return
        val known = current.known.getOrPut(chat) { mutableSetOf() }
        val earned = eventIDs.filter { it.isNotBlank() && known.add(it) }
        if (earned.isEmpty()) return
        current.pending.getOrPut(chat) { linkedSetOf() }.addAll(earned)
        current.wake.trySend(Unit)
    }

    suspend fun disconnect(api: APIClient) {
        val oldConnection = connection?.takeIf { it.api === api } ?: return
        disconnectedAPI = WeakReference(api)
        oldConnection.stop()
        connection = null
        retire()
        viewId = UUID.randomUUID().toString()
        mounted = null
        active = false
        route = null
        try {
            withTimeoutOrNull(2_000) { oldConnection.views.toList().forEach { it.job.join() } }
        } finally {
            oldConnection.views.toList().forEach { it.job.cancel() }
        }
    }

    private fun retire(): View? {
        val old = view
        view = null
        old?.retired = true
        old?.set(null, force = true)
        if (old != null && !old.job.isCompleted) {
            old.retirement = scope.launch {
                delay(2_000)
                old.job.cancel()
            }
        }
        return old
    }

    private fun refresh() {
        view?.set(effective)
    }

    private inner class View(val owner: Connection, val id: String) {
        val api = owner.api
        var retirement: Job? = null
        val wake = Channel<Unit>(Channel.CONFLATED)
        var desired: ChatAttentionChat? = null
        var revision = 0L
        var retired = false
        val job = scope.launch {
            val heartbeat = launch {
                while (true) {
                    delay(30_000)
                    if (!retired && desired != null) set(desired, force = true)
                }
            }
            try {
                for (signal in wake) {
                    var backoff = 1_000L
                    while (true) {
                        wake.tryReceive()
                        val version = revision
                        val chat = desired
                        try {
                            api.putChatAttentionView(id, chat)
                            if (version != revision) continue
                            if (retired) return@launch
                            break
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (error is APIError && error.statusCode == 401) return@launch
                            if (version != revision) continue
                            withTimeoutOrNull(backoff) { wake.receive() }
                            backoff = (backoff * 2).coerceAtMost(30_000)
                        }
                    }
                }
            } finally {
                heartbeat.cancel()
                wake.close()
            }
        }

        init {
            owner.views.add(this)
            job.invokeOnCompletion {
                retirement?.cancel()
                owner.views.remove(this)
            }
        }

        fun set(chat: ChatAttentionChat?, force: Boolean = false) {
            if (!force && desired == chat) return
            desired = chat
            revision++
            wake.trySend(Unit)
        }
    }

    private inner class Connection(val api: APIClient) {
        val views = mutableSetOf<View>()
        val known = mutableMapOf<ChatAttentionChat, MutableSet<String>>()
        val pending = linkedMapOf<ChatAttentionChat, LinkedHashSet<String>>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var unauthorized = false
        val job: Job = scope.launch {
            for (signal in wake) {
                delay(100)
                while (pending.isNotEmpty() && !unauthorized) {
                    val chat = pending.keys.first()
                    val ids = pending.getValue(chat).take(100)
                    pending.getValue(chat).removeAll(ids.toSet())
                    if (pending.getValue(chat).isEmpty()) pending.remove(chat)
                    submit(chat, ids)
                }
            }
        }

        suspend fun submit(chat: ChatAttentionChat, ids: List<String>) {
            var backoff = 1_000L
            while (!unauthorized) {
                try {
                    api.postChatAttentionSeen(chat, ids)
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is APIError) {
                        when (error.statusCode) {
                            401 -> { unauthorized = true; return }
                            400, 404 -> {
                                if (ids.size > 1) ids.forEach { submit(chat, listOf(it)) }
                                return
                            }
                        }
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }

        fun stop() {
            job.cancel()
            wake.close()
            pending.clear()
            known.clear()
        }
    }
}
