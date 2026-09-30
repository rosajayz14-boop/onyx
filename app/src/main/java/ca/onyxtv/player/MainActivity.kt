package ca.onyxtv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import ca.onyxtv.player.ui.OnyxRoot
import ca.onyxtv.player.ui.theme.OnyxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            OnyxTheme {
                OnyxRoot()
            }
        }
    }
}
