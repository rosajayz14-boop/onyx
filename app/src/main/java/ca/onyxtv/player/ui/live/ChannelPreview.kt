package ca.onyxtv.player.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.player.buildPlayer
import ca.onyxtv.player.ui.components.Thumbnail
import kotlinx.coroutines.delay

/**
 * Aperçu vidéo MUET de la chaîne sélectionnée (TV en direct), démarré après un court délai
 * pour ne pas ouvrir une connexion à chaque passage de focus. Logo en attendant / en cas d'échec.
 */
@OptIn(UnstableApi::class)
@Composable
fun ChannelPreview(channel: Channel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var armed by remember(channel.id) { mutableStateOf(false) }
    var failed by remember(channel.id) { mutableStateOf(false) }
    var ready by remember(channel.id) { mutableStateOf(false) }
    LaunchedEffect(channel.id) { armed = false; delay(700); armed = true }

    Box(modifier.fillMaxSize()) {
        if (!armed || failed || !ready) {
            Thumbnail(channel.logoUrl, channel.name.take(2).uppercase(), channel.id, Modifier.fillMaxSize())
        }
        if (armed && !failed) {
            val exo = remember(channel.url) {
                buildPlayer(context, false).apply {
                    setMediaItem(MediaItem.fromUri(channel.url))
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    prepare()
                    playWhenReady = true
                }
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(exo, lifecycleOwner) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failed = true }
                    override fun onRenderedFirstFrame() { ready = true }
                }
                val obs = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_STOP -> runCatching { exo.stop() }
                        Lifecycle.Event.ON_START -> runCatching { exo.prepare(); exo.play() }
                        else -> Unit
                    }
                }
                exo.addListener(listener)
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(obs)
                    exo.removeListener(listener)
                    exo.release()
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                update = { if (it.player !== exo) it.player = exo },
                onRelease = { it.player = null },
            )
        }
    }
}
