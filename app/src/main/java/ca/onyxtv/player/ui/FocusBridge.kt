package ca.onyxtv.player.ui

import android.view.KeyEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Pont entre l'Activity (touches de la télécommande) et l'arbre Compose (focus).
 *
 * Corrige un blocage de Compose 1.7 sur TV : la vue racine garde le focus système alors
 * qu'aucun composant n'est sélectionné (page encore en chargement, fiche ou lecteur qui
 * vient de se fermer). Dans cet état, les flèches sont « avalées » par la vue et rien ne
 * bouge à l'écran. L'Activity intercepte alors la touche et replace le focus dans l'écran
 * courant, ce qui rend la sélection visible et la navigation fonctionnelle.
 */
object FocusBridge {
    /** Vrai tant qu'un composant Compose a le focus (mis à jour par l'écran racine). */
    @Volatile var hasFocus: Boolean = false

    /** Replace le focus dans l'écran courant (page, fiche, lecteur, PIN, mise à jour). */
    @Volatile var requestFocus: (() -> Unit)? = null

    /** Vrai si une vue native (bande-annonce YouTube, clavier…) détient le focus système. Fourni par l'Activity. */
    @Volatile var nativeViewHasFocus: () -> Boolean = { false }

    /** Diagnostic : touches récupérées par le pont et dernière touche reçue par l'Activity. */
    val rescued = mutableIntStateOf(0)
    val lastKey = mutableStateOf("—")

    // Seules les flèches servent à récupérer le focus. OK/Enter n'est jamais consommé par le
    // pont : il doit toujours activer l'élément sélectionné.
    private val navKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /**
     * À appeler depuis Activity.dispatchKeyEvent (avant la vue). Renvoie true si la touche a
     * servi à replacer le focus et ne doit pas être transmise plus loin.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        lastKey.value = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        if (hasFocus || event.keyCode !in navKeys || nativeViewHasFocus()) return false
        val request = requestFocus ?: return false
        runCatching { request() }
        rescued.intValue++
        return true
    }
}
