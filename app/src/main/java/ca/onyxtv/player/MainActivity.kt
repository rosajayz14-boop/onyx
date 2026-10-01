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
        // Sortie du MODE TACTILE, UNE SEULE FOIS, après la première composition. En mode tactile
        // Android refuse de poser une sélection : après un Retour, le focus est nul et les flèches
        // n'ont rien à déplacer (il faut OK). requestFocusFromTouch() quitte le mode tactile pour
        // toute la session (une TV n'a pas d'écran tactile, donc il n'y revient pas).
        // UN seul appel différé, sans boucle ni onWindowFocusChanged : c'est la boucle répétée du
        // build 44 qui avait gelé l'app.
        exitTouchModeBounded()
    }

    /**
     * Sortie du MODE TACTILE, bornée (au plus ~12 tentatives) et sûre : un seul point d'entrée,
     * aucune ré-entrée via onWindowFocusChanged (c'est la boucle + ré-entrée du build 44 qui gelait).
     * S'arrête dès que le mode tactile est quitté.
     */
    private var leanbackTries = 0
    private fun exitTouchModeBounded() {
        val content = findViewById<View>(android.R.id.content) ?: return
        content.post(object : Runnable {
            override fun run() {
                if (!content.isInTouchMode) return
                runCatching { content.requestFocusFromTouch() }
                if (!content.isInTouchMode) return
                if (++leanbackTries < 12) content.postDelayed(this, 150)
            }
        })
    }

    /** On NOTE la touche (diagnostic) mais on ne la consomme JAMAIS : laisser passer les flèches. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        FocusBridge.note(event)
        return super.dispatchKeyEvent(event)
    }
}
