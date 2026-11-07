package com.therechat.app.core

import android.content.Context
import com.therechat.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ApiError(val code: String, val message: String)

class ApiException(val error: ApiError) : Exception(error.message)

/**
 * Thin client for the There REST API. Registers the device lazily on first
 * use, then sends the device secret as a bearer token.
 */
class ApiClient(
    private val identity: DeviceIdentity,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }
    private val registerLock = Mutex()
    private var registered = false

    @Serializable private data class ChatsResponse(val chats: List<Chat>)
    @Serializable private data class MyChatsResponse(val chats: List<MyChat>)
    @Serializable private data class NamesResponse(val names: List<String>)
    @Serializable private data class MessagesResponse(val messages: List<Message>)
    @Serializable private data class MembersResponse(val members: List<Member>)
    @Serializable private data class ErrorEnvelope(val error: ApiError)

    @Serializable private data class RegisterRequest(val secret: String)
    @Serializable private data class JoinRequest(val name: String, @kotlinx.serialization.SerialName("invite_code") val inviteCode: String? = null)
    @Serializable private data class PostRequest(val body: String, @kotlinx.serialization.SerialName("client_id") val clientId: String)
    @Serializable private data class ReportRequest(
        @kotlinx.serialization.SerialName("chat_id") val chatId: String,
        @kotlinx.serialization.SerialName("message_id") val messageId: String? = null,
        val reason: String,
    )

    @Serializable
    data class NewChat(
        val kind: ChatKind,
        val title: String,
        val emoji: String,
        val lat: Double,
        val lng: Double,
        @kotlinx.serialization.SerialName("free_join") val freeJoin: Boolean,
        val premoderated: Boolean,
        val visible: Boolean,
        val name: String,
    )

    @Serializable
    data class ChatSettings(
        val title: String? = null,
        val emoji: String? = null,
        @kotlinx.serialization.SerialName("free_join") val freeJoin: Boolean? = null,
        val premoderated: Boolean? = null,
        val visible: Boolean? = null,
    )

    @Serializable
    data class MeUpdate(
        val name: String? = null,
        val notify: Boolean? = null,
        @kotlinx.serialization.SerialName("last_read_seq") val lastReadSeq: Long? = null,
    )

    enum class MemberAction { APPROVE, REJECT, PROMOTE, DEMOTE, BLOCK, UNBLOCK }

    // --- map and lists ---

    suspend fun chatsOnMap(box: BBox): List<Chat> =
        call(HttpMethod.Get, "/v1/map/chats") { parameter("bbox", box.query) }.body<ChatsResponse>().chats

    suspend fun myChats(): List<MyChat> = call(HttpMethod.Get, "/v1/me/chats").body<MyChatsResponse>().chats

    suspend fun suggestedNames(count: Int = 5): List<String> =
        call(HttpMethod.Get, "/v1/names/suggest") { parameter("count", count) }.body<NamesResponse>().names

    // --- chats ---

    suspend fun createChat(new: NewChat): Chat = call(HttpMethod.Post, "/v1/chats", enc(new)).body()

    suspend fun chat(id: String, invite: String? = null): Chat =
        call(HttpMethod.Get, "/v1/chats/$id") { invite?.let { parameter("invite", it) } }.body()

    suspend fun chatByInvite(code: String): Chat = call(HttpMethod.Get, "/v1/invites/$code").body()

    suspend fun updateChat(id: String, settings: ChatSettings): Chat = call(HttpMethod.Patch, "/v1/chats/$id", enc(settings)).body()

    suspend fun deleteChat(id: String) { call(HttpMethod.Delete, "/v1/chats/$id") }

    suspend fun join(id: String, name: String, invite: String?): Chat =
        call(HttpMethod.Post, "/v1/chats/$id/join", enc(JoinRequest(name, invite))).body()

    suspend fun leave(id: String): Chat = call(HttpMethod.Post, "/v1/chats/$id/leave").body()

    suspend fun updateMe(chatId: String, update: MeUpdate): Chat = call(HttpMethod.Patch, "/v1/chats/$chatId/me", enc(update)).body()

    // --- members ---

    suspend fun members(chatId: String): List<Member> =
        call(HttpMethod.Get, "/v1/chats/$chatId/members").body<MembersResponse>().members

    suspend fun member(chatId: String, memberId: String, action: MemberAction): Member =
        call(HttpMethod.Post, "/v1/chats/$chatId/members/$memberId/${action.name.lowercase()}").body()

    suspend fun deleteMessagesOf(memberId: String, chatId: String) {
        call(HttpMethod.Delete, "/v1/chats/$chatId/members/$memberId/messages")
    }

    // --- messages ---

    suspend fun messages(chatId: String, before: Long? = null, after: Long? = null, limit: Int = 50, invite: String? = null): List<Message> =
        call(HttpMethod.Get, "/v1/chats/$chatId/messages") {
            parameter("limit", limit)
            before?.let { parameter("before", it) }
            after?.let { parameter("after", it) }
            invite?.let { parameter("invite", it) }
        }.body<MessagesResponse>().messages

    suspend fun post(chatId: String, body: String, clientId: String): Message =
        call(HttpMethod.Post, "/v1/chats/$chatId/messages", enc(PostRequest(body, clientId))).body()

    suspend fun deleteMessage(id: String) { call(HttpMethod.Delete, "/v1/messages/$id") }

    suspend fun moderate(id: String, approve: Boolean): Message =
        call(HttpMethod.Post, "/v1/messages/$id/${if (approve) "approve" else "reject"}").body()

    suspend fun react(id: String, emoji: String, on: Boolean): Message =
        call(if (on) HttpMethod.Put else HttpMethod.Delete, "/v1/messages/$id/reactions/${emoji.encodeURLPathPart()}").body()

    suspend fun report(chatId: String, messageId: String?, reason: String) {
        call(HttpMethod.Post, "/v1/reports", enc(ReportRequest(chatId, messageId, reason)))
    }

    // --- plumbing ---

    private inline fun <reified T> enc(value: T): String = json.encodeToString(value)

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        ensureRegistered()
        val secret = identity.secret()
        val response = http.request("$baseUrl$path") {
            this.method = method
            bearerAuth(secret)
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            block()
        }
        check(response)
        return response
    }

    private suspend fun ensureRegistered() = registerLock.withLock {
        if (registered) return@withLock
        val response = http.request("$baseUrl/v1/devices") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(identity.secret()))
        }
        check(response)
        registered = true
    }

    private suspend fun check(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val error = runCatching { response.body<ErrorEnvelope>().error }
            .getOrElse { ApiError("http_${response.status.value}", response.status.description) }
        throw ApiException(error)
    }

    companion object {
        @Volatile private var instance: ApiClient? = null

        /** One client per process, shared by all screens. */
        fun get(context: Context): ApiClient =
            instance ?: synchronized(this) {
                instance ?: ApiClient(DeviceIdentity(context.applicationContext)).also { instance = it }
            }
    }
}
