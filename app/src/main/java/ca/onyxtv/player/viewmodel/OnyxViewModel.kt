package ca.onyxtv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.onyxtv.player.core.data.OnyxRepository
import ca.onyxtv.player.core.data.PlaylistStore
import ca.onyxtv.player.core.data.RecentItem
import ca.onyxtv.player.core.data.UserStore
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
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
    private val userStore = UserStore(app)
    private val repo = OnyxRepository(store)

    private val _state = MutableStateFlow(OnyxUiState())
    val state: StateFlow<OnyxUiState> = _state.asStateFlow()

    /** Message d'état de la dernière source ajoutée (test de connexion), affiché dans Réglages. */
    private val _sourceStatus = MutableStateFlow<String?>(null)
    val sourceStatus: StateFlow<String?> = _sourceStatus.asStateFlow()

    /** Sources configurées, observées pour l'écran Réglages. */
    val sources: StateFlow<List<PlaylistSource>> =
        store.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Favoris (ids) et récents (reprise), toujours chauds pour l'accueil et le direct. */
    val favorites: StateFlow<Set<String>> =
        userStore.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val recents: StateFlow<List<RecentItem>> =
        userStore.recents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            _sourceStatus.value = "Liste ajoutée — chargement…"
            refresh()
        }
    }

    fun addXtream(label: String, server: String, username: String, password: String) {
        viewModelScope.launch {
            val src = PlaylistSource.Xtream(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { "Compte Xtream" },
                server = server.trim(),
                username = username.trim(),
                password = password.trim(),
            )
            store.add(src)
            // Test de connexion : message clair au lieu d'un échec silencieux.
            _sourceStatus.value = "Connexion au serveur…"
            _sourceStatus.value = runCatching { repo.probeXtream(src) }
                .getOrElse { e ->
                    "Serveur injoignable : ${e.message ?: "erreur réseau"}. " +
                        "Vérifiez l'adresse (http://serveur:port) et votre connexion."
                }
            refresh()
        }
    }

    fun clearSourceStatus() { _sourceStatus.value = null }

    fun removeSource(id: String) {
        viewModelScope.launch {
            store.remove(id)
            refresh()
        }
    }

    // ---- Favoris ----
    fun toggleFavorite(id: String) {
        viewModelScope.launch { userStore.toggleFavorite(id) }
    }

    // ---- Récents / reprise ----
    fun onPlaybackProgress(target: PlayTarget, positionMs: Long, durationMs: Long) {
        val id = target.id ?: return
        viewModelScope.launch {
            userStore.recordRecent(
                RecentItem(
                    id = id,
                    title = target.title,
                    subtitle = target.subtitle,
                    url = target.url,
                    imageUrl = target.imageUrl,
                    live = target.isLive,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            )
        }
    }

    fun removeRecent(id: String) {
        viewModelScope.launch { userStore.removeRecent(id) }
    }

    // ---- Zapping ----
    /** Chaîne voisine (+1/−1) dans l'ordre courant, en boucle. */
    fun neighborChannel(currentId: String?, delta: Int): Channel? {
        val list = _state.value.channels
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.id == currentId }
        if (idx < 0) return list.first()
        val n = ((idx + delta) % list.size + list.size) % list.size
        return list[n]
    }

    /** Guide (now/next) pour une chaîne donnée. */
    suspend fun epgFor(channel: Channel) = repo.epg(channel)
}

/**
 * Recommandations : heuristique locale basée sur les catégories des favoris et des récents.
 * Sans historique, on privilégie les mieux notés. Les contenus déjà vus sont écartés.
 */
fun recommendVod(vod: List<VodItem>, favorites: Set<String>, recents: List<RecentItem>, limit: Int = 24): List<VodItem> {
    val seen = recents.map { it.id }.toSet()
    fun rating(v: VodItem) = v.rating?.toFloatOrNull() ?: 0f
    val likedCats = vod.filter { it.id in favorites || it.id in seen }
        .mapNotNull { it.category }
        .groupingBy { it }.eachCount()
    if (likedCats.isEmpty()) return vod.sortedByDescending(::rating).take(limit)
    return vod.filter { it.id !in seen }
        .sortedWith(compareByDescending<VodItem> { likedCats[it.category] ?: 0 }.thenByDescending(::rating))
        .take(limit)
}
