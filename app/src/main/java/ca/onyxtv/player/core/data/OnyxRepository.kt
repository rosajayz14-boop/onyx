package ca.onyxtv.player.core.data

import ca.onyxtv.player.core.epg.XmltvParser
import ca.onyxtv.player.core.m3u.M3uParser
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.PlaylistSource
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

    /** Contenus VOD (Xtream uniquement pour le MVP ; une M3U mélange souvent tout). */
    suspend fun vod(): List<VodItem> {
        val out = ArrayList<VodItem>()
        store.sources.first().filterIsInstance<PlaylistSource.Xtream>().forEach { source ->
            out += runCatching { xt.vodStreams(source) }.getOrDefault(emptyList())
        }
        return out
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
