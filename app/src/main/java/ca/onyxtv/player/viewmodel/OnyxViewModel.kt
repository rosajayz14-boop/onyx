package ca.onyxtv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.onyxtv.player.core.data.AppPrefs
import ca.onyxtv.player.core.data.CatalogCache
import ca.onyxtv.player.core.data.CatalogSnapshot
import ca.onyxtv.player.core.data.OnyxRepository
import ca.onyxtv.player.core.data.ParentalSettings
import ca.onyxtv.player.core.data.PlaylistStore
import ca.onyxtv.player.core.data.RecentItem
import ca.onyxtv.player.core.data.SourceReport
import ca.onyxtv.player.core.data.UserStore
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.core.net.Http
import ca.onyxtv.player.core.update.UpdateChecker
import ca.onyxtv.player.core.update.UpdateInfo
import ca.onyxtv.player.dvr.RecordingInfo
import ca.onyxtv.player.dvr.RecordingService
import ca.onyxtv.player.dvr.RecordingStore
import ca.onyxtv.player.player.PlayTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import ca.onyxtv.player.ui.components.toPlayTarget
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first

/** État global des contenus affichés. */
data class OnyxUiState(
    val loading: Boolean = false,
    /** Message d'avancement pendant une mise à jour (ex. « Compte X : films… »). */
    val progress: String? = null,
    val channels: List<Channel> = emptyList(),
    val vod: List<VodItem> = emptyList(),
    /** Bilan par source de la dernière mise à jour (comptes, erreurs). */
    val reports: List<SourceReport> = emptyList(),
    /** Horodatage de la dernière mise à jour réussie (0 = jamais). */
    val updatedAt: Long = 0,
    /** Erreur globale (toutes les sources ont échoué et rien n'est affichable). */
    val error: String? = null,
    /** Incrémenté par « Mettre à jour le guide » pour forcer le rechargement de l'EPG affiché. */
    val epgVersion: Int = 0,
) {
    val groups: List<String>
        get() = channels.mapNotNull { it.groupTitle }.distinct()

    val hasContent: Boolean get() = channels.isNotEmpty() || vod.isNotEmpty()
    val sourceErrors: List<String> get() = reports.mapNotNull { r -> r.error?.let { "${r.label} — $it" } }
}

/** État de la mise à jour de l'application. */
data class UpdateUi(
    val info: UpdateInfo? = null,
    val checking: Boolean = false,
    /** Progression du téléchargement 0f..1f, null si aucun téléchargement. */
    val downloading: Float? = null,
    val readyFile: File? = null,
    val error: String? = null,
    val checkedAt: Long = 0,
)

class OnyxViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PlaylistStore(app)
    private val userStore = UserStore(app)
    private val recStore = RecordingStore(app)
    private val cache = CatalogCache(app)
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

    /**
     * Recommandations calculées EN ARRIÈRE-PLAN, uniquement quand le catalogue, les favoris ou
     * l'ensemble des contenus vus changent (pas à chaque sauvegarde de position toutes les 5 s).
     */
    val recommended: StateFlow<List<VodItem>> = combine(
        _state.map { it.vod }.distinctUntilChanged(),
        favorites,
        recents.map { list -> list.map { it.id }.toSet() }.distinctUntilChanged(),
    ) { vod, favs, seen -> recommendVod(vod, favs, seen) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Suggestions TMDB (tendances de la semaine), limitées à ce qui existe dans le catalogue ----
    private val tmdbTrending = MutableStateFlow<List<ca.onyxtv.player.core.tmdb.TmdbClient.Trending>>(emptyList())
    init {
        val key = ca.onyxtv.player.BuildConfig.TMDB_API_KEY
        if (key.isNotBlank()) viewModelScope.launch {
            tmdbTrending.value = runCatching { ca.onyxtv.player.core.tmdb.TmdbClient.trendingWeek(key) }.getOrDefault(emptyList())
        }
    }
    /** Titres tendance TMDB présents dans le catalogue (appariés par titre normalisé + année), affiche TMDB. */
    val tmdbSuggestions: StateFlow<List<VodItem>> = combine(
        _state.map { it.vod }.distinctUntilChanged(),
        tmdbTrending,
    ) { vod, trend ->
        if (trend.isEmpty() || vod.isEmpty()) emptyList()
        else {
            val byName = HashMap<String, MutableList<VodItem>>()
            vod.forEach { byName.getOrPut(normTitle(it.name)) { ArrayList() }.add(it) }
            val seen = HashSet<String>()
            trend.mapNotNull { t ->
                val cands = byName[normTitle(t.title)] ?: return@mapNotNull null
                val pick = cands.firstOrNull { c -> (c.kind == ca.onyxtv.player.core.model.MediaKind.SERIES) == t.tv && (t.year == null || c.year == null || c.year == t.year) }
                    ?: cands.firstOrNull { c -> (c.kind == ca.onyxtv.player.core.model.MediaKind.SERIES) == t.tv }
                    ?: return@mapNotNull null
                if (!seen.add(pick.id)) return@mapNotNull null
                if (t.posterUrl != null) pick.copy(posterUrl = t.posterUrl) else pick
            }.take(24)
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Recherche (anti-rebond + arrière-plan) ----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    // `by lazy` : `parental` est déclaré plus bas (ordre d'initialisation des propriétés).
    // Le filtre parental s'applique AVANT la troncature à 60 : sinon 60 résultats verrouillés
    // masquaient des résultats visibles.
    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<Pair<List<Channel>, List<VodItem>>> by lazy {
        combine(
            _searchQuery.debounce(250),
            _state.map { it.channels to it.vod }.distinctUntilChanged(),
            parental,
            _unlockedGroups,
            prefs,
        ) { q, (channels, vod), p, u, pr ->
            val needle = q.trim()
            val hidden = hiddenGroups(p, u) + pr.hiddenCategories
            if (needle.length < 2) emptyList<Channel>() to emptyList()
            else channels.asSequence().filter { it.groupTitle !in hidden && it.id !in pr.hiddenChannelIds && it.name.contains(needle, ignoreCase = true) }.take(60).toList() to
                vod.asSequence().filter { it.category !in hidden && it.name.contains(needle, ignoreCase = true) }.take(60).toList()
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<Channel>() to emptyList())
    }

    // ---- Journal de plantage ----
    private val crashFile = File(app.filesDir, ca.onyxtv.player.OnyxApp.CRASH_FILE)
    private val _lastCrash = MutableStateFlow(runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull())
    /** Dernier plantage enregistré (observable : « Effacer le journal » met l'écran à jour). */
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    fun clearCrash() { runCatching { crashFile.delete() }; _lastCrash.value = null }

    // ---- Préférences ----
    val prefs: StateFlow<AppPrefs> =
        userStore.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())

    fun setResumeOnStart(enabled: Boolean) {
        viewModelScope.launch { userStore.updatePrefs { it.copy(resumeOnStart = enabled) } }
    }

    fun setDiagnostics(enabled: Boolean) {
        viewModelScope.launch { userStore.updatePrefs { it.copy(diagnostics = enabled) } }
    }

    fun dismissUpdate(commit: String) {
        viewModelScope.launch { userStore.updatePrefs { it.copy(dismissedUpdateCommit = commit) } }
    }

    fun setSeekSteps(back: Int, forward: Int) {
        viewModelScope.launch { userStore.updatePrefs { it.copy(seekBackSeconds = back, seekForwardSeconds = forward) } }
    }

    fun setLivePreview(enabled: Boolean) {
        viewModelScope.launch { userStore.updatePrefs { it.copy(livePreview = enabled) } }
    }

    fun setSubtitleStyle(scale: Float? = null, background: Boolean? = null, yellow: Boolean? = null) {
        viewModelScope.launch {
            userStore.updatePrefs {
                it.copy(subtitleScale = scale ?: it.subtitleScale, subtitleBackground = background ?: it.subtitleBackground, subtitleYellow = yellow ?: it.subtitleYellow)
            }
        }
    }

    // ---- Comptes Xtream : statut / expiration / connexions ----
    private val _accounts = MutableStateFlow<Map<String, OnyxRepository.AccountInfo>>(emptyMap())
    val accounts: StateFlow<Map<String, OnyxRepository.AccountInfo>> = _accounts.asStateFlow()

    fun refreshAccounts() {
        viewModelScope.launch {
            val xts = sources.value.filterIsInstance<PlaylistSource.Xtream>()
            if (xts.isEmpty()) return@launch
            val m = HashMap<String, OnyxRepository.AccountInfo>()
            xts.forEach { src -> runCatching { repo.accountInfo(src) }.getOrNull()?.let { m[src.id] = it } }
            if (m.isNotEmpty()) _accounts.value = m
        }
    }

    // ---- Mise à jour de l'application (vérification quotidienne) ----
    private val _update = MutableStateFlow(UpdateUi())
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    /** Vérifie s'il existe une nouvelle version publiée ; au plus une fois par jour sauf [force]. */
    fun checkForUpdate(force: Boolean = false) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val last = userStore.prefs.first().lastUpdateCheck
            if (!force && now - last < UPDATE_CHECK_MS) return@launch
            _update.update { it.copy(checking = true, error = null) }
            runCatching { UpdateChecker.check() }
                .onSuccess { info ->
                    _update.update { it.copy(checking = false, info = info, checkedAt = now) }
                    userStore.updatePrefs { it.copy(lastUpdateCheck = now) }
                }
                .onFailure { e -> _update.update { it.copy(checking = false, error = Http.describe(e), checkedAt = now) } }
        }
    }

    /** Télécharge la nouvelle version puis lance l'installateur système. */
    fun downloadAndInstallUpdate() {
        if (_update.value.downloading != null) return
        viewModelScope.launch {
            _update.update { it.copy(downloading = 0f, error = null) }
            runCatching { UpdateChecker.download(getApplication<Application>()) { p -> _update.update { it.copy(downloading = p) } } }
                .onSuccess { file ->
                    _update.update { it.copy(downloading = null, readyFile = file) }
                    installUpdate()
                }
                .onFailure { e -> _update.update { it.copy(downloading = null, error = "Téléchargement impossible : ${Http.describe(e)}") } }
        }
    }

    fun installUpdate() {
        val file = _update.value.readyFile ?: return
        val ctx = getApplication<Application>()
        if (!UpdateChecker.canInstall(ctx)) {
            UpdateChecker.openInstallPermission(ctx)
            _update.update { it.copy(error = "Autorisez ONYX TV à installer des applications, puis appuyez de nouveau sur « Installer ».") }
            return
        }
        if (UpdateChecker.sameSigner(ctx, file) == false) {
            _update.update { it.copy(error = "Cette mise à jour est signée avec une autre clé que l'app installée : Android la refusera. Désinstallez ONYX TV puis réinstallez-la depuis l'URL tv-latest (favoris et réglages seront perdus).") }
            return
        }
        runCatching { UpdateChecker.install(ctx, file) }
            .onFailure { e -> _update.update { it.copy(error = "Installation impossible : ${e.message}") } }
    }

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

    // ---- Catalogue : cache disque + mise à jour ----

    init {
        viewModelScope.launch {
            // 1) Ouverture instantanée sur le dernier catalogue connu.
            val cached = cache.load()
            if (cached != null && !cached.isEmpty) {
                _state.update {
                    it.copy(channels = cached.channels, vod = cached.vod, reports = cached.reports, updatedAt = cached.updatedAt)
                }
            }
            // 2) Rafraîchissement automatique si le cache est absent, vide ou ancien.
            if (cached == null || cached.isEmpty || cached.isStale(AUTO_REFRESH_MS)) refresh()
            else refreshAccounts()
        }
        checkForUpdate()
        // Rappels / enregistrements programmés : vérification toutes les 20 s tant que l'app vit.
        viewModelScope.launch { while (true) { delay(20_000); runCatching { checkReminders() } } }
    }

    /**
     * Met à jour TOUT le catalogue (chaînes, films, séries) depuis les serveurs.
     * Les sources en échec conservent leurs données précédentes ; le bilan est dans [OnyxUiState.reports].
     */
    private var refreshRequested = false

    fun refresh() {
        // Déjà en cours (ex. rafraîchissement du démarrage) : on note la demande et on relance
        // à la fin. Sinon « Ajouter un compte » pendant un chargement était perdu.
        if (_state.value.loading) { refreshRequested = true; return }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, progress = "Connexion aux sources…") }
            runCatching { repo.loadCatalog { msg -> _state.update { it.copy(progress = msg) } } }
                .onSuccess { fresh ->
                    val merged = mergeWithPrevious(fresh, _state.value)
                    cache.save(merged)
                    // Guide TV : précharger le xmltv des comptes en arrière-plan (ouverture déjà remplie).
                    viewModelScope.launch { runCatching { repo.prefetchEpg(merged.channels) }; _state.update { it.copy(epgVersion = it.epgVersion + 1) } }
                    refreshAccounts()
                    val nothing = merged.isEmpty && merged.reports.any { it.error != null }
                    _state.update {
                        it.copy(
                            loading = false, progress = null,
                            channels = merged.channels, vod = merged.vod,
                            reports = merged.reports, updatedAt = merged.updatedAt,
                            error = if (nothing) merged.reports.mapNotNull { r -> r.error }.joinToString(" · ") else null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, progress = null, error = Http.describe(e)) }
                }
            if (refreshRequested) { refreshRequested = false; refresh() }
        }
    }

    /** Une source totalement en échec garde ce qu'elle avait chargé la fois précédente. */
    private fun mergeWithPrevious(fresh: CatalogSnapshot, prev: OnyxUiState): CatalogSnapshot {
        // Fusion PAR TYPE : si seules les séries d'un compte ont échoué, on garde les séries
        // précédentes de ce compte et on prend les chaînes/films frais. Jamais de doublon d'id.
        val partial = fresh.reports.filter { !it.liveOk || !it.vodOk || !it.seriesOk }
        if (partial.isEmpty()) return fresh
        fun prefix(r: SourceReport, kind: String) = if (r.type == "Xtream") "xt:${r.sourceId}:$kind:" else "m3u:${r.sourceId}:"
        val keepChannelPrefixes = partial.filter { !it.liveOk }.map { prefix(it, "live") }
        val keepVodPrefixes = partial.filter { it.type == "Xtream" && !it.vodOk }.map { prefix(it, "vod") } +
            partial.filter { it.type == "Xtream" && !it.seriesOk }.map { prefix(it, "series") }
        val freshChannelIds = fresh.channels.mapTo(HashSet()) { it.id }
        val freshVodIds = fresh.vod.mapTo(HashSet()) { it.id }
        val keptChannels = prev.channels.filter { c -> c.id !in freshChannelIds && keepChannelPrefixes.any { c.id.startsWith(it) } }
        val keptVod = prev.vod.filter { v -> v.id !in freshVodIds && keepVodPrefixes.any { v.id.startsWith(it) } }
        val reports = fresh.reports.map { r ->
            if (r in partial) r.copy(
                channels = if (r.liveOk) r.channels else keptChannels.count { it.id.startsWith(prefix(r, "live")) },
                movies = if (r.vodOk) r.movies else keptVod.count { it.id.startsWith(prefix(r, "vod")) },
                series = if (r.seriesOk) r.series else keptVod.count { it.id.startsWith(prefix(r, "series")) },
                error = (r.error ?: "erreur") + " (données précédentes conservées)",
            ) else r
        }
        return fresh.copy(channels = fresh.channels + keptChannels, vod = fresh.vod + keptVod, reports = reports)
    }

    /** Vide le cache du guide : les programmes sont retéléchargés à l'affichage. */
    fun refreshEpg() {
        repo.clearEpg()
        _state.update { it.copy(epgVersion = it.epgVersion + 1) }
        viewModelScope.launch {
            runCatching { repo.refreshEpgFromNetwork() }
            runCatching { repo.prefetchEpg(_state.value.channels) }
            _state.update { it.copy(epgVersion = it.epgVersion + 1) }
        }
    }

    /** État du guide (programmes, chaînes appariées) pour le bandeau du Guide TV. */
    val epgStatus: StateFlow<OnyxRepository.EpgStatus?> get() = repo.epgStatus

    private val _epgTest = MutableStateFlow<String?>(null)
    /** Résultat du test du guide (Réglages). */
    val epgTest: StateFlow<String?> = _epgTest.asStateFlow()

    fun testEpg() {
        if (_epgTest.value == "Test du guide en cours… (téléchargement complet, patientez)") return
        _epgTest.value = "Test du guide en cours… (téléchargement complet, patientez)"
        viewModelScope.launch {
            _epgTest.value = runCatching { repo.epgReport(_state.value.channels) }.getOrElse { "Échec du test : ${Http.describe(it)}" }
            runCatching { repo.prefetchEpg(_state.value.channels) }
            _state.update { it.copy(epgVersion = it.epgVersion + 1) }
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
                    "Serveur injoignable : ${Http.describe(e)}. " +
                        "Vérifiez l'adresse (http://serveur:port) et votre connexion."
                }
            refresh()
        }
    }

    fun clearSourceStatus() { _sourceStatus.value = null }

    /** Format des flux live d'un compte Xtream : "ts" (MPEG-TS) ou "m3u8" (HLS). */
    fun setLiveExtension(sourceId: String, ext: String) {
        viewModelScope.launch {
            store.update(sourceId) { if (it is PlaylistSource.Xtream) it.copy(liveExtension = ext) else it }
            _sourceStatus.value = "Format des flux live : ${if (ext == "m3u8") "HLS (m3u8)" else "MPEG-TS"} — rechargement…"
            refresh()
        }
    }

    /** Chaîne portant ce numéro (saisie au pavé numérique dans le lecteur). */
    /** Chaînes visibles pour le zapping / numéro : catégories verrouillées (parental) exclues. */
    private fun zapChannels(): List<Channel> {
        val pr = prefs.value
        val hidden = hiddenGroups(parental.value, unlockedGroups.value) + pr.hiddenCategories
        return _state.value.channels.filter { it.groupTitle !in hidden && it.id !in pr.hiddenChannelIds }
    }

    /** Liste des chaînes zappables (superposition « liste des chaînes » du lecteur). */
    fun zapList(): List<Channel> = zapChannels()

    fun channelById(id: String): Channel? = _state.value.channels.firstOrNull { it.id == id }

    // ---- Chaîne précédente (rappel) ----
    private var currentLiveId: String? = null
    private var lastLiveId: String? = null
    fun noteLive(id: String) { if (id != currentLiveId) { lastLiveId = currentLiveId; currentLiveId = id } }
    fun previousChannel(): Channel? = lastLiveId?.let { id -> zapChannels().firstOrNull { it.id == id } }

    // ---- Masquage de chaînes / catégories, ordre des favoris ----
    fun hideChannel(id: String, hidden: Boolean) {
        viewModelScope.launch { userStore.updatePrefs { it.copy(hiddenChannelIds = if (hidden) it.hiddenChannelIds + id else it.hiddenChannelIds - id) } }
    }
    fun hideCategory(name: String, hidden: Boolean) {
        viewModelScope.launch { userStore.updatePrefs { it.copy(hiddenCategories = if (hidden) it.hiddenCategories + name else it.hiddenCategories - name) } }
    }
    fun unhideAll() {
        viewModelScope.launch { userStore.updatePrefs { it.copy(hiddenCategories = emptySet(), hiddenChannelIds = emptySet()) } }
    }
    fun moveFavorite(id: String, delta: Int) { viewModelScope.launch { userStore.moveFavorite(id, delta) } }

    // ---- Rappels et enregistrements programmés (guide) ----
    val reminders: StateFlow<List<ca.onyxtv.player.core.data.Reminder>> =
        userStore.reminders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _dueReminder = MutableStateFlow<ca.onyxtv.player.core.data.Reminder?>(null)
    /** Rappel arrivé à échéance pendant que l'app est ouverte (dialogue « Regarder »). */
    val dueReminder: StateFlow<ca.onyxtv.player.core.data.Reminder?> = _dueReminder.asStateFlow()
    fun dismissDueReminder() { _dueReminder.value = null }
    fun removeReminder(id: String) {
        viewModelScope.launch { userStore.removeReminder(id); ca.onyxtv.player.core.work.ReminderWorker.cancel(getApplication(), id) }
    }

    fun toggleReminder(ch: Channel, p: EpgProgram, record: Boolean) {
        val id = ca.onyxtv.player.core.data.reminderId(ch.id, p.start, record)
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            if (reminders.value.any { it.id == id }) {
                userStore.removeReminder(id)
                ca.onyxtv.player.core.work.ReminderWorker.cancel(ctx, id)
            } else {
                val r = ca.onyxtv.player.core.data.Reminder(id, ch.id, ch.name, p.title, p.start, p.stop, record)
                userStore.addReminder(r)
                ca.onyxtv.player.core.work.ReminderWorker.schedule(ctx, r, ch.url)
            }
        }
    }

    private fun checkReminders() {
        val now = System.currentTimeMillis()
        reminders.value.forEach { r ->
            if (r.record) {
                if (now >= r.start - 30_000L && now < r.stop) {
                    channelById(r.channelId)?.let { ch -> startRecording(ch, ((r.stop - now) / 60_000L).toInt().coerceAtLeast(1)) }
                    viewModelScope.launch { userStore.removeReminder(r.id) }
                } else if (now >= r.stop) viewModelScope.launch { userStore.removeReminder(r.id) }
            } else {
                if (now >= r.start - 2 * 60_000L && now < r.start + 5 * 60_000L) {
                    if (_dueReminder.value == null) _dueReminder.value = r
                    viewModelScope.launch { userStore.removeReminder(r.id) }
                } else if (now >= r.start + 5 * 60_000L) viewModelScope.launch { userStore.removeReminder(r.id) }
            }
        }
    }

    fun channelByNumber(n: Int): Channel? = zapChannels().firstOrNull { it.number == n }

    /** Titre du programme en cours pour une cible de lecture live (bandeau du lecteur). */
    suspend fun nowPlaying(target: PlayTarget): String? {
        val ch = target.id?.let { id -> _state.value.channels.firstOrNull { it.id == id } } ?: return null
        val now = System.currentTimeMillis()
        return runCatching { repo.epg(ch) }.getOrDefault(emptyList()).firstOrNull { it.isLiveAt(now) }?.title
    }

    fun removeSource(id: String) {
        viewModelScope.launch {
            store.remove(id)
            // Retire immédiatement les contenus de cette source, puis recharge le reste.
            _state.update { st ->
                st.copy(
                    channels = st.channels.filterNot { it.id.startsWith("xt:$id:") },
                    vod = st.vod.filterNot { it.id.startsWith("xt:$id:") },
                )
            }
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
                    seriesId = target.seriesId,
                )
            )
        }
    }

    fun removeRecent(id: String) {
        viewModelScope.launch { userStore.removeRecent(id) }
    }

    /** « Marquer comme vu / non vu » un épisode (menu appui long de la fiche série). */
    fun markWatched(ep: ca.onyxtv.player.core.model.Episode, series: VodItem, watched: Boolean) {
        viewModelScope.launch {
            if (!watched) { userStore.removeRecent(ep.id); return@launch }
            val dur = ((ep.durationSecs ?: 3600) * 1000L)
            userStore.recordRecent(
                RecentItem(
                    id = ep.id, title = "S${ep.season}E${ep.number} · ${ep.title}", subtitle = series.name,
                    url = ep.url, imageUrl = ep.imageUrl ?: series.posterUrl, live = false,
                    positionMs = dur, durationMs = dur, seriesId = series.id,
                )
            )
        }
    }

    // ---- Profils ----
    val profiles: StateFlow<List<ca.onyxtv.player.core.data.Profile>> =
        userStore.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(ca.onyxtv.player.core.data.Profile("", "Principal")))
    val activeProfile: StateFlow<String> = userStore.activeProfile.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _profileChosen = MutableStateFlow(false)
    /** Vrai une fois le profil choisi (ou confirmé) dans cette session. */
    val profileChosen: StateFlow<Boolean> = _profileChosen.asStateFlow()
    fun confirmProfile() { _profileChosen.value = true }
    fun setActiveProfile(id: String) {
        viewModelScope.launch {
            userStore.setActiveProfile(id)
            // Nouveau profil = nouveau contrôle parental : on referme ce qui était déverrouillé.
            _unlockedGroups.value = emptySet()
            _appUnlocked.value = false
            _profileChosen.value = true
        }
    }
    fun addProfile(name: String) { viewModelScope.launch { userStore.addProfile(name) } }
    fun removeProfile(id: String) { viewModelScope.launch { userStore.removeProfile(id) } }
    fun setAskProfileAtStart(ask: Boolean) { viewModelScope.launch { userStore.updatePrefs { it.copy(askProfileAtStart = ask) } } }

    /**
     * Cible de lecture d'un récent avec l'URL ACTUELLE du catalogue. L'URL mémorisée au moment
     * de la lecture peut être périmée (extension, serveur, identifiants changés) : rejouer une
     * vieille URL donne un flux noir ou une « vidéo » de quelques minutes renvoyée par le panneau.
     */
    fun freshTarget(r: RecentItem): PlayTarget {
        val st = _state.value
        val base = st.channels.firstOrNull { it.id == r.id }?.toPlayTarget()
            ?: st.vod.firstOrNull { it.id == r.id }?.toPlayTarget()
            ?: return r.toPlayTarget()
        return base.copy(startPositionMs = if (r.resumable) r.positionMs else 0L)
    }

    // ---- Zapping ----
    /** Chaîne voisine (+1/−1) dans l'ordre courant, en boucle. */
    fun neighborChannel(currentId: String?, delta: Int): Channel? {
        val list = zapChannels()
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.id == currentId }
        if (idx < 0) return list.first()
        val n = ((idx + delta) % list.size + list.size) % list.size
        return list[n]
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

    /** Guide (now/next) pour une chaîne donnée. */
    suspend fun epgFor(channel: Channel) = repo.epg(channel)
    suspend fun epgDiag(channel: ca.onyxtv.player.core.model.Channel) = repo.epgDiag(channel)

    /** Fiche d'une série (saisons/épisodes), chargée à la demande. */
    suspend fun seriesDetail(item: VodItem) = repo.seriesDetail(item)

    /** Fiche d'un film (résumé, casting, note, bande-annonce), chargée à la demande. */
    suspend fun movieDetail(item: VodItem) = repo.movieDetail(item)

    private companion object {
        /** Au-delà de cet âge, le catalogue est rafraîchi automatiquement à l'ouverture. */
        const val AUTO_REFRESH_MS = 24 * 3_600_000L
        /** Vérification de mise à jour de l'application : une fois par jour. */
        const val UPDATE_CHECK_MS = 24 * 3_600_000L
    }
}

/** Catégories à masquer tant qu'elles n'ont pas été déverrouillées par le PIN. */
/** Identifiants (chaînes + VOD) appartenant aux groupes masqués : pour filtrer récents, reprise, favoris. */
fun hiddenIds(channels: List<Channel>, vod: List<VodItem>, hidden: Set<String>): Set<String> {
    if (hidden.isEmpty()) return emptySet()
    val out = HashSet<String>()
    channels.forEach { if (it.groupTitle in hidden) out.add(it.id) }
    vod.forEach { if (it.category in hidden) out.add(it.id) }
    return out
}

fun hiddenGroups(parental: ParentalSettings, unlocked: Set<String>): Set<String> =
    if (!parental.enabled) emptySet() else parental.lockedGroups - unlocked

/**
 * Recommandations : heuristique locale basée sur les catégories des favoris et des récents.
 * Sans historique, on privilégie les mieux notés. Les contenus déjà vus sont écartés.
 */
/** Titre normalisé pour l'appariement TMDB <-> catalogue : minuscules, sans accents, sans ponctuation, sans tags [FR]/(2019)/VF. */
fun normTitle(raw: String): String {
    var t = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    t = t.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")           // [FR] / (2019)
    t = t.replace(Regex("\\b(vf|vff|vostfr|vo|multi|fr|en|hd|4k|uhd|fhd|the|le|la|les|l)\\b"), " ")
    return t.replace(Regex("[^a-z0-9]"), "")
}

fun recommendVod(vod: List<VodItem>, favorites: Set<String>, seen: Set<String>, limit: Int = 24): List<VodItem> {
    fun rating(v: VodItem) = v.rating?.toFloatOrNull() ?: 0f
    val likedCats = vod.filter { it.id in favorites || it.id in seen }
        .mapNotNull { it.category }
        .groupingBy { it }.eachCount()
    if (likedCats.isEmpty()) return vod.sortedByDescending(::rating).take(limit)
    return vod.filter { it.id !in seen }
        .sortedWith(compareByDescending<VodItem> { likedCats[it.category] ?: 0 }.thenByDescending(::rating))
        .take(limit)
}
