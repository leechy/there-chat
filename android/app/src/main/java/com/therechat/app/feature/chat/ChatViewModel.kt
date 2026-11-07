package com.therechat.app.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therechat.app.core.ApiClient
import com.therechat.app.core.ApiException
import com.therechat.app.core.Chat
import com.therechat.app.core.ChatKind
import com.therechat.app.core.Member
import com.therechat.app.core.Message
import com.therechat.app.core.Realtime
import com.therechat.app.core.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val chat: Chat,
    val messages: List<Message> = emptyList(),
    val outbox: List<Message> = emptyList(),
    val hasOlder: Boolean = true,
    val loadingOlder: Boolean = false,
    val error: String? = null,
    val gone: Boolean = false,
) {
    val me: Member? get() = chat.me
    val canRead: Boolean get() = chat.isMember || (chat.freeJoin && me?.status != Member.Status.BLOCKED)
    val canPost: Boolean get() = chat.isMember && (chat.kind == ChatKind.CHAT || chat.isAdmin)
    val lastSeq: Long get() = messages.lastOrNull()?.seq ?: 0

    fun canDelete(m: Message): Boolean =
        m.kind == Message.Kind.TEXT && m.status != Message.Status.DELETED &&
            (chat.isAdmin || (chat.isMember && m.memberId == me?.id))
}

/**
 * One open chat. Realtime events nudge it to refetch through REST, which
 * stays the source of truth. While the socket is down, or the chat is only
 * read without membership (no events for those), it polls every 3 seconds.
 */
class ChatViewModel(app: Application, chat: Chat, private val invite: String?) : AndroidViewModel(app) {
    private val api = ApiClient.get(app)
    private val _state = MutableStateFlow(ChatUiState(chat))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private val realtime = Realtime.get(app)
    private var poll: Job? = null
    private var events: Job? = null
    private var lastRefresh = 0L
    private var wanted = 0
    private var refetching = false

    private val chatId get() = _state.value.chat.id

    /** True when events for this chat arrive over the socket, so polling can rest. */
    private val isLive: Boolean
        get() {
            val s = _state.value
            return realtime.connected.value && (s.chat.isMember || s.me?.status == Member.Status.PENDING)
        }

    fun start() {
        if (poll?.isActive == true) return
        events = viewModelScope.launch {
            realtime.events.collect { refetch(refetchFor(it, chatId, _state.value.lastSeq)) }
        }
        poll = viewModelScope.launch {
            refetch(REFETCH_ALL)
            while (true) {
                delay(3_000)
                // Fallback polling; a slow safety net even while live.
                if (!isLive || System.currentTimeMillis() - lastRefresh > 60_000) refetch(REFETCH_ALL)
            }
        }
    }

    fun stop() {
        poll?.cancel()
        events?.cancel()
    }

    /** Runs the requested refetches, merging requests that arrive meanwhile. */
    private fun refetch(what: Int) {
        wanted = wanted or what
        if (refetching || wanted == 0) return
        refetching = true
        viewModelScope.launch {
            try {
                while (wanted != 0) {
                    val now = wanted
                    wanted = 0
                    if (now and REFETCH_CHAT != 0) refreshChat()
                    if (now and REFETCH_LATEST != 0 || _state.value.lastSeq == 0L) loadLatest()
                    else if (now and REFETCH_NEWER != 0) loadNewer()
                }
                lastRefresh = System.currentTimeMillis()
            } finally {
                refetching = false
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun refreshChat() {
        try {
            val chat = api.chat(chatId, invite)
            _state.update { it.copy(chat = chat) }
        } catch (e: ApiException) {
            if (e.error.code == "not_found" || e.error.code == "forbidden") {
                _state.update { it.copy(gone = true, error = if (e.error.code == "forbidden") "You can't access this chat anymore." else "This chat doesn't exist anymore.") }
                stop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun loadLatest() {
        if (!_state.value.canRead) return
        try {
            val latest = api.messages(chatId, limit = 50, invite = invite)
            val s = _state.value
            if (s.messages.isNotEmpty() && (latest.firstOrNull()?.seq ?: 0) > s.lastSeq + 1) {
                merge(api.messages(chatId, after = s.lastSeq, limit = 100, invite = invite))
            }
            if (s.messages.isEmpty()) _state.update { it.copy(hasOlder = latest.size == 50) }
            merge(latest)
            markRead()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    /** Fetches everything after the last known message. */
    private suspend fun loadNewer() {
        if (!_state.value.canRead) return
        try {
            do {
                val page = api.messages(chatId, after = _state.value.lastSeq, limit = 100, invite = invite)
                merge(page)
            } while (page.size == 100)
            markRead()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun loadOlder() {
        val s = _state.value
        val first = s.messages.firstOrNull() ?: return
        if (!s.hasOlder || s.loadingOlder) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val older = api.messages(chatId, before = first.seq, limit = 50, invite = invite)
                _state.update { it.copy(hasOlder = older.size == 50) }
                merge(older)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    private fun merge(incoming: List<Message>) {
        if (incoming.isEmpty()) return
        _state.update { s ->
            val byId = LinkedHashMap<String, Message>()
            s.messages.forEach { byId[it.id] = it }
            incoming.forEach { byId[it.id] = it }
            s.copy(messages = byId.values.sortedBy { it.seq })
        }
    }

    private suspend fun markRead() {
        val s = _state.value
        val me = s.me ?: return
        if (!s.chat.isMember || s.lastSeq <= me.lastReadSeq) return
        runCatching { api.updateMe(chatId, ApiClient.MeUpdate(lastReadSeq = s.lastSeq)) }
            .onSuccess { chat -> _state.update { it.copy(chat = it.chat.copy(me = chat.me)) } }
    }

    // --- membership ---

    suspend fun join(name: String) {
        val chat = api.join(chatId, name, invite)
        _state.update { it.copy(chat = chat) }
        loadLatest()
    }

    suspend fun rename(name: String) {
        val chat = api.updateMe(chatId, ApiClient.MeUpdate(name = name))
        _state.update { it.copy(chat = chat) }
        loadLatest()
    }

    fun leave(then: () -> Unit = {}) = launchCatching {
        val chat = api.leave(chatId)
        _state.update { it.copy(chat = chat) }
        then()
    }

    // --- messages ---

    fun send(text: String) {
        val body = text.trim()
        val me = _state.value.me ?: return
        if (body.isEmpty()) return
        val clientId = UUID.randomUUID().toString()
        val draft = Message(
            id = "local-$clientId", chatId = chatId, seq = Long.MAX_VALUE, memberId = me.id,
            authorName = me.name, authorRole = me.role, kind = Message.Kind.TEXT, body = body,
            status = Message.Status.PENDING, createdAt = System.currentTimeMillis() / 1000,
        )
        _state.update { it.copy(outbox = it.outbox + draft) }
        viewModelScope.launch {
            // Retries reuse the client ID, so the server never stores duplicates.
            repeat(5) { attempt ->
                try {
                    val sent = api.post(chatId, body, clientId)
                    _state.update { it.copy(outbox = it.outbox - draft) }
                    merge(listOf(sent))
                    return@launch
                } catch (e: ApiException) {
                    _state.update { it.copy(outbox = it.outbox - draft, error = e.message) }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(1_000L shl attempt)
                }
            }
            _state.update { it.copy(outbox = it.outbox - draft, error = "Couldn't send the message. Check your connection.") }
        }
    }

    fun toggle(emoji: String, message: Message) = launchCatching {
        val mine = message.reactions.any { it.emoji == emoji && it.mine }
        merge(listOf(api.react(message.id, emoji, on = !mine)))
    }

    fun delete(message: Message) = launchCatching {
        api.deleteMessage(message.id)
        loadLatest()
    }

    fun moderate(message: Message, approve: Boolean) = launchCatching {
        merge(listOf(api.moderate(message.id, approve)))
    }

    fun report(message: Message?, reason: String) = launchCatching {
        api.report(chatId, message?.id, reason)
    }

    suspend fun suggestedNames(): List<String> = runCatching { api.suggestedNames(6) }.getOrDefault(emptyList())

    companion object {
        const val REFETCH_CHAT = 1 // header, settings, my membership
        const val REFETCH_NEWER = 2 // messages after lastSeq
        const val REFETCH_LATEST = 4 // newest page, picks up edits
        const val REFETCH_ALL = REFETCH_CHAT or REFETCH_LATEST

        /** What to refetch for an event; 0 when it doesn't concern this chat. */
        fun refetchFor(event: RealtimeEvent, chatId: String, lastSeq: Long): Int = when {
            event.type == RealtimeEvent.RESYNC.type -> REFETCH_ALL
            event.chatId != chatId -> 0
            event.type == "message" -> if ((event.seq ?: Long.MAX_VALUE) > lastSeq) REFETCH_NEWER else 0
            event.type in setOf("reaction", "message_deleted", "moderated") -> REFETCH_LATEST
            else -> REFETCH_ALL // member, chat_updated, chat_deleted
        }
    }

    private fun launchCatching(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }
}
