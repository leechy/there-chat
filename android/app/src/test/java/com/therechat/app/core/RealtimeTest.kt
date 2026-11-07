package com.therechat.app.core

import com.therechat.app.feature.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeTest {
    @Test
    fun decodesEvents() {
        val event = RealtimeEvent.decode("""{"type":"message","chat_id":"c1","seq":42,"message_id":"m1","extra":1}""")
        assertEquals(RealtimeEvent("message", chatId = "c1", seq = 42, messageId = "m1"), event)
        assertNull(RealtimeEvent.decode("not json"))
    }

    @Test
    fun backoffDoublesUpToThirtySeconds() {
        assertEquals(1_000L, Realtime.backoffMillis(0))
        assertEquals(8_000L, Realtime.backoffMillis(3))
        assertEquals(30_000L, Realtime.backoffMillis(10))
    }

    @Test
    fun chatRefetchesOnlyWhatTheEventNeeds() {
        fun f(type: String, chat: String? = "c1", seq: Long? = null) =
            ChatViewModel.refetchFor(RealtimeEvent(type, chatId = chat, seq = seq), "c1", lastSeq = 10)
        assertEquals(ChatViewModel.REFETCH_NEWER, f("message", seq = 11))
        assertEquals(0, f("message", seq = 10)) // already have it (our own send)
        assertEquals(0, f("message", chat = "other", seq = 99))
        assertEquals(ChatViewModel.REFETCH_LATEST, f("reaction", seq = 5))
        assertEquals(ChatViewModel.REFETCH_ALL, f("member"))
        assertEquals(ChatViewModel.REFETCH_ALL, f("resync", chat = null))
    }
}
