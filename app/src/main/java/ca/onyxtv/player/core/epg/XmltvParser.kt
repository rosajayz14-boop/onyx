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
    /** Diagnostic du dernier parse : erreur, balises vues, premier horodatage brut. */
    @Volatile var lastError: String? = null
    @Volatile var lastProgrammeTags: Int = 0
    @Volatile var lastDropped: Int = 0
    @Volatile var lastFirstStartRaw: String? = null
    @Volatile var lastLastStartRaw: String? = null
    @Volatile var lastUnparsable: Int = 0
    @Volatile var lastTooOld: Int = 0
    @Volatile var lastTooFar: Int = 0
    @Volatile var lastFiller: Int = 0
    @Volatile var lastMinStart: Long = Long.MAX_VALUE
    @Volatile var lastMaxStop: Long = 0L


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

        lastError = null; lastProgrammeTags = 0; lastDropped = 0; lastFirstStartRaw = null; lastLastStartRaw = null
        lastUnparsable = 0; lastTooOld = 0; lastTooFar = 0; lastFiller = 0; lastMinStart = Long.MAX_VALUE; lastMaxStop = 0L
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
                        lastProgrammeTags++
                        channel = parser.getAttributeValue(null, "channel")
                        if (lastFirstStartRaw == null) lastFirstStartRaw = parser.getAttributeValue(null, "start")
                        lastLastStartRaw = parser.getAttributeValue(null, "start")
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
                        if (start > 0L) { if (start < lastMinStart) lastMinStart = start; if (stop > lastMaxStop) lastMaxStop = stop }
                        when {
                            ch == null || start <= 0L -> { lastDropped++; lastUnparsable++ }
                            start > toMs -> { lastDropped++; lastTooFar++ }
                            stop > start && stop < fromMs -> { lastDropped++; lastTooOld++ }
                        }
                        // « Programme » sans titre de plus de 12 h : remplissage du panneau, pas une émission.
                        // Gardé dans le guide, il masquerait les vraies émissions d'un autre guide et
                        // ferait croire à une chaîne « appariée ».
                        val filler = title == null && stop > start && stop - start > 12 * 3_600_000L
                        if (filler) { lastDropped++; lastFiller++ }
                        if (ch != null && start > 0 && start <= toMs && !filler) {
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
            lastError = (e.javaClass.simpleName + ": " + e.message).take(200)
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
