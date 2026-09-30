package ca.onyxtv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.onyxtv.player.core.data.OnyxRepository
import ca.onyxtv.player.core.data.ParentalSettings
import ca.onyxtv.player.core.data.PlaylistStore
import ca.onyxtv.player.core.data.RecentItem
import ca.onyxtv.player.core.data.UserStore
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.dvr.RecordingInfo
import ca.onyxtv.player.dvr.RecordingService
import ca.onyxtv.player.dvr.RecordingStore
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
    private val recStore = RecordingStore(app)
    private val repo = OnyxRepository(store)

    // ---- DVR ----
    val recordings: StateFlow<List<RecordingInfo>> =
        recStore.recordings.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun startRecording(channel: Channel, minutes: Int) =
        RecordingService.start(getApplication<Application>(), channel.url, channel.name, channel.id, minutes)

    fun stopRecording(id: String) = RecordingService.stop(getApplication<Application>(), id)

    fun deleteRecording(id: String) {
        viewModelScope.launch { recStore.delete(id) }
    }

    // ---- Contrôle parental ----
    val parental: StateFlow<ParentalSettings> =
        userStore.parental.stateIn(viewModelScope, SharingStarted.Eagerly, ParentalSettings())

    private val _unlockedGroups = MutableStateFlow<Set<String>>(emptySet())
    /** Catégories déverrouillées pour cette session (redemandées au prochain lancement). */
    val unlockedGroups: StateFlow<Set<String>> = _unlockedGroups.asStateFlow()

    private val _appUnlocked = MutableStateFlow(false)
    val appUnlocked: StateFlow<Boolean> = _appUnlocked.asStateFlow()

    private fun checkPin(pin: String) = parental.value.enabled && parental.value.pin == pin

    fun unlockGroup(group: String, pin: String): Boolean {
        if (!checkPin(pin)) return false
        _unlockedGroups.update { it + group }
        return true
    }

    fun unlockApp(pin: String): Boolean {
        if (!checkPin(pin)) return false
        _appUnlocked.value = true
        return true
    }

    /** Définit (4 chiffres) ou supprime (null) le PIN. */
    fun setPin(pin: String?) {
        viewModelScope.launch {
            userStore.updateParental { it.copy(pin = pin?.takeIf { p -> p.length == 4 && p.all(Char::isDigit) }) }
            if (pin == null) { _unlockedGroups.value = emptySet(); _appUnlocked.value = true }
        }
    }

    fun toggleLockedGroup(group: String) {
        viewModelScope.launch {
            userStore.updateParental {
                it.copy(lockedGroups = if (group in it.lockedGroups) it.lockedGroups - group else it.lockedGroups + group)
            }
            _unlockedGroups.update { it - group }
        }
    }

    fun setLockAtStart(enabled: Boolean) {
        viewModelScope.launch { userStore.updateParental { it.copy(lockAtStart = enabled) } }
    }

    // ---- Rattrapage (catch-up) ----
    /** Cible de lecture d'un programme déjà diffusé, si le fournisseur l'archive. */
    suspend fun catchupTarget(channel: Channel, program: EpgProgram): PlayTarget? =
        repo.catchupUrl(channel, program)?.let { url ->
            PlayTarget(
                id = "${channel.id}:cu:${program.start}",
                url = url,
                title = program.title,
                subtitle = "Rattrapage · ${channel.name}",
                imageUrl = channel.logoUrl,
                isLive = false,
            )
        }

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

    /** Fiche d'une série (saisons/épisodes), chargée à la demande. */
    suspend fun seriesDetail(item: VodItem) = repo.seriesDetail(item)
}

/** Catégories à masquer tant qu'elles n'ont pas été déverrouillées par le PIN. */
fun hiddenGroups(parental: ParentalSettings, unlocked: Set<String>): Set<String> =
    if (!parental.enabled) emptySet() else parental.lockedGroups - unlocked

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
