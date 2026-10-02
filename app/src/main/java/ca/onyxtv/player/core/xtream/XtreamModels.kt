package ca.onyxtv.player.core.xtream

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Entier « tolérant » : les panneaux Xtream renvoient `num`/`tv_archive`… tantôt en nombre,
 * tantôt en texte (« 12 », « » vide, « 1.0 »). Avec un Int strict, UN seul enregistrement
 * bizarre faisait basculer tout le catalogue sur le décodage lent (arbre JSON de 50 Mo).
 */
object LenientInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.STRING).nullable
    override fun deserialize(decoder: Decoder): Int? {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return runCatching { decoder.decodeInt() }.getOrNull()
        val c = (el as? JsonPrimitive)?.content?.trim() ?: return null
        return c.toIntOrNull() ?: c.toDoubleOrNull()?.toInt()
    }
    override fun serialize(encoder: Encoder, value: Int?) { if (value == null) encoder.encodeNull() else encoder.encodeInt(value) }
}

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
    @Serializable(with = LenientInt::class) @SerialName("auth") val auth: Int? = null,
    @SerialName("status") val status: String = "",
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("active_cons") val activeCons: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
)

@Serializable
data class XtCategory(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @Serializable(with = LenientInt::class) @SerialName("parent_id") val parentId: Int? = null,
)

@Serializable
data class XtLiveStream(
    @Serializable(with = LenientInt::class) @SerialName("num") val num: Int? = null,
    @SerialName("name") val name: String = "",
    @SerialName("stream_id") val streamId: Long = 0,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @Serializable(with = LenientInt::class) @SerialName("tv_archive") val tvArchive: Int? = null,
    @Serializable(with = LenientInt::class) @SerialName("tv_archive_duration") val tvArchiveDuration: Int? = null,
)

@Serializable
data class XtVodStream(
    @Serializable(with = LenientInt::class) @SerialName("num") val num: Int? = null,
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
    @Serializable(with = LenientInt::class) @SerialName("num") val num: Int? = null,
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
    // "info" est un objet sur la plupart des panneaux mais un tableau vide [] sur d'autres :
    // on le garde brut et on le lit de façon tolérante.
    @SerialName("info") val info: kotlinx.serialization.json.JsonElement? = null,
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
