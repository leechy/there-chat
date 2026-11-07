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
 * One open chat. Polls the newest page every few seconds while visible and
 * merges it, which also picks up reactions, deletions and moderation.
 */
class ChatViewModel(app: Application, chat: Chat, private val invite: String?) : AndroidViewModel(app) {
    private val api = ApiClient.get(app)
    private val _state = MutableStateFlow(ChatUiState(chat))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var poll: Job? = null

    private val chatId get() = _state.value.chat.id

    fun start() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (true) {
                refreshChat()
                loadLatest()
                delay(3_000)
            }
        }
    }

    fun stop() { poll?.cancel() }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun refreshChat() {
        try {
            val chat = api.chat(chatId, invite)
            _state.update { it.copy(chat = chat) }
        } catch (e: ApiException) {
            if (e.error.code == "not_found" || e.error.code == "forbidden") {
                _state.update { it.copy(gone = true, error = if (e.error.code == "forbidden") "You can't access this chat anymore." else "This chat doesn't exist anymore.") }
                poll?.cancel()
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
