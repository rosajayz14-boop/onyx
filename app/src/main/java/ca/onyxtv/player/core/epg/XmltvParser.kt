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

    /**
     * Parse un XMLTV. [fromMs]/[toMs] limitent les programmes conservés à une fenêtre de temps :
     * indispensable sur les guides de fournisseurs (plusieurs jours × milliers de chaînes).
     */
    fun parse(input: InputStream, fromMs: Long = Long.MIN_VALUE, toMs: Long = Long.MAX_VALUE): List<EpgProgram> {
        val out = ArrayList<EpgProgram>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var inProgramme = false
        var current: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        inProgramme = true
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null; desc = null
                    }
                    "title", "desc" -> current = parser.name
                }
                XmlPullParser.TEXT -> if (inProgramme && current != null) {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (current) {
                        "title" -> title = text
                        "desc" -> desc = text
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title", "desc" -> current = null
                    "programme" -> {
                        val ch = channel
                        if (ch != null && stop > start && stop >= fromMs && start <= toMs) {
                            out += EpgProgram(
                                channelId = ch,
                                title = title ?: "Programme",
                                description = desc,
                                start = start,
                                stop = stop,
                            )
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
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
