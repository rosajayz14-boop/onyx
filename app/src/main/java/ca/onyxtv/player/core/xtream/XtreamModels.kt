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
