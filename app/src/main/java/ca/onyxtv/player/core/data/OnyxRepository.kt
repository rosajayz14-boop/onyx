package ca.onyxtv.player.core.data

import ca.onyxtv.player.core.epg.XmltvParser
import ca.onyxtv.player.core.m3u.M3uParser
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.MovieDetail
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.SeriesDetail
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.core.net.Http
import ca.onyxtv.player.core.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Dépôt central : agrège toutes les sources configurées en listes prêtes pour l'UI.
 * Réunit M3U (parsing local) et Xtream (API), plus l'EPG (avec cache mémoire).
 */
class OnyxRepository(
    val store: PlaylistStore,
    private val xt: XtreamClient = XtreamClient(),
) {

    // ---- Catalogue ----

    /**
     * Charge toutes les sources EN PARALLÈLE et renvoie un instantané complet avec un bilan
     * par source (comptes et erreur éventuelle). Une source en échec n'empêche pas les autres.
     * [onProgress] reçoit des messages d'avancement pour l'UI.
     */
    suspend fun loadCatalog(onProgress: (String) -> Unit = {}): CatalogSnapshot = withContext(Dispatchers.Default) { loadCatalogInner(onProgress) }

    private suspend fun loadCatalogInner(onProgress: (String) -> Unit): CatalogSnapshot = coroutineScope {
        val sources = store.sources.first()
        if (sources.isEmpty()) return@coroutineScope CatalogSnapshot(updatedAt = System.currentTimeMillis())

        val jobs = sources.map { source ->
            async {
                val t0 = System.currentTimeMillis()
                when (source) {
                    is PlaylistSource.M3u -> {
                        onProgress("Liste « ${source.label} »…")
                        runCatching { M3uParser.parse(Http.get(source.url)) }
                            .fold(
                                onSuccess = { ch ->
                                    Triple(ch, emptyList<VodItem>(), SourceReport(source.id, source.label, "M3U", channels = ch.size, durationMs = System.currentTimeMillis() - t0))
                                },
                                onFailure = { e ->
                                    Triple(emptyList<Channel>(), emptyList<VodItem>(), SourceReport(source.id, source.label, "M3U", error = Http.describe(e), durationMs = System.currentTimeMillis() - t0))
                                },
                            )
                    }
                    is PlaylistSource.Xtream -> {
                        onProgress("Compte « ${source.label} » : chaînes…")
                        val live = async { runCatching { xt.liveStreams(source) } }
                        val movies = async { onProgress("Compte « ${source.label} » : films…"); runCatching { xt.vodStreams(source) } }
                        val series = async { onProgress("Compte « ${source.label} » : séries…"); runCatching { xt.series(source) } }
                        val ch = live.await().getOrDefault(emptyList())
                        val mv = movies.await().getOrDefault(emptyList())
                        val sr = series.await().getOrDefault(emptyList())
                        val errors = listOfNotNull(
                            live.await().exceptionOrNull()?.let { "chaînes : ${Http.describe(it)}" },
                            movies.await().exceptionOrNull()?.let { "films : ${Http.describe(it)}" },
                            series.await().exceptionOrNull()?.let { "séries : ${Http.describe(it)}" },
                        )
                        Triple(
                            ch, mv + sr,
                            SourceReport(
                                source.id, source.label, "Xtream",
                                channels = ch.size, movies = mv.size, series = sr.size,
                                error = errors.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                durationMs = System.currentTimeMillis() - t0,
                            ),
                        )
                    }
                }
            }
        }
        val results = jobs.map { it.await() }
        CatalogSnapshot(
            channels = results.flatMap { it.first },
            vod = results.flatMap { it.second },
            reports = results.map { it.third },
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Teste un compte Xtream et renvoie un message d'état lisible. */
    suspend fun probeXtream(src: PlaylistSource.Xtream): String = xt.probe(src)

    // ---- EPG (cache mémoire) ----

    private data class Cached<T>(val at: Long, val value: T)
    private val epgByChannel = HashMap<String, Cached<List<EpgProgram>>>()
    private val xmltvByUrl = HashMap<String, Cached<List<EpgProgram>>>()

    /** Vide les caches EPG (mémoire + disque) : le prochain affichage retélécharge le guide. */
    fun clearEpg() {
        synchronized(epgByChannel) { epgByChannel.clear() }
        synchronized(xmltvByUrl) { xmltvByUrl.clear() }
        runCatching { epgDir()?.listFiles()?.forEach { it.delete() } }
    }

    private fun epgDir(): java.io.File? = Http.dataDir?.resolve("epg")?.apply { mkdirs() }
    private fun epgFile(url: String): java.io.File? = epgDir()?.resolve("epg-${url.hashCode()}.json")
    private val epgJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val epgListSer = kotlinx.serialization.builtins.ListSerializer(EpgProgram.serializer())

    /** Toutes les URL de guide des sources configurées (xmltv.php des comptes + EPG des M3U). */
    private suspend fun epgUrls(): List<String> = store.sources.first().mapNotNull { s ->
        when (s) {
            is PlaylistSource.Xtream -> xt.xmltvUrl(s)
            is PlaylistSource.M3u -> s.epgUrl?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Rafraîchit le guide depuis le réseau pour toutes les sources (appelé par la tâche
     * quotidienne en arrière-plan et par « Mettre à jour le guide »). Résultat sur disque.
     */
    suspend fun refreshEpgFromNetwork() {
        synchronized(epgByChannel) { epgByChannel.clear() }
        epgUrls().forEach { url -> runCatching { xmltv(url, force = true) } }
    }

    /** Guide (now/next) pour une chaîne. Xtream via short_epg ; M3U via XMLTV. Mis en cache. */
    suspend fun epg(channel: Channel): List<EpgProgram> {
        val now = System.currentTimeMillis()
        synchronized(epgByChannel) { epgByChannel[channel.id] }
            ?.takeIf { now - it.at < EPG_TTL_MS }
            ?.let { return it.value }

        val sources = store.sources.first()
        val result: List<EpgProgram> = run {
            // Xtream : 1) guide complet xmltv.php du compte (une seule descente pour toutes les
            // chaînes, plusieurs jours), 2) get_short_epg en secours si la chaîne n'y figure pas.
            channel.streamId?.let { sid ->
                val sourceId = channel.id.split(":").getOrNull(1)
                val src = sources.filterIsInstance<PlaylistSource.Xtream>().firstOrNull { it.id == sourceId }
                    ?: return@run emptyList()
                // 1) get_short_epg : rapide, par chaîne, fiable (now/next + programmes à venir).
                //    C'est ce qui fait apparaître le guide immédiatement.
                val short = runCatching { xt.shortEpg(src, sid, limit = 24) }.getOrDefault(emptyList())
                if (short.isNotEmpty()) return@run short.sortedBy { it.start }
                // 2) Repli : guide complet xmltv.php du compte, filtré sur l'identifiant EPG.
                val fromXmltv = channel.epgChannelId?.takeIf { it.isNotBlank() }?.let { epgId ->
                    val all = runCatching { xmltv(xt.xmltvUrl(src)) }.getOrDefault(emptyList())
                    withContext(Dispatchers.Default) { all.filter { it.channelId.equals(epgId, ignoreCase = true) } }
                }.orEmpty()
                return@run fromXmltv.sortedBy { it.start }
            }
            // M3U : XMLTV téléchargé une fois (cache), filtré sur le tvg-id
            val epgId = channel.epgChannelId ?: return@run emptyList()
            val m3u = sources.filterIsInstance<PlaylistSource.M3u>().firstOrNull { !it.epgUrl.isNullOrBlank() }
                ?: return@run emptyList()
            val all = xmltv(m3u.epgUrl!!)
            withContext(Dispatchers.Default) { all.filter { it.channelId.equals(epgId, ignoreCase = true) }.sortedBy { it.start } }
        }
        synchronized(epgByChannel) { epgByChannel[channel.id] = Cached(now, result) }
        return result
    }

    private val xmltvMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Guide XMLTV téléchargé EN FLUX vers un fichier temporaire puis parsé sur une fenêtre
     * [maintenant − 6 h ; + 36 h] : mémoire maîtrisée même avec des guides de 100 Mo.
     * Un seul téléchargement à la fois par URL (les écrans demandent l'EPG en parallèle).
     */
    private suspend fun xmltv(url: String, force: Boolean = false): List<EpgProgram> {
        val now = System.currentTimeMillis()
        if (!force) {
            synchronized(xmltvByUrl) { xmltvByUrl[url] }
                ?.takeIf { now - it.at < XMLTV_TTL_MS }
                ?.let { return it.value }
        }
        return xmltvMutex.withLock {
            if (!force) {
                synchronized(xmltvByUrl) { xmltvByUrl[url] }?.takeIf { now - it.at < XMLTV_TTL_MS }?.let { return@withLock it.value }
                // Cache disque (rempli par la mise à jour quotidienne) : ouverture instantanée.
                val disk = epgFile(url)
                if (disk != null && disk.exists() && now - disk.lastModified() < XMLTV_TTL_MS) {
                    val fromDisk = runCatching {
                        withContext(Dispatchers.IO) { epgJson.decodeFromString(epgListSer, disk.readText()) }
                    }.getOrNull()
                    if (fromDisk != null) {
                        synchronized(xmltvByUrl) { xmltvByUrl[url] = Cached(disk.lastModified(), fromDisk) }
                        return@withLock fromDisk
                    }
                }
            }
            val parsed = runCatching {
                val dir = Http.tempDir
                if (dir != null) {
                    dir.mkdirs()
                    val file = java.io.File(dir, "epg-${url.hashCode()}.xml")
                    try {
                        Http.getToFile(url, file)
                        withContext(Dispatchers.IO) {
                            file.inputStream().buffered().use { XmltvParser.parse(it, now - 6 * 3_600_000L, now + 48 * 3_600_000L) }
                        }
                    } finally { file.delete() }
                } else {
                    withContext(Dispatchers.IO) {
                        Http.getBytes(url).inputStream().use { XmltvParser.parse(it, now - 6 * 3_600_000L, now + 48 * 3_600_000L) }
                    }
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) {
                synchronized(xmltvByUrl) { xmltvByUrl[url] = Cached(now, parsed) }
                runCatching {
                    withContext(Dispatchers.IO) {
                        epgFile(url)?.writeText(epgJson.encodeToString(epgListSer, parsed))
                    }
                }
            }
            parsed
        }
    }

    // ---- Rattrapage / séries ----

    /**
     * URL de rattrapage d'un programme déjà diffusé (Xtream timeshift), ou null si la chaîne
     * n'offre pas d'archive, si le programme n'est pas terminé ou s'il est trop ancien.
     */
    suspend fun catchupUrl(channel: Channel, program: EpgProgram): String? {
        if (channel.archiveDays <= 0) return null
        val streamId = channel.streamId ?: return null
        val now = System.currentTimeMillis()
        if (program.stop > now) return null
        if (program.start < now - channel.archiveDays * 86_400_000L) return null
        val sourceId = channel.id.split(":").getOrNull(1) ?: return null
        val src = store.sources.first().filterIsInstance<PlaylistSource.Xtream>()
            .firstOrNull { it.id == sourceId } ?: return null
        val minutes = (program.durationMs / 60_000L).toInt().coerceAtLeast(1)
        return xt.timeshiftUrl(src, streamId, program.start, minutes)
    }

    /** Fiche d'un film. Null si la source n'existe plus. id = "xt:<sourceId>:vod:<streamId>" */
    suspend fun movieDetail(item: VodItem): MovieDetail? {
        val parts = item.id.split(":")
        val sourceId = parts.getOrNull(1) ?: return null
        val streamId = parts.getOrNull(3) ?: return null
        val src = store.sources.first().filterIsInstance<PlaylistSource.Xtream>()
            .firstOrNull { it.id == sourceId } ?: return null
        val base = xt.movieInfo(src, streamId)
        if (!base.trailerUrl.isNullOrBlank()) return base
        // Repli bande-annonce : TMDB (par titre + année), si une clé API est configurée.
        val tmdbKey = ca.onyxtv.player.BuildConfig.TMDB_API_KEY
        if (tmdbKey.isNotBlank()) {
            val yt = runCatching {
                ca.onyxtv.player.core.tmdb.TmdbClient.trailerYoutubeId(tmdbKey, item.name, item.year ?: base.releaseDate)
            }.getOrNull()
            if (!yt.isNullOrBlank()) return base.copy(trailerUrl = "https://www.youtube.com/watch?v=$yt")
        }
        return base
    }

    /** Fiche complète d'une série (saisons/épisodes). Null si la source n'existe plus. */
    suspend fun seriesDetail(item: VodItem): SeriesDetail? {
        val seriesId = item.seriesId ?: return null
        // id = "xt:<sourceId>:series:<seriesId>"
        val sourceId = item.id.split(":").getOrNull(1) ?: return null
        val src = store.sources.first().filterIsInstance<PlaylistSource.Xtream>()
            .firstOrNull { it.id == sourceId } ?: return null
        return xt.seriesInfo(src, seriesId, item.name)
    }

    private companion object {
        const val EPG_TTL_MS = 30 * 60_000L        // now/next Xtream : 30 min
        const val XMLTV_TTL_MS = 24 * 3_600_000L   // guide XMLTV : une fois par jour
    }
}
