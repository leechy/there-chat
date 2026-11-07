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
) {
    val isMember: Boolean get() = me?.status == Member.Status.ACTIVE
    val isAdmin: Boolean get() = isMember && (me?.role == Member.Role.OWNER || me?.role == Member.Role.ADMIN)
    val inviteUrl: String? get() = inviteCode?.let { "https://there-chat.com/$it" }
}

/** A row of "My Chats": the chat plus counters. */
@Serializable
data class MyChat(
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
    @SerialName("last_seq") val lastSeq: Long,
    val unread: Long,
    @SerialName("member_count") val memberCount: Long,
) {
    val chat: Chat get() = Chat(id, kind, title, emoji, lat, lng, freeJoin, premoderated, visible, inviteCode, createdAt, me)
}

@Serializable
data class Member(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    val name: String,
    val role: Role,
    val status: Status,
    val notify: Boolean,
    @SerialName("last_read_seq") val lastReadSeq: Long,
    @SerialName("created_at") val createdAt: Long = 0,
) {
    @Serializable
    enum class Role { @SerialName("owner") OWNER, @SerialName("admin") ADMIN, @SerialName("member") MEMBER }

    @Serializable
    enum class Status {
        @SerialName("pending") PENDING, @SerialName("active") ACTIVE,
        @SerialName("left") LEFT, @SerialName("blocked") BLOCKED,
    }
}

@Serializable
data class Reaction(val emoji: String, val count: Int, val mine: Boolean = false)

@Serializable
data class Message(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    val seq: Long,
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("author_name") val authorName: String,
    @SerialName("author_role") val authorRole: Member.Role? = null,
    val kind: Kind,
    val body: String,
    val status: Status,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val reactions: List<Reaction> = emptyList(),
) {
    @Serializable
    enum class Kind { @SerialName("text") TEXT, @SerialName("system") SYSTEM }

    @Serializable
    enum class Status {
        @SerialName("pending") PENDING, @SerialName("published") PUBLISHED,
        @SerialName("rejected") REJECTED, @SerialName("deleted") DELETED,
    }

    val isLocal: Boolean get() = id.startsWith("local-")
}

/** A map area in degrees, as the server expects it. */
data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val query: String get() = "$west,$south,$east,$north"
}
