package ca.onyxtv.player.core.model

/** Type de média diffusé. */
enum class MediaKind { LIVE, MOVIE, SERIES }

/** Catégorie/regroupement (ex. « Sports », « Ciné »). */
data class Category(
    val id: String,
    val name: String,
    val kind: MediaKind,
)

/** Une chaîne en direct (ou un flux jouable). */
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
)

/** Un contenu à la demande (film ou série). */
data class VodItem(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val category: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val url: String,
    val kind: MediaKind = MediaKind.MOVIE,
)

/** Un programme du guide (EPG). Horodatage en millisecondes epoch. */
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
