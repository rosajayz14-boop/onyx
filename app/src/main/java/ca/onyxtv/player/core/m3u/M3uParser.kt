package ca.onyxtv.player.core.m3u

import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.MediaKind

/**
 * Parseur M3U/M3U8 étendu.
 *
 * Gère les entrées de la forme :
 *   #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",Nom affiché
 *   http://.../flux.ts
 *
 * Tolère aussi #EXTGRP: et ignore les tags non pertinents pour la lecture (#KODIPROP, etc.).
 */
object M3uParser {

    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    private data class Pending(
        val name: String,
        val tvgId: String?,
        val tvgName: String?,
        val logo: String?,
        val group: String?,
        val number: Int?,
    )

    fun parse(content: String): List<Channel> {
        val out = ArrayList<Channel>()
        var pending: Pending? = null
        var seq = 0

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> Unit
                line.startsWith("#EXTINF") -> pending = parseExtInf(line)
                line.startsWith("#EXTGRP:") -> {
                    pending?.let { pending = it.copy(group = line.substringAfter(':').trim()) }
                }
                line.startsWith("#") -> Unit // autres directives ignorées
                else -> {
                    val p = pending
                    if (p != null) {
                        out += Channel(
                            id = buildId(p.tvgId, p.name, line, seq++),
                            number = p.number,
                            name = p.name,
                            logoUrl = p.logo?.takeIf { it.isNotBlank() },
                            groupTitle = p.group?.takeIf { it.isNotBlank() },
                            epgChannelId = p.tvgId?.takeIf { it.isNotBlank() },
                            url = line,
                            kind = MediaKind.LIVE,
                        )
                        pending = null
                    }
                }
            }
        }
        return out
    }

    private fun parseExtInf(line: String): Pending {
        // Attributs clé="valeur"
        val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        // Nom affiché = texte après la dernière virgule
        val displayName = line.substringAfterLast(',').trim().ifEmpty {
            attrs["tvg-name"] ?: "Sans nom"
        }
        val number = attrs["tvg-chno"]?.toIntOrNull()
        return Pending(
            name = displayName,
            tvgId = attrs["tvg-id"],
            tvgName = attrs["tvg-name"],
            logo = attrs["tvg-logo"],
            group = attrs["group-title"],
            number = number,
        )
    }

    private fun buildId(tvgId: String?, name: String, url: String, seq: Int): String {
        val base = tvgId?.takeIf { it.isNotBlank() } ?: name
        return "m3u:$base:${url.hashCode()}:$seq"
    }
}
