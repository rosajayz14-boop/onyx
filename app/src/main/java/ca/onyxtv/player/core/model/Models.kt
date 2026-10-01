package ca.onyxtv.player.core.model

import kotlinx.serialization.Serializable

/** Type de média diffusé. */
enum class MediaKind { LIVE, MOVIE, SERIES }

/** Catégorie/regroupement (ex. « Sports », « Ciné »). */
data class Category(
    val id: String,
    val name: String,
    val kind: MediaKind,
)

/** Une chaîne en direct (ou un flux jouable). */
@Serializable
data class Channel(
    val id: String,               // identifiant interne unique
    val streamId: String? = null, // id Xtream si applicable
    val number: Int? = null,      // numéro logique (LCN)
    val name: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val epgChannelId: String? = null, // tvg-id → clé de correspondance EPG
    val url: String,              // URL de flux prête à lire
    val kind: MediaKind = MediaKind.LIVE,
    /** Jours d'archive (catch-up) offerts par le fournisseur, 0 = aucun. */
    val archiveDays: Int = 0,
)

/** Un contenu à la demande (film ou série). Pour une série, [url] est vide et [seriesId] renseigné. */
@Serializable
data class VodItem(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val category: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val url: String,
    val kind: MediaKind = MediaKind.MOVIE,
    val seriesId: String? = null,
    val plot: String? = null,
)

/** Un épisode de série, prêt à lire. */
data class Episode(
    val id: String,               // identifiant interne unique (reprise / récents)
    val title: String,
    val season: Int,
    val number: Int,
    val url: String,
    val plot: String? = null,
    val imageUrl: String? = null,
    val durationSecs: Int? = null,
)

/** Fiche détaillée d'un film (get_vod_info). */
data class MovieDetail(
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    /** Note sur 10 (null si inconnue). */
    val rating: Float? = null,
    val duration: String? = null,
    /** Lien de bande-annonce (YouTube), null si absent. */
    val trailerUrl: String? = null,
    val backdropUrl: String? = null,
) {
    /** Note sur 5 étoiles, arrondie au demi. */
    val stars: Float? get() = rating?.let { (it / 2f * 2).toInt() / 2f }
}

/** Fiche détaillée d'une série : saisons → épisodes. */
data class SeriesDetail(
    val name: String,
    val plot: String? = null,
    val coverUrl: String? = null,
    val seasons: Map<Int, List<Episode>>,
    /** Bande-annonce (YouTube ou lien direct), fournie par la source ou par TMDB. */
    val trailerUrl: String? = null,
) {
    val seasonNumbers: List<Int> get() = seasons.keys.sorted()
    val episodeCount: Int get() = seasons.values.sumOf { it.size }
}

/** Un programme du guide (EPG). Horodatage en millisecondes epoch. */
@Serializable
data class EpgProgram(
    val channelId: String,        // correspond à Channel.epgChannelId
    val title: String,
    val description: String? = null,
    val start: Long,
    val stop: Long,
) {
    val durationMs: Long get() = (stop - start).coerceAtLeast(0)
    fun isLiveAt(nowMs: Long): Boolean = nowMs in start until stop
    /** Progression 0f..1f à l'instant [nowMs]. */
    fun progressAt(nowMs: Long): Float {
        if (durationMs == 0L) return 0f
        return ((nowMs - start).toFloat() / durationMs).coerceIn(0f, 1f)
    }
}
