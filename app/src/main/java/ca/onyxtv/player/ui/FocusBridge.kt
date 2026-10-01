package ca.onyxtv.player.ui

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

/**
 * Petit relais Activity -> Compose. Il NE consomme AUCUNE touche : avaler les flèches empêchait
 * Android de sortir du mode tactile (touch mode), ce qui bloquait toute la navigation tant qu'on
 * n'avait pas appuyé sur OK. On se contente de noter la dernière touche (diagnostic) et de dire si
 * une vue native (bande-annonce, lecteur) détient le focus.
 */
object FocusBridge {
    /** Vrai si une vue native (WebView bande-annonce, etc.) détient le focus système. */
    @Volatile var nativeViewHasFocus: () -> Boolean = { false }

    /** Diagnostic : dernière touche reçue par l'Activity. */
    val lastKey = mutableStateOf("—")

    fun note(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN)
            lastKey.value = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
    }
}
