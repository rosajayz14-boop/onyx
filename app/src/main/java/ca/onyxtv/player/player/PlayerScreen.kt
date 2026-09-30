package ca.onyxtv.player.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Cible de lecture : ce que l'on ouvre en plein écran. */
data class PlayTarget(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    /** Identifiant interne (chaîne/contenu) — sert aux favoris, récents et au zapping. */
    val id: String? = null,
    val imageUrl: String? = null,
    val isLive: Boolean = false,
    /** Position de reprise (ms), 0 = depuis le début. */
    val startPositionMs: Long = 0L,
)

/**
 * Lecteur plein écran basé sur Media3/ExoPlayer.
 * - HLS, DASH et flux progressifs (TS/MP4).
 * - Indicateur de chargement, écran d'erreur avec « Réessayer ».
 * - Reprise à la dernière position et remontée périodique de la progression.
 * - Zapping en direct : ↑/↓ ou CH+/CH− changent de chaîne.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    target: PlayTarget,
    onExit: () -> Unit,
    onProgress: (PlayTarget, Long, Long) -> Unit = { _, _, _ -> },
    /** Renvoie la chaîne voisine (+1 / −1) en mode direct, null si indisponible. */
    zap: ((Int) -> PlayTarget?)? = null,
    onSwitch: (PlayTarget) -> Unit = {},
) {
    val context = LocalContext.current
    val exo = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    val focus = remember { FocusRequester() }

    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(true) }

    val currentTarget by rememberUpdatedState(target)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentZap by rememberUpdatedState(zap)
    val currentOnSwitch by rememberUpdatedState(onSwitch)

    fun doZap(delta: Int): Boolean {
        if (!currentTarget.isLive) return false
        val next = currentZap?.invoke(delta) ?: return false
        currentOnSwitch(next)
        return true
    }

    fun load() {
        error = null
        buffering = true
        exo.setMediaItem(MediaItem.fromUri(target.url))
        exo.prepare()
        if (!target.isLive && target.startPositionMs > 0) exo.seekTo(target.startPositionMs)
        exo.play()
    }

    // Cycle de vie du lecteur : un seul ExoPlayer pour toute la durée de l'écran (zapping inclus).
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) error = null
            }
            override fun onPlayerError(e: PlaybackException) {
                buffering = false
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Flux injoignable — vérifiez votre connexion ou le serveur."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Le serveur a refusé le flux (accès expiré ou chaîne indisponible)."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        "Format de flux non reconnu."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED ->
                        "Ce boîtier ne peut pas décoder ce flux."
                    else -> "Lecture impossible (${e.errorCodeName})."
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // (Re)chargement à chaque changement de cible, avec bandeau d'info temporaire.
    LaunchedEffect(target.url) {
        load()
        showInfo = true
        delay(4_000)
        showInfo = false
    }

    // Remontée périodique de la progression (reprise / récents).
    LaunchedEffect(target.url) {
        while (isActive) {
            delay(5_000)
            val dur = exo.duration
            currentOnProgress(currentTarget, exo.currentPosition, if (dur == C.TIME_UNSET) 0L else dur)
        }
    }
    // Dernière remontée à la sortie (déclaré après le DisposableEffect du lecteur pour s'exécuter avant release()).
    DisposableEffect(target.url) {
        onDispose {
            runCatching {
                val dur = exo.duration
                currentOnProgress(currentTarget, exo.currentPosition, if (dur == C.TIME_UNSET) 0L else dur)
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BackHandler(enabled = true) { onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp -> doZap(+1)
                    Key.DirectionDown, Key.ChannelDown -> doZap(-1)
                    else -> false
                }
            }
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
                    // Repli : CH+/CH− arrivent ici même si Compose ne les intercepte pas.
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_CHANNEL_UP -> doZap(+1)
                            KeyEvent.KEYCODE_CHANNEL_DOWN -> doZap(-1)
                            else -> false
                        }
                    }
                }
            },
            update = { view -> if (view.player !== exo) view.player = exo },
        )

        // Bandeau d'information (titre / catégorie), masqué après quelques secondes.
        AnimatedVisibility(
            visible = showInfo && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 28.dp, vertical = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (target.isLive) Text("● DIRECT", color = OnyxLive, style = MaterialTheme.typography.labelLarge)
                    target.subtitle?.let {
                        Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, color = OnyxCyan)
                    }
                }
                Text(target.title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                if (target.isLive && zap != null) {
                    Text("↑ ↓ pour changer de chaîne", style = MaterialTheme.typography.bodyMedium, color = OnyxMuted)
                }
            }
        }

        if (buffering && error == null) {
            CircularProgressIndicator(color = OnyxCyan, modifier = Modifier.align(Alignment.Center))
        }

        error?.let { msg ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE60B0C14))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Lecture interrompue", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(msg, style = MaterialTheme.typography.bodyLarge, color = OnyxMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { load() }) { Text("Réessayer") }
                    Button(onClick = onExit) { Text("Retour") }
                }
            }
        }
    }
}
