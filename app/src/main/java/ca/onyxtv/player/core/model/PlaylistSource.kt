package ca.onyxtv.player.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Source de contenu configurée par l'utilisateur.
 * Polymorphisme fermé (sealed) → sérialisation JSON automatique via kotlinx.serialization.
 */
@Serializable
sealed interface PlaylistSource {
    val id: String
    val label: String

    /** Liste M3U/M3U8 distante (avec EPG XMLTV optionnel). */
    @Serializable
    @SerialName("m3u")
    data class M3u(
        override val id: String,
        override val label: String,
        val url: String,
        val epgUrl: String? = null,
    ) : PlaylistSource

    /** Compte Xtream Codes (panneau player_api.php). */
    @Serializable
    @SerialName("xtream")
    data class Xtream(
        override val id: String,
        override val label: String,
        val server: String,   // ex. http://mon-serveur.tv:8080
        val username: String,
        val password: String,
        /** Extension de flux live : "ts" (MPEG-TS) ou "m3u8" (HLS). */
        val liveExtension: String = "ts",
    ) : PlaylistSource
}
