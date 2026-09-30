package ca.onyxtv.player

import android.os.Bundle
import android.view.KeyEvent
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
    }

    /**
     * Touches de la télécommande : si aucun composant n'a le focus (état où Compose 1.7 avale
     * les flèches), la touche sert à replacer le focus dans l'écran courant. Une vue native
     * (bande-annonce YouTube, lecteur) qui a le focus garde ses touches.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (FocusBridge.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
