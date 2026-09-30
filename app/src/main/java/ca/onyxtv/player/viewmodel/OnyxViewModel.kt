package ca.onyxtv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.onyxtv.player.core.data.OnyxRepository
import ca.onyxtv.player.core.data.PlaylistStore
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.VodItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** État global des contenus affichés. */
data class OnyxUiState(
    val loading: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val vod: List<VodItem> = emptyList(),
    val error: String? = null,
) {
    val groups: List<String>
        get() = channels.mapNotNull { it.groupTitle }.distinct()
}

class OnyxViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PlaylistStore(app)
    private val repo = OnyxRepository(store)

    private val _state = MutableStateFlow(OnyxUiState())
    val state: StateFlow<OnyxUiState> = _state.asStateFlow()

    /** Sources configurées, observées pour l'écran Réglages. */
    val sources: StateFlow<List<PlaylistSource>> =
        store.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val ch = repo.channels()
                val vod = repo.vod()
                OnyxUiState(loading = false, channels = ch, vod = vod)
            }.onSuccess { _state.value = it }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun addM3u(label: String, url: String, epgUrl: String?) {
        viewModelScope.launch {
            store.add(
                PlaylistSource.M3u(
                    id = UUID.randomUUID().toString(),
                    label = label.ifBlank { "Liste M3U" },
                    url = url.trim(),
                    epgUrl = epgUrl?.trim()?.ifBlank { null },
                )
            )
            refresh()
        }
    }

    fun addXtream(label: String, server: String, username: String, password: String) {
        viewModelScope.launch {
            store.add(
                PlaylistSource.Xtream(
                    id = UUID.randomUUID().toString(),
                    label = label.ifBlank { "Compte Xtream" },
                    server = server.trim(),
                    username = username.trim(),
                    password = password.trim(),
                )
            )
            refresh()
        }
    }

    fun removeSource(id: String) {
        viewModelScope.launch {
            store.remove(id)
            refresh()
        }
    }

    /** Guide (now/next) pour une chaîne donnée. */
    suspend fun epgFor(channel: Channel) = repo.epg(channel)
}
