package ca.onyxtv.player.core.xtream

import android.util.Base64
import ca.onyxtv.player.core.model.Category
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.core.net.Http
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Client de l'API Xtream Codes (player_api.php).
 * Construit aussi les URLs de flux live/VOD.
 */
class XtreamClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true          // tolère stream_id renvoyé en chaîne
        coerceInputValues = true
    },
) {

    /**
     * Base normalisée du serveur : ajoute http:// si le schéma manque (erreur de saisie
     * la plus fréquente) et retire le / final. Ex. "mon.tv:8080/" -> "http://mon.tv:8080".
     */
    private fun base(src: PlaylistSource.Xtream): String {
        var s = src.server.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return s
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun api(src: PlaylistSource.Xtream, action: String, extra: String = ""): String =
        "${base(src)}/player_api.php?username=${enc(src.username)}&password=${enc(src.password)}&action=$action$extra"

    /** URL d'authentification (sans action) : renvoie user_info / server_info. */
    private fun authUrl(src: PlaylistSource.Xtream): String =
        "${base(src)}/player_api.php?username=${enc(src.username)}&password=${enc(src.password)}"

    /**
     * Teste le compte et renvoie un message clair pour l'UI.
     * Lève une exception réseau (IOException) si le serveur est injoignable.
     */
    suspend fun probe(src: PlaylistSource.Xtream): String {
        val body = Http.get(authUrl(src))
        val info = runCatching { json.decodeFromString<XtAuthResponse>(body).userInfo }.getOrNull()
            ?: return "Réponse inattendue du serveur (vérifiez l'URL du serveur)."
        return when {
            info.auth == 0 || info.status.equals("Disabled", true) ->
                "Identifiants refusés par le serveur (auth=${info.auth}, statut « ${info.status} »)."
            info.status.isNotBlank() && !info.status.equals("Active", true) ->
                "Compte « ${info.status} » — vérifiez la validité de l'abonnement."
            else -> {
                val exp = info.expDate?.toLongOrNull()?.let { java.text.SimpleDateFormat("dd/MM/yyyy").format(java.util.Date(it * 1000)) }
                "Connecté ✓" + (exp?.let { " — expire le $it" } ?: "")
            }
        }
    }

    fun liveUrl(src: PlaylistSource.Xtream, streamId: Long): String =
        "${base(src)}/live/${enc(src.username)}/${enc(src.password)}/$streamId.${src.liveExtension}"

    fun movieUrl(src: PlaylistSource.Xtream, streamId: Long, ext: String?): String =
        "${base(src)}/movie/${enc(src.username)}/${enc(src.password)}/$streamId.${ext ?: "mp4"}"

    suspend fun liveCategories(src: PlaylistSource.Xtream): List<Category> =
        getList<XtCategory>(api(src, "get_live_categories"))
            .map { Category(it.categoryId, it.categoryName, MediaKind.LIVE) }

    suspend fun vodCategories(src: PlaylistSource.Xtream): List<Category> =
        getList<XtCategory>(api(src, "get_vod_categories"))
            .map { Category(it.categoryId, it.categoryName, MediaKind.MOVIE) }

    /** Table id → nom de catégorie (vide si l'appel échoue : on retombe sur l'id). */
    private suspend fun categoryNames(fetch: suspend () -> List<Category>): Map<String, String> =
        runCatching { fetch() }.getOrDefault(emptyList()).associate { it.id to it.name }

    suspend fun liveStreams(src: PlaylistSource.Xtream, categoryId: String? = null): List<Channel> {
        val extra = categoryId?.let { "&category_id=$it" }.orEmpty()
        val names = categoryNames { liveCategories(src) }
        return getList<XtLiveStream>(api(src, "get_live_streams", extra)).map { s ->
            Channel(
                id = "xt:${src.id}:live:${s.streamId}",
                streamId = s.streamId.toString(),
                number = s.num,
                name = s.name,
                logoUrl = s.streamIcon,
                groupTitle = s.categoryId?.let { names[it] ?: it },
                epgChannelId = s.epgChannelId,
                url = liveUrl(src, s.streamId),
                kind = MediaKind.LIVE,
            )
        }
    }

    suspend fun vodStreams(src: PlaylistSource.Xtream, categoryId: String? = null): List<VodItem> {
        val extra = categoryId?.let { "&category_id=$it" }.orEmpty()
        val names = categoryNames { vodCategories(src) }
        return getList<XtVodStream>(api(src, "get_vod_streams", extra)).map { v ->
            VodItem(
                id = "xt:${src.id}:vod:${v.streamId}",
                name = v.name,
                posterUrl = v.streamIcon,
                category = v.categoryId?.let { names[it] ?: it },
                year = v.year,
                rating = v.rating,
                url = movieUrl(src, v.streamId, v.containerExtension),
                kind = MediaKind.MOVIE,
            )
        }
    }

    /** EPG court (now/next…) pour une chaîne. Les titres/description sont en Base64. */
    suspend fun shortEpg(src: PlaylistSource.Xtream, streamId: String, limit: Int = 8): List<EpgProgram> {
        val r = getOne<XtShortEpg>(api(src, "get_short_epg", "&stream_id=$streamId&limit=$limit"))
        return r.listings.mapNotNull { item ->
            val start = item.startTs?.times(1000) ?: return@mapNotNull null
            val stop = item.stopTs?.times(1000) ?: return@mapNotNull null
            EpgProgram(
                channelId = streamId,
                title = decodeB64(item.title).ifBlank { "Programme" },
                description = decodeB64(item.description).ifBlank { null },
                start = start,
                stop = stop,
            )
        }
    }

    private fun decodeB64(s: String): String = runCatching {
        String(Base64.decode(s, Base64.DEFAULT))
    }.getOrDefault(s)

    private suspend inline fun <reified T> getList(url: String): List<T> {
        val body = Http.get(url)
        return runCatching { json.decodeFromString<List<T>>(body) }.getOrDefault(emptyList())
    }

    private suspend inline fun <reified T> getOne(url: String): T =
        json.decodeFromString(Http.get(url))
}
