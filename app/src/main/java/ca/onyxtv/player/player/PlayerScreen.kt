package ca.onyxtv.player.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Cible de lecture : ce que l'on ouvre en plein écran. */
data class PlayTarget(
    val url: String,
    val title: String,
    val subtitle: String? = null,
)

/**
 * Lecteur plein écran basé sur Media3/ExoPlayer.
 * Gère HLS, DASH et flux progressifs (TS/MP4). La télécommande pilote les contrôles.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(target: PlayTarget, onExit: () -> Unit) {
    val context = LocalContext.current
    val exo = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    DisposableEffect(target.url) {
        exo.setMediaItem(MediaItem.fromUri(target.url))
        exo.prepare()
        onDispose { exo.release() }
    }

    BackHandler(enabled = true) { onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    controllerShowTimeoutMs = 4000
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
        )

        // Bandeau d'information (masqué automatiquement par le contrôleur natif au besoin)
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(28.dp)
        ) {
            target.subtitle?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                text = target.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
        }
    }
}
