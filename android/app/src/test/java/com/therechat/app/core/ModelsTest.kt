package com.therechat.app.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's JSON (see server/internal/store) must decode into the app models. */
class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun myChatDecodesWithCounters() {
        val item = json.decodeFromString<MyChat>(
            """{"id":"abc","kind":"channel","title":"News","emoji":"📰","lat":1,"lng":2,"free_join":true,
               "premoderated":false,"visible":true,"created_at":1,"last_seq":7,"unread":3,"member_count":2,
               "me":{"id":"m1","chat_id":"abc","name":"Neo","role":"owner","status":"active","notify":true,
                     "last_read_seq":4,"created_at":1,"updated_at":1}}""",
        )
        assertEquals(ChatKind.CHANNEL, item.chat.kind)
        assertEquals(3L, item.unread)
        assertTrue(item.chat.isAdmin)
    }

    @Test
    fun messageReactionsDefaultMineToFalse() {
        val m = json.decodeFromString<Message>(
            """{"id":"x","chat_id":"c","seq":1,"member_id":"m","author_name":"A","author_role":"admin","kind":"text",
               "body":"hi","status":"published","created_at":1,
               "reactions":[{"emoji":"🔥","count":2},{"emoji":"👍","count":1,"mine":true}]}""",
        )
        assertEquals(listOf(false, true), m.reactions.map { it.mine })
        assertEquals(Member.Role.ADMIN, m.authorRole)
    }

    @Test
    fun systemMessageWithoutRoleOrReactions() {
        val m = json.decodeFromString<Message>(
            """{"id":"y","chat_id":"c","seq":2,"author_name":"B","kind":"system","body":"renamed:A","status":"published","created_at":1}""",
        )
        assertEquals(Message.Kind.SYSTEM, m.kind)
        assertTrue(m.reactions.isEmpty())
    }
}
