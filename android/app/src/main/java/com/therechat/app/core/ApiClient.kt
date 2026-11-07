package com.therechat.app.core

import com.therechat.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }
    private val registerLock = Mutex()
    private var registered = false

    @Serializable private data class ChatsResponse(val chats: List<Chat>)
    @Serializable private data class NamesResponse(val names: List<String>)
    @Serializable private data class RegisterRequest(val secret: String)
    @Serializable private data class ErrorEnvelope(val error: ApiError)

    suspend fun chatsOnMap(box: BBox): List<Chat> =
        authed { http.get("$baseUrl/v1/map/chats") { it(); parameter("bbox", box.query) } }
            .body<ChatsResponse>().chats

    suspend fun suggestedNames(count: Int = 5): List<String> =
        authed { http.get("$baseUrl/v1/names/suggest") { it(); parameter("count", count) } }
            .body<NamesResponse>().names

    private suspend fun authed(request: suspend (HttpRequestBuilder.() -> Unit) -> HttpResponse): HttpResponse {
        ensureRegistered()
        val secret = identity.secret()
        return request { bearerAuth(secret) }.also { check(it) }
    }

    private suspend fun ensureRegistered() = registerLock.withLock {
        if (registered) return@withLock
        val response = http.post("$baseUrl/v1/devices") {
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
}
