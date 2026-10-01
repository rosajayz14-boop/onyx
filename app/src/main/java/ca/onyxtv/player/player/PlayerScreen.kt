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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
import kotlinx.coroutines.launch

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
    /** Contenu à enchaîner automatiquement à la fin (épisode suivant), null sinon. */
    val next: PlayTarget? = null,
)

private fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Une piste (audio ou sous-titre) sélectionnable. */
private data class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> =
    tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            val parts = listOfNotNull(
                f.label,
                f.language?.uppercase(),
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) "${f.channelCount} canaux" else null,
            ).distinct()
            TrackOption(g, i, parts.ifEmpty { listOf("Piste ${i + 1}") }.joinToString(" · "), g.isTrackSelected(i))
        }
    }

private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/**
 * Lecteur plein écran basé sur Media3/ExoPlayer.
 * - HLS, DASH et flux progressifs (TS/MP4).
 * - Indicateur de chargement, écran d'erreur avec « Réessayer ».
 * - Reprise à la dernière position et remontée périodique de la progression.
 * - Zapping en direct : ↑/↓ ou CH+/CH− ; saisie d'un numéro de chaîne au pavé numérique.
 * - Touche Menu (≡) : pistes audio, sous-titres, format d'image.
 * - Bandeau avec le programme en cours (EPG) pour le direct.
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
    /** Renvoie la chaîne portant ce numéro (saisie au pavé numérique). */
    zapToNumber: ((Int) -> PlayTarget?)? = null,
    /** Titre du programme en cours (EPG) pour le direct. */
    nowPlaying: (suspend (PlayTarget) -> String?)? = null,
    seekBackSeconds: Int = 10,
    seekForwardSeconds: Int = 30,
) {
    val context = LocalContext.current
    val exo = remember {
        // Les serveurs Xtream redirigent souvent http -> https : refusé par défaut (=> erreur de lecture).
        // L'UA « ExoPlayerLib » est bloqué par certains panneaux. Décodeur logiciel de repli.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("ONYX-TV/1.0 (Android TV)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(
            context,
            androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true),
        )
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, http))
                    .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6)),
            )
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build().apply { playWhenReady = true }
    }
    val focus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(true) }
    var epgLine by remember { mutableStateOf<String?>(null) }
    var panelOpen by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var digits by remember { mutableStateOf("") }
    var ended by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var posMs by remember { mutableStateOf(0L) }
    var durMs by remember { mutableStateOf(0L) }
    var seekNote by remember { mutableStateOf<String?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var liveRetries by remember { mutableIntStateOf(0) }
    val overlayFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Fenêtre « générique » (3 dernières minutes) pour la VOD : ▲ lance l'épisode suivant.
    val inCredits = !target.isLive && durMs > 0 && target.next != null && durMs - posMs in 1..(3 * 60_000L)

    val currentTarget by rememberUpdatedState(target)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentZap by rememberUpdatedState(zap)
    val currentZapToNumber by rememberUpdatedState(zapToNumber)
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

    fun seekBy(deltaMs: Long) {
        if (currentTarget.isLive || !exo.isCurrentMediaItemSeekable) return
        val dur = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        val to = (exo.currentPosition + deltaMs).coerceIn(0L, dur)
        exo.seekTo(to)
        posMs = to
        seekNote = (if (deltaMs >= 0) "⏩ +" else "⏪ −") + "${kotlin.math.abs(deltaMs) / 1000} s   ${fmtClock(to)} / ${fmtClock(dur)}"
    }

    // Remontée de progression. Pour la VOD, on ignore une durée inconnue (sortie rapide, flux en
    // échec) : sinon on écrirait « durée 0 » et on EFFACERAIT le point de reprise existant.
    fun report(t: PlayTarget) {
        val dur = exo.duration
        if (!t.isLive && (dur == C.TIME_UNSET || dur <= 0L || exo.playbackState == Player.STATE_IDLE)) return
        currentOnProgress(t, exo.currentPosition, if (dur == C.TIME_UNSET) 0L else dur)
    }

    fun skipCredits(): Boolean {
        val next = currentTarget.next ?: return false
        currentOnSwitch(next)
        return true
    }

    fun selectTrack(o: TrackOption, type: Int) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
            .build()
    }

    fun disableSubtitles() {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // Cycle de vie du lecteur : un seul ExoPlayer pour toute la durée de l'écran (zapping inclus).
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            // Direct : un fournisseur coupe souvent la connexion ; on se reconnecte (jusqu'à 5 fois)
            // au lieu de figer l'image ou d'afficher une erreur.
            fun reconnectLive(): Boolean {
                if (liveRetries >= 5) return false
                liveRetries++
                buffering = true
                scope.launch {
                    delay(1_000L * liveRetries)
                    runCatching { exo.seekToDefaultPosition(); exo.prepare(); exo.play() }
                }
                return true
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    if (currentTarget.isLive) reconnectLive() else ended = true
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { error = null; liveRetries = 0 }
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) {
                // HLS direct : décroché de la fenêtre live -> on se recale sans erreur.
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    runCatching { exo.seekToDefaultPosition(); exo.prepare() }
                    return
                }
                // Direct : erreur réseau transitoire -> reconnexion silencieuse.
                if (currentTarget.isLive && e.errorCode in 2000..2999 && reconnectLive()) return
                buffering = false
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Flux injoignable — vérifiez votre connexion ou le serveur."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Le serveur a refusé le flux (accès expiré ou chaîne indisponible)."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "Flux introuvable sur le serveur (chaîne retirée ou adresse changée)."
                    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                        "Le serveur n'a pas renvoyé un flux vidéo (abonnement expiré ?)."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        "Format de flux non reconnu (essayez l'autre format TS/HLS dans Réglages)."
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

    // Quitter l'app (Accueil, veille) : position sauvegardée et lecture mise en pause ;
    // retour dans l'app : reprise (retour au direct pour une chaîne).
    val lifecycleOwner = LocalLifecycleOwner.current
    var wasPlaying by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner, exo) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    runCatching { report(currentTarget) }
                    wasPlaying = exo.playWhenReady
                    exo.stop()   // libère décodeur + connexion fournisseur (position conservée)
                }
                Lifecycle.Event.ON_START -> {
                    runCatching { exo.prepare() }
                    if (currentTarget.isLive) { exo.seekToDefaultPosition(); exo.play() }
                    else if (wasPlaying) exo.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // (Re)chargement à chaque changement de cible, avec bandeau d'info temporaire.
    LaunchedEffect(target.url) {
        ended = false
        load()
        panelOpen = false
        showInfo = true
        delay(5_000)
        showInfo = false
    }

    // Fin de lecture : épisode suivant après un compte à rebours, sinon retour.
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        val next = currentTarget.next
        if (next == null) { delay(2_500); onExit(); return@LaunchedEffect }
        for (i in 10 downTo 1) { countdown = i; delay(1_000) }
        currentOnSwitch(next)
    }

    // Programme en cours (EPG) pour le direct.
    LaunchedEffect(target.url) {
        epgLine = null
        if (target.isLive) epgLine = runCatching { nowPlaying?.invoke(target) }.getOrNull()
    }

    // Saisie d'un numéro de chaîne : on zappe 1,5 s après le dernier chiffre.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = digits.toIntOrNull()
        digits = ""
        if (n != null) currentZapToNumber?.invoke(n)?.let(currentOnSwitch)
    }

    // Position courante (fenêtres intro / générique, affichage).
    LaunchedEffect(target.url) {
        while (isActive) {
            posMs = exo.currentPosition
            durMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(1_000)
        }
    }
    LaunchedEffect(seekNote) { if (seekNote != null) { delay(1_800); seekNote = null } }

    LaunchedEffect(target.url) {
        while (isActive) {
            delay(5_000)
            runCatching { report(target) }
        }
    }
    // Dernière remontée à la sortie : on CAPTURE la cible de cet effet. Avec currentTarget, au
    // zapping ou à l'épisode suivant, la position de l'ANCIEN élément était enregistrée sous
    // l'id du NOUVEAU (épisode suivant marqué « vu », reprise au mauvais endroit).
    DisposableEffect(target.url) {
        val captured = target
        onDispose { runCatching { report(captured) } }
    }

    LaunchedEffect(Unit) { repeat(10) { runCatching { focus.requestFocus() }; kotlinx.coroutines.delay(100) } }
    LaunchedEffect(panelOpen) { if (panelOpen) runCatching { panelFocus.requestFocus() } else runCatching { focus.requestFocus() } }
    LaunchedEffect(error, ended) {
        delay(80)
        if (error != null || ended) runCatching { overlayFocus.requestFocus() } else runCatching { focus.requestFocus() }
    }

    BackHandler(enabled = true) { if (panelOpen) panelOpen = false else onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                // OK / centre : appui COURT = pause ↔ lecture ; appui LONG = panneau (sous-titres,
                // audio, format). Beaucoup de télécommandes n'ont pas de touche Menu dédiée.
                // Retour avec le panneau ouvert : fermer le panneau (sinon Compose « sort » du bouton
                // vers la Box focalisable, consomme la touche, et les flèches sont mortes jusqu'à OK).
                if (ev.key == Key.Back && panelOpen) {
                    if (ev.type == KeyEventType.KeyUp) panelOpen = false
                    return@onPreviewKeyEvent true
                }
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                // Écran d'erreur ou de fin : les boutons (Réessayer / Lire maintenant / Retour) doivent
                // recevoir OK et les flèches ; on laisse donc tout passer aux enfants.
                if (error != null || ended) return@onPreviewKeyEvent false
                if (isOk && !panelOpen) {
                    when {
                        ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.isLongPress -> panelOpen = true
                        ev.type == KeyEventType.KeyUp && !ev.nativeKeyEvent.isCanceled -> {
                            if (exo.isPlaying) exo.pause() else exo.play()
                            runCatching { playerView?.showController() }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (panelOpen) {
                    return@onPreviewKeyEvent if (ev.key == Key.Menu) { panelOpen = false; true } else false
                }
                val digit = DIGIT_KEYS[ev.key]
                when {
                    ev.key == Key.Menu -> { panelOpen = true; true }
                    // VOD : ▲ passe l'intro/générique si on y est, sinon ouvre le panneau (pistes/format).
                    !currentTarget.isLive && ev.key == Key.DirectionUp && inCredits -> skipCredits()
                    !currentTarget.isLive && ev.key == Key.DirectionUp -> { panelOpen = true; true }
                    // VOD : ◀ / ▶ = recul / avance (toujours actifs).
                    !currentTarget.isLive && ev.key == Key.DirectionLeft -> { seekBy(-seekBackSeconds * 1000L); true }
                    !currentTarget.isLive && ev.key == Key.DirectionRight -> { seekBy(seekForwardSeconds * 1000L); true }
                    digit != null && currentTarget.isLive && currentZapToNumber != null -> {
                        if (digits.length < 4) digits += digit
                        true
                    }
                    ev.key == Key.DirectionUp || ev.key == Key.ChannelUp -> doZap(+1)
                    ev.key == Key.DirectionDown || ev.key == Key.ChannelDown -> doZap(-1)
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    keepScreenOn = true   // empêche la veille pendant la lecture
                    // La barre native est AFFICHAGE SEULEMENT. Sinon, à chaque apparition, elle appelle
                    // requestPlayPauseFocus() et vole le focus Android à Compose : le lecteur devient
                    // « sourd » (pause / avance / zapping / panneau sans effet). Compose garde toutes les touches.
                    useController = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowRewindButton(false)
                    setShowFastForwardButton(false)
                    controllerShowTimeoutMs = 4000
                    resizeMode = resize
                    playerView = this
                }
            },
            update = { view ->
                if (view.player !== exo) view.player = exo
                if (view.resizeMode != resize) view.resizeMode = resize
            },
            onRelease = { it.player = null },
        )

        // Bandeau d'information (titre / catégorie / programme en cours), masqué après quelques secondes.
        AnimatedVisibility(
            visible = showInfo && error == null && !panelOpen,
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
                epgLine?.let { Text("En ce moment : $it", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f)) }
                if (target.startPositionMs > 0) {
                    Text("↺ Reprise à ${fmtClock(target.startPositionMs)}", style = MaterialTheme.typography.bodyLarge, color = OnyxCyan)
                }
                Text(
                    buildString {
                        if (target.isLive && zap != null) append("↑ ↓ chaîne  ·  0-9 numéro  ·  ")
                        append("Menu ≡ pistes & image")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnyxMuted,
                )
            }
        }

        // Indications contextuelles (intro / générique / recul-avance) en bas à droite.
        val hint = when {
            ended || error != null || panelOpen -> null
            seekNote != null -> seekNote
            inCredits -> "▲ Passer le générique → épisode suivant"
            else -> null
        }
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 96.dp, end = 28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // Numéro de chaîne en cours de saisie.
        if (digits.isNotEmpty()) {
            Text(
                digits,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }

        if (buffering && error == null) {
            CircularProgressIndicator(color = OnyxCyan, modifier = Modifier.align(Alignment.Center))
        }

        // Panneau Menu : pistes audio, sous-titres, format d'image.
        if (panelOpen) {
            val audio = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_AUDIO) }
            val subs = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_TEXT) }
            val subsDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(380.dp)
                    .background(Color(0xF00B0C14))
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Lecture", style = MaterialTheme.typography.headlineMedium, color = Color.White)

                if (!target.isLive) {
                    Text("Navigation", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                    if (target.next != null) Button(onClick = { skipCredits() }) { Text("⏭ Épisode suivant") }
                    Text("◀ −$seekBackSeconds s   ▶ +$seekForwardSeconds s   ▲ générique → épisode suivant", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium)
                }

                Text("Format d'image", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajusté",
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
                        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étiré",
                    ).forEachIndexed { idx, (mode, label) ->
                        Button(
                            onClick = { resize = mode },
                            modifier = if (idx == 0) Modifier.focusRequester(panelFocus) else Modifier,
                        ) { Text(if (resize == mode) "✓ $label" else label) }
                    }
                }

                Text("Audio", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                if (audio.isEmpty()) Text("Aucune piste détectée", color = OnyxMuted)
                audio.forEach { o ->
                    Button(onClick = { selectTrack(o, C.TRACK_TYPE_AUDIO) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (o.selected) "✓ ${o.label}" else o.label)
                    }
                }

                Text("Sous-titres", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = { disableSubtitles() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (subsDisabled || subs.none { it.selected }) "✓ Désactivés" else "Désactivés")
                }
                if (subs.isEmpty()) Text("Aucun sous-titre dans ce flux", color = OnyxMuted)
                subs.forEach { o ->
                    Button(onClick = { selectTrack(o, C.TRACK_TYPE_TEXT) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (o.selected && !subsDisabled) "✓ ${o.label}" else o.label)
                    }
                }

                Text("Retour ou Menu pour fermer", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
        }

        // Fin de lecture
        if (ended && error == null) {
            val next = target.next
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE60B0C14))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (next != null) {
                    Text("Épisode suivant dans $countdown s", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(next.title, style = MaterialTheme.typography.bodyLarge, color = OnyxMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { currentOnSwitch(next) }, modifier = Modifier.focusRequester(overlayFocus)) { Text("▶ Lire maintenant") }
                        Button(onClick = onExit) { Text("Retour") }
                    }
                } else {
                    Text("Lecture terminée", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Button(onClick = onExit, modifier = Modifier.focusRequester(overlayFocus)) { Text("Retour") }
                }
            }
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
                    Button(onClick = { load() }, modifier = Modifier.focusRequester(overlayFocus)) { Text("Réessayer") }
                    if (target.isLive && zap != null) Button(onClick = { doZap(+1) }) { Text("Chaîne suivante") }
                    Button(onClick = onExit) { Text("Retour") }
                }
            }
        }
    }
}
