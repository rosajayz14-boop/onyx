package ca.onyxtv.player.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sondage rapide d'un flux VOD AVANT la lecture : on suit les redirections, on lit les premiers
 * octets et on en déduit ce que le serveur renvoie VRAIMENT (vidéo, playlist HLS, page HTML
 * d'erreur, refus 403…). Sans cela ExoPlayer se contente d'un écran noir ou d'un code obscur.
 */
object StreamProbe {

    enum class Kind { HLS, MP4, MKV, TS, AVI, HTML, UNKNOWN }

    data class Result(
        val finalUrl: String,
        val code: Int,
        val contentType: String?,
        val length: Long?,
        val acceptRanges: Boolean,
        val kind: Kind,
    ) {
        /** Résumé lisible : « HTTP 206 video/x-matroska · 1,8 Go · plages OK · MKV ». */
        fun summary(): String = buildString {
            append("HTTP ").append(code)
            contentType?.let { append(' ').append(it.substringBefore(';').trim()) }
            length?.let { append(" · ").append(fmtSize(it)) }
            append(" · plages ").append(if (acceptRanges) "OK" else "NON")
            append(" · ").append(kind.name)
            if (finalUrl.startsWith("https://")) append(" · https")
        }
    }

    private val probeClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun probe(url: String): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "ONYX-TV/1.0 (Android TV)")
                .header("Range", "bytes=0-4095")
                .header("Accept-Encoding", "identity")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val head = ByteArray(4096)
                var n = 0
                resp.body?.byteStream()?.let { input ->
                    runCatching {
                        while (n < head.size) {
                            val r = input.read(head, n, head.size - n)
                            if (r < 0) break
                            n += r
                            if (n >= 512) break   // suffisant pour reconnaître le conteneur
                        }
                    }
                }
                val ct = resp.header("Content-Type")
                val total = resp.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()?.takeIf { resp.code == 200 }
                val ranges = resp.code == 206 || resp.header("Accept-Ranges")?.contains("bytes") == true
                Result(
                    finalUrl = resp.request.url.toString(),
                    code = resp.code,
                    contentType = ct,
                    length = total,
                    acceptRanges = ranges,
                    kind = sniff(head, n, ct, resp.request.url.encodedPath),
                )
            }
        }.getOrNull()
    }

    private fun sniff(b: ByteArray, n: Int, contentType: String?, path: String): Kind {
        val text = String(b, 0, minOf(n, 256), Charsets.ISO_8859_1)
        val ct = contentType?.lowercase().orEmpty()
        return when {
            text.startsWith("#EXTM3U") || ct.contains("mpegurl") || path.endsWith(".m3u8", true) -> Kind.HLS
            n >= 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Kind.MP4
            n >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> Kind.MKV
            n >= 12 && text.startsWith("RIFF") && text.substring(8, 12) == "AVI " -> Kind.AVI
            n >= 189 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte() -> Kind.TS
            text.trimStart().startsWith("<") || ct.contains("html") || ct.contains("text/") || ct.contains("json") -> Kind.HTML
            else -> Kind.UNKNOWN
        }
    }

    fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.FRANCE, "%.1f Go", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format(java.util.Locale.FRANCE, "%.0f Mo", bytes / (1024.0 * 1024))
        else -> "$bytes o"
    }
}
