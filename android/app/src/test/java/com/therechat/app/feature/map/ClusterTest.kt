package com.therechat.app.feature.map

import com.therechat.app.core.Chat
import com.therechat.app.core.ChatKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterTest {
    private fun chat(id: String, lat: Double, lng: Double) = Chat(
        id = id, kind = ChatKind.CHAT, title = id, emoji = "❓", lat = lat, lng = lng,
        freeJoin = true, premoderated = false, visible = true, createdAt = 0,
    )

    @Test
    fun nearbyChatsShareAMarker() {
        val chats = listOf(
            chat("a", 48.85300, 2.34990),
            chat("b", 48.85301, 2.34991), // a few metres away
            chat("c", 48.85900, 2.36000), // far across the screen
        )
        val clusters = MapViewModel.cluster(chats, lngSpan = 0.02)
        assertEquals(2, clusters.size)
        assertEquals(listOf(2, 1), clusters.map { it.chats.size }.sortedDescending())
    }
}
