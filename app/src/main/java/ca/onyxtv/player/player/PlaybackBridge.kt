package ca.onyxtv.player.player

/** État de lecture partagé avec l'activité (image dans l'image quand on appuie sur Accueil). */
object PlaybackBridge {
    /** Vrai quand un film/épisode est en lecture : l'activité peut passer en image dans l'image. */
    @Volatile var pipEligible: Boolean = false
}
