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
import kotlinx.coroutines.async
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
    suspend fun loadCatalog(onProgress: (String) -> Unit = {}): CatalogSnapshot = coroutineScope {
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

    /** Vide les caches EPG : le prochain affichage retélécharge le guide. */
    fun clearEpg() {
        synchronized(epgByChannel) { epgByChannel.clear() }
        synchronized(xmltvByUrl) { xmltvByUrl.clear() }
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
                val fromXmltv = channel.epgChannelId?.takeIf { it.isNotBlank() }?.let { epgId ->
                    xmltv(xt.xmltvUrl(src)).filter { it.channelId.equals(epgId, ignoreCase = true) }
                }.orEmpty()
                if (fromXmltv.isNotEmpty()) return@run fromXmltv.sortedBy { it.start }
                return@run runCatching { xt.shortEpg(src, sid, limit = 30) }.getOrDefault(emptyList())
            }
            // M3U : XMLTV téléchargé une fois (cache), filtré sur le tvg-id
            val epgId = channel.epgChannelId ?: return@run emptyList()
            val m3u = sources.filterIsInstance<PlaylistSource.M3u>().firstOrNull { !it.epgUrl.isNullOrBlank() }
                ?: return@run emptyList()
            xmltv(m3u.epgUrl!!).filter { it.channelId.equals(epgId, ignoreCase = true) }.sortedBy { it.start }
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
    private suspend fun xmltv(url: String): List<EpgProgram> {
        val now = System.currentTimeMillis()
        synchronized(xmltvByUrl) { xmltvByUrl[url] }
            ?.takeIf { now - it.at < XMLTV_TTL_MS }
            ?.let { return it.value }
        return xmltvMutex.withLock {
            synchronized(xmltvByUrl) { xmltvByUrl[url] }?.takeIf { now - it.at < XMLTV_TTL_MS }?.let { return@withLock it.value }
            val parsed = runCatching {
                val dir = Http.tempDir
                if (dir != null) {
                    dir.mkdirs()
                    val file = java.io.File(dir, "epg-${url.hashCode()}.xml")
                    try {
                        Http.getToFile(url, file)
                        file.inputStream().buffered().use { XmltvParser.parse(it, now - 6 * 3_600_000L, now + 36 * 3_600_000L) }
                    } finally { file.delete() }
                } else {
                    Http.getBytes(url).inputStream().use { XmltvParser.parse(it, now - 6 * 3_600_000L, now + 36 * 3_600_000L) }
                }
            }.getOrDefault(emptyList())
            synchronized(xmltvByUrl) { xmltvByUrl[url] = Cached(now, parsed) }
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
        return xt.movieInfo(src, streamId)
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
        const val XMLTV_TTL_MS = 6 * 3_600_000L    // fichier XMLTV : 6 h
    }
}
