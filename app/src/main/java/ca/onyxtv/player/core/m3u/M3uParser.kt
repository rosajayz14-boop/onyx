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

    fun parse(content: String, sourceId: String = ""): List<Channel> {
        val out = ArrayList<Channel>()
        var pending: Pending? = null
        val seen = HashMap<String, Int>()

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
                            id = buildId(sourceId, p.tvgId, p.name, line, seen),
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

    /**
     * Identifiant STABLE : source + (tvg-id ou nom) + URL. L'ancien suffixe de position changeait
     * tous les ids dès qu'une ligne était insérée en amont (favoris et reprises perdus) ; et sans
     * la source, deux listes M3U pouvaient produire le même id (clé en double -> plantage).
     */
    private fun buildId(sourceId: String, tvgId: String?, name: String, url: String, seen: HashMap<String, Int>): String {
        val base = (tvgId?.takeIf { it.isNotBlank() } ?: name).lowercase().replace(Regex("[^a-z0-9]"), "")
        val core = "m3u:$sourceId:$base:${Integer.toHexString(url.hashCode())}"
        val n = (seen[core] ?: 0).also { seen[core] = it + 1 }
        return if (n == 0) core else "$core:$n"
    }
}
