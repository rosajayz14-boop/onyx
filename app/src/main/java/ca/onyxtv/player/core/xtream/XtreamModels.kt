package ca.onyxtv.player.core.xtream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modèles JSON des réponses Xtream Codes (player_api.php).
 * Tous les champs inconnus sont ignorés (Json { ignoreUnknownKeys = true }).
 *
 * Remarque : selon les panneaux, `stream_id` peut être un entier ou une chaîne ;
 * on le lit en String via un sérialiseur tolérant côté client.
 */

/** Réponse d'authentification (player_api.php sans action). */
@Serializable
data class XtAuthResponse(
    @SerialName("user_info") val userInfo: XtUserInfo? = null,
)

@Serializable
data class XtUserInfo(
    @SerialName("auth") val auth: Int = 0,
    @SerialName("status") val status: String = "",
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
)

@Serializable
data class XtCategory(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @SerialName("parent_id") val parentId: Int = 0,
)

@Serializable
data class XtLiveStream(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("stream_id") val streamId: Long = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("tv_archive") val tvArchive: Int? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: Int? = null,
)

@Serializable
data class XtVodStream(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("stream_id") val streamId: Long = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("year") val year: String? = null,
)

@Serializable
data class XtSeries(
    @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("series_id") val seriesId: Long = 0,
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("rating") val rating: String? = null,
    @SerialName("year") val year: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
)

/**
 * Réponse de get_series_info. `episodes` varie selon les panneaux (objet {"1": [...]} ou
 * tableau de tableaux) : on le garde brut (JsonElement) et on l'interprète côté client.
 */
@Serializable
data class XtSeriesInfo(
    @SerialName("info") val info: XtSeriesInfoBlock? = null,
    @SerialName("episodes") val episodes: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class XtSeriesInfoBlock(
    @SerialName("name") val name: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
)

@Serializable
data class XtShortEpg(
    @SerialName("epg_listings") val listings: List<XtEpgItem> = emptyList(),
)

@Serializable
data class XtEpgItem(
    // title et description sont encodés en Base64 par le panneau
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("start_timestamp") val startTs: Long? = null,
    @SerialName("stop_timestamp") val stopTs: Long? = null,
)
