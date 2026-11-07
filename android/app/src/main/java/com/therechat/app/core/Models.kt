package com.therechat.app.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ChatKind { @SerialName("chat") CHAT, @SerialName("channel") CHANNEL }

@Serializable
data class Chat(
    val id: String,
    val kind: ChatKind,
    val title: String,
    val emoji: String,
    val lat: Double,
    val lng: Double,
    @SerialName("free_join") val freeJoin: Boolean,
    val premoderated: Boolean,
    val visible: Boolean,
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("created_at") val createdAt: Long,
    val me: Member? = null,
)

@Serializable
data class Member(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    val name: String,
    val role: String,
    val status: String,
    val notify: Boolean,
    @SerialName("last_read_seq") val lastReadSeq: Long,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    val seq: Long,
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("author_name") val authorName: String,
    val kind: String,
    val body: String,
    val status: String,
    @SerialName("created_at") val createdAt: Long,
)

/** A map area in degrees, as the server expects it. */
data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val query: String get() = "$west,$south,$east,$north"
}
