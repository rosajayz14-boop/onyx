package ca.onyxtv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import ca.onyxtv.player.ui.FocusBridge
import ca.onyxtv.player.ui.OnyxRoot
import ca.onyxtv.player.ui.theme.OnyxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        FocusBridge.nativeViewHasFocus = {
            currentFocus?.let { it.javaClass.simpleName != "AndroidComposeView" } ?: false
        }
        setContent {
            OnyxTheme {
                OnyxRoot()
            }
        }
        forceLeanbackMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) forceLeanbackMode()
    }

    /**
     * Sort du MODE TACTILE. Sur une TV, l'app doit être en mode télécommande (non-tactile) sinon
     * Android refuse de placer la sélection : rien n'est surligné et les flèches ne font rien
     * tant qu'on n'a pas appuyé sur OK (ce qui force la sortie du mode tactile). On force cette
     * sortie dès le départ avec requestFocusFromTouch(), en réessayant le temps que la vue existe.
     */
    private fun forceLeanbackMode() {
        val root = findViewById<View>(android.R.id.content) ?: return
        root.isFocusableInTouchMode = true
        var tries = 0
        val task = object : Runnable {
            override fun run() {
                if (!root.isInTouchMode) return            // déjà en mode télécommande : terminé
                runCatching { root.requestFocusFromTouch() } // quitte le mode tactile
                if (++tries < 25) root.postDelayed(this, 120)
            }
        }
        root.post(task)
    }

    /** On NOTE la touche (diagnostic) mais on ne la consomme JAMAIS : laisser passer les flèches. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        FocusBridge.note(event)
        return super.dispatchKeyEvent(event)
    }
}
