package ca.onyxtv.player.core.epg

import android.util.Xml
import ca.onyxtv.player.core.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parseur XMLTV (format standard des guides pour les listes M3U).
 * Lit les balises <programme start="..." stop="..." channel="..."> avec <title>/<desc>.
 *
 * Format d'horodatage XMLTV : "yyyyMMddHHmmss Z" (ex. 20260929180000 -0400).
 */
object XmltvParser {

    private val TIME_FMT = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val TIME_FMT_NO_TZ = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    /**
     * Parse un XMLTV. [fromMs]/[toMs] limitent les programmes conservés à une fenêtre de temps :
     * indispensable sur les guides de fournisseurs (plusieurs jours × milliers de chaînes).
     */
    fun parse(input: InputStream, fromMs: Long = Long.MIN_VALUE, toMs: Long = Long.MAX_VALUE): List<EpgProgram> {
        val out = ArrayList<EpgProgram>()
        // Guides .xml.gz (très courant pour les EPG de listes M3U) : détection par la signature gzip.
        val buffered = java.io.BufferedInputStream(input, 64 * 1024).apply { mark(2) }
        val b1 = buffered.read(); val b2 = buffered.read()
        buffered.reset()
        val src: InputStream = if (b1 == 0x1f && b2 == 0x8b) java.util.zip.GZIPInputStream(buffered) else buffered
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(src, null)

        var event = parser.eventType
        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var titleLang: String? = null
        var desc: String? = null
        val open = ArrayList<EpgProgram>()   // programmes sans <stop> : clôturés par le suivant
        var inProgramme = false
        var current: String? = null

        try { while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        inProgramme = true
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null; desc = null
                    }
                    "title", "desc" -> {
                        current = parser.name
                        if (parser.name == "title") titleLang = parser.getAttributeValue(null, "lang")?.lowercase()
                    }
                }
                XmlPullParser.TEXT -> if (inProgramme && current != null) {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (current) {
                        // Guides multilingues : on garde le premier titre, sauf si une version « fr » arrive.
                        "title" -> if (title == null || titleLang?.startsWith("fr") == true) title = text
                        "desc" -> desc = text
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title", "desc" -> current = null
                    "programme" -> {
                        val ch = channel
                        if (ch != null && start > 0 && start <= toMs) {
                            if (stop > start) {
                                if (stop >= fromMs) out += EpgProgram(channelId = ch, title = title ?: "Programme", description = desc, start = start, stop = stop)
                            } else if (start >= fromMs - 6 * 3_600_000L) {
                                // <stop> absent (autorisé par XMLTV) : fin = début du programme suivant.
                                open += EpgProgram(channelId = ch, title = title ?: "Programme", description = desc, start = start, stop = 0L)
                            }
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
        } } catch (e: Exception) {
            // Guide tronqué ou balise mal formée : on garde les programmes déjà lus.
        }
        if (open.isNotEmpty()) {
            val startsByChannel = (out + open).groupBy({ it.channelId }, { it.start }).mapValues { it.value.sorted() }
            open.forEach { p ->
                val starts = startsByChannel[p.channelId].orEmpty()
                val idx = starts.binarySearch(p.start).let { if (it < 0) -it - 1 else it + 1 }
                val next = starts.getOrNull(idx) ?: (p.start + 60 * 60_000L)
                if (next > p.start && next >= fromMs) out += p.copy(stop = next)
            }
        }
        return out
    }

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val v = raw.trim()
        return runCatching { TIME_FMT.parse(v)?.time }.getOrNull()
            ?: runCatching { TIME_FMT_NO_TZ.parse(v.take(14))?.time }.getOrNull()
            ?: 0L
    }
}
