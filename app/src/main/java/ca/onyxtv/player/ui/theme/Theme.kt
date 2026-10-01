package ca.onyxtv.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val OnyxColorScheme = darkColorScheme(
    primary = OnyxViolet,
    onPrimary = Color(0xFF08080F),
    secondary = OnyxCyan,
    onSecondary = Color(0xFF04121A),
    background = OnyxBg,
    onBackground = OnyxText,
    surface = OnyxSurface,
    onSurface = OnyxText,
    surfaceVariant = OnyxSurfaceHi,
    onSurfaceVariant = OnyxMuted,
    border = OnyxCyan,   // bordure de focus bien visible (avant : 12 % de blanc, invisible)
)

/** Thème applicatif ONYX (Compose for TV). */
@Composable
fun OnyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OnyxColorScheme,
        typography = OnyxTypography,
        content = content,
    )
}
