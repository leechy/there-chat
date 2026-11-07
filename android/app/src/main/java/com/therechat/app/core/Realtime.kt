package com.therechat.app.core

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * A nudge from the server: something changed in a chat. Events carry no
 * content; listeners refetch through REST, which stays the source of truth.
 */
@Serializable
data class RealtimeEvent(
    val type: String,
    @SerialName("chat_id") val chatId: String? = null,
    val seq: Long? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("member_id") val memberId: String? = null,
    val action: String? = null,
) {
    companion object {
        /** Sent after every (re)connect: events may have been missed, refetch everything. */
        val RESYNC = RealtimeEvent("resync")

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): RealtimeEvent? = runCatching { json.decodeFromString<RealtimeEvent>(text) }.getOrNull()
    }
}

/**
 * The app's single WebSocket to `GET /v1/ws`. Open while the app is in the
 * foreground ([start] / [stop] from the activity). Reconnects with
 * exponential backoff; while [connected] is false, screens fall back to polling.
 */
class Realtime(private val api: ApiClient) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val http = HttpClient(OkHttp) {
        // OkHttp pings the server and fails the socket if a pong doesn't come back.
        engine { config { pingInterval(30, TimeUnit.SECONDS) } }
        install(WebSockets)
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    private suspend fun run() {
        var attempt = 0
        while (scope.isActive) {
            try {
                val (url, secret) = api.webSocketTarget()
                http.webSocket(urlString = url, request = { bearerAuth(secret) }) {
                    val hello = incoming.receive() as? Frame.Text
                    if (hello == null || RealtimeEvent.decode(hello.readText())?.type != "hello") return@webSocket
                    attempt = 0
                    _connected.value = true
                    _events.emit(RealtimeEvent.RESYNC)
                    for (frame in incoming) {
                        if (frame is Frame.Text) RealtimeEvent.decode(frame.readText())?.let { _events.emit(it) }
                    }
                }
            } catch (e: CancellationException) {
                _connected.value = false
                throw e
            } catch (_: Exception) {
            }
            _connected.value = false
            delay((backoffMillis(attempt) * Random.nextDouble(0.5, 1.0)).toLong())
            attempt++
        }
    }

    companion object {
        /** 1, 2, 4 … 30 seconds; jitter is added by the caller. */
        fun backoffMillis(attempt: Int): Long = min(30_000L, 1_000L shl min(attempt, 5))

        @Volatile private var instance: Realtime? = null

        fun get(context: Context): Realtime =
            instance ?: synchronized(this) {
                instance ?: Realtime(ApiClient.get(context)).also { instance = it }
            }
    }
}
