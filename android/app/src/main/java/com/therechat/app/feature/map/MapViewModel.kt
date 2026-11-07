package com.therechat.app.feature.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.therechat.app.core.ApiClient
import com.therechat.app.core.ApiException
import com.therechat.app.core.BBox
import com.therechat.app.core.Chat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/** Chats close enough on screen to overlap are shown as one marker. */
data class ChatCluster(val chats: List<Chat>) {
    val id: String = chats.joinToString(",") { it.id }
    val position: LatLng = LatLng(chats.sumOf { it.lat } / chats.size, chats.sumOf { it.lng } / chats.size)
}

data class MapUiState(
    val clusters: List<ChatCluster> = emptyList(),
    val tooFar: Boolean = false,
    /** 0 = at the threshold where chats appear, 1 = street level, < 0 = hidden. */
    val zoomProgress: Float = -1f,
    val error: String? = null,
    val unread: Long = 0,
    val myChatCount: Int = 0,
)

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val api = ApiClient.get(app)
    private var lastBounds: LatLngBounds? = null
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var chats: List<Chat> = emptyList()
    private var loadJob: Job? = null

    /** Reloads chats in view and the "My Chats" counters, e.g. after coming back to the map. */
    fun refresh() {
        lastBounds?.let(::onCameraIdle)
        viewModelScope.launch {
            runCatching { api.myChats() }.onSuccess { mine ->
                _state.update { s ->
                    s.copy(myChatCount = mine.size, unread = mine.filter { it.me?.notify != false }.sumOf { it.unread })
                }
            }
        }
    }

    fun onCameraIdle(bounds: LatLngBounds) {
        lastBounds = bounds
        val latSpan = bounds.northeast.latitude - bounds.southwest.latitude
        val lngSpan = bounds.northeast.longitude - bounds.southwest.longitude
        val span = max(latSpan, lngSpan)
        val tooFar = latSpan > MAX_SPAN || lngSpan > MAX_SPAN
        val progress = (1 - ln(span / STREET_SPAN) / ln(MAX_SPAN / STREET_SPAN)).toFloat()
        loadJob?.cancel()
        if (tooFar) {
            chats = emptyList()
            _state.update { it.copy(clusters = emptyList(), tooFar = true, zoomProgress = progress) }
            return
        }
        _state.update { it.copy(tooFar = false, zoomProgress = progress, clusters = cluster(chats, lngSpan)) }
        val box = BBox(
            west = bounds.southwest.longitude, south = bounds.southwest.latitude,
            east = bounds.northeast.longitude, north = bounds.northeast.latitude,
        )
        loadJob = viewModelScope.launch {
            try {
                chats = api.chatsOnMap(box)
                _state.update { it.copy(clusters = cluster(chats, lngSpan), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.error.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Network error") }
            }
        }
    }

    companion object {
        /** Same limit as the server (THERE_MAX_SPAN): beyond it chats are hidden. */
        const val MAX_SPAN = 0.04
        private const val STREET_SPAN = 0.002

        /** Groups chats into grid cells ~1/12 of the visible width. */
        fun cluster(chats: List<Chat>, lngSpan: Double): List<ChatCluster> {
            val cell = max(lngSpan, 1e-6) / 12
            return chats
                .groupBy { "${floor(it.lat / cell).toLong()}:${floor(it.lng / cell).toLong()}" }
                .values
                .map { group -> ChatCluster(group.sortedBy { it.createdAt }) }
                .sortedBy { it.id }
        }
    }
}
