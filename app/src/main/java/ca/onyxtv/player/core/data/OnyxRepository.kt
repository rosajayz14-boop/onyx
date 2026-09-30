package ca.onyxtv.player.core.data

import ca.onyxtv.player.core.epg.XmltvParser
import ca.onyxtv.player.core.m3u.M3uParser
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.SeriesDetail
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.core.net.Http
import ca.onyxtv.player.core.xtream.XtreamClient
import kotlinx.coroutines.flow.first

/**
 * Dépôt central : agrège toutes les sources configurées en listes prêtes pour l'UI.
 * Réunit M3U (parsing local) et Xtream (API), plus l'EPG.
 */
class OnyxRepository(
    val store: PlaylistStore,
    private val xt: XtreamClient = XtreamClient(),
) {

    /** Toutes les chaînes en direct, toutes sources confondues. */
    suspend fun channels(): List<Channel> {
        val out = ArrayList<Channel>()
        store.sources.first().forEach { source ->
            when (source) {
                is PlaylistSource.M3u -> out += runCatching {
                    M3uParser.parse(Http.get(source.url))
                }.getOrDefault(emptyList())

                is PlaylistSource.Xtream -> out += runCatching {
                    xt.liveStreams(source)
                }.getOrDefault(emptyList())
            }
        }
        return out
    }

    /** Teste un compte Xtream et renvoie un message d'état lisible. */
    suspend fun probeXtream(src: PlaylistSource.Xtream): String = xt.probe(src)

    /** Contenus VOD : films + séries des comptes Xtream (une M3U mélange souvent tout). */
    suspend fun vod(): List<VodItem> {
        val out = ArrayList<VodItem>()
        store.sources.first().filterIsInstance<PlaylistSource.Xtream>().forEach { source ->
            out += runCatching { xt.vodStreams(source) }.getOrDefault(emptyList())
            out += runCatching { xt.series(source) }.getOrDefault(emptyList())
        }
        return out
    }

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

    /** Fiche complète d'une série (saisons/épisodes). Null si la source n'existe plus. */
    suspend fun seriesDetail(item: VodItem): SeriesDetail? {
        val seriesId = item.seriesId ?: return null
        // id = "xt:<sourceId>:series:<seriesId>"
        val sourceId = item.id.split(":").getOrNull(1) ?: return null
        val src = store.sources.first().filterIsInstance<PlaylistSource.Xtream>()
            .firstOrNull { it.id == sourceId } ?: return null
        return xt.seriesInfo(src, seriesId, item.name)
    }

    /** Guide (now/next) pour une chaîne. Xtream via short_epg ; M3U via XMLTV. */
    suspend fun epg(channel: Channel): List<EpgProgram> {
        val sources = store.sources.first()

        // Xtream : EPG court par stream_id
        channel.streamId?.let { sid ->
            sources.filterIsInstance<PlaylistSource.Xtream>().firstOrNull()?.let { src ->
                return runCatching { xt.shortEpg(src, sid) }.getOrDefault(emptyList())
            }
        }

        // M3U : télécharger et filtrer le XMLTV sur le tvg-id
        val epgId = channel.epgChannelId ?: return emptyList()
        val m3u = sources.filterIsInstance<PlaylistSource.M3u>().firstOrNull { !it.epgUrl.isNullOrBlank() }
            ?: return emptyList()
        return runCatching {
            Http.getBytes(m3u.epgUrl!!).inputStream().use { XmltvParser.parse(it) }
                .filter { it.channelId == epgId }
        }.getOrDefault(emptyList())
    }
}
