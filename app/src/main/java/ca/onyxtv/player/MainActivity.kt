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

    /** On NOTE la touche (diagnostic) mais on ne la consomme JAMAIS : laisser passer les flèches. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        FocusBridge.note(event)
        return super.dispatchKeyEvent(event)
    }
}
