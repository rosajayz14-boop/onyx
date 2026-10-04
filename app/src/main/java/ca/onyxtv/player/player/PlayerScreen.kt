package ca.onyxtv.player.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.compose.runtime.key
import android.view.LayoutInflater
import ca.onyxtv.player.R
import ca.onyxtv.player.core.net.StreamProbe
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
    /** Série d'origine (épisode) : rangée « Continuer la série ». */
    val seriesId: String? = null,
    /** Différé d'une chaîne (rattrapage Xtream) : instant réel (epoch ms) du début du flux, 0 = direct. */
    val shiftStartMs: Long = 0L,
    /** Cible « direct » d'origine d'un flux en différé (retour au direct, zapping, récents). */
    val liveOrigin: PlayTarget? = null,
)

/**
 * Lecteur ExoPlayer. [preferSoftware] : décodeurs logiciels (c2.android.* / OMX.google.*) d'abord,
 * repli quand le décodeur matériel du boîtier rend une image noire (HEVC 10 bits, Dolby Vision…).
 */
@OptIn(UnstableApi::class)
internal fun buildPlayer(context: android.content.Context, preferSoftware: Boolean): ExoPlayer {
    // Les serveurs Xtream redirigent souvent http -> https : refusé par défaut (=> erreur de lecture).
    // L'UA « ExoPlayerLib » est bloqué par certains panneaux.
    val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        .setUserAgent("ONYX-TV/1.0 (Android TV)")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    if (preferSoftware) {
        renderers.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .sortedBy { if (it.hardwareAccelerated) 1 else 0 }
        }
    }
    // Fichiers VOD en .ts / .mp3 sans index : avance/recul possibles grâce au débit constant.
    val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context, http),
                extractors,
            ).setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6)),
        )
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .build().apply { playWhenReady = true }
}

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

internal val DIGIT_KEYS = mapOf(
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
    subtitleScale: Float = 1f,
    subtitleBackground: Boolean = true,
    subtitleYellow: Boolean = false,
    /** Liste des chaînes (superposition ▶ en direct) et chaîne précédente (◀). */
    channelList: (() -> List<ca.onyxtv.player.core.model.Channel>)? = null,
    previous: (() -> PlayTarget?)? = null,
    /** Différé (pause / retour arrière sur le direct) : la chaîne offre-t-elle le rattrapage ? */
    canShift: ((PlayTarget) -> Boolean)? = null,
    /** Cible de différé de la chaîne [live] à partir de l'instant réel [startMs] (null : pas d'archive). */
    timeshift: (suspend (PlayTarget, Long) -> PlayTarget?)? = null,
) {
    val context = LocalContext.current
    // Repli « image noire » : rendu TextureView, puis décodeur LOGICIEL (nouveau lecteur).
    var textureView by remember { mutableStateOf(false) }
    var softwareDecoder by remember { mutableStateOf(false) }
    var playerGen by remember { mutableIntStateOf(0) }
    var reloadAtMs by remember { mutableStateOf(-1L) }
    val exo = remember(playerGen) { buildPlayer(context, softwareDecoder) }
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
    // Diagnostic du flux (ce que le serveur renvoie vraiment, codecs, décodeur, première image).
    var probe by remember { mutableStateOf<StreamProbe.Result?>(null) }
    var videoFmt by remember { mutableStateOf<Format?>(null) }
    var audioFmt by remember { mutableStateOf<Format?>(null) }
    var decoder by remember { mutableStateOf<String?>(null) }
    var firstFrame by remember { mutableStateOf(false) }
    var readyAt by remember { mutableStateOf(0L) }
    var noPicture by remember { mutableStateOf(false) }
    var fallbackNote by remember { mutableStateOf<String?>(null) }
    // OK long a ouvert le panneau : les répétitions et le relâchement de CE même appui ne doivent
    // pas « cliquer » le premier bouton du panneau (remise du format d'image à « Ajusté »).
    var okLatched by remember { mutableStateOf(false) }
    // Direct sans extension renvoyant du HLS : un seul nouvel essai en forçant le type m3u8.
    var hlsRetried by remember { mutableStateOf(false) }
    // Minuterie de sommeil (epoch ms, 0 = inactive) et vitesse de lecture (VOD).
    var sleepAt by remember { mutableStateOf(0L) }
    var speed by remember { mutableStateOf(1f) }
    var sleepLeft by remember { mutableStateOf(0L) }
    // Superposition « liste des chaînes » (direct, ▶).
    var listOpen by remember { mutableStateOf(false) }
    val listFocus = remember { FocusRequester() }
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

    suspend fun load() {
        error = null
        buffering = true
        firstFrame = false; readyAt = 0L; noPicture = false
        val isReload = reloadAtMs >= 0
        val startMs = if (isReload) reloadAtMs else target.startPositionMs
        reloadAtMs = -1L
        hlsRetried = false
        var item = MediaItem.fromUri(target.url)
        // Sondage une seule fois par cible (pas au rechargement décodeur/réessai : certains
        // panneaux limitent à 1 connexion et compteraient le sondage).
        if (!target.isLive && !isReload) {
            // VOD : on regarde d'abord ce que le serveur renvoie VRAIMENT (redirection, HLS, page
            // HTML, 403…). Sinon ExoPlayer joue n'importe quoi en silence (écran noir, « film » de
            // quelques minutes = vidéo d'erreur du panneau) ou échoue avec un code obscur.
            val p = StreamProbe.probe(target.url)
            probe = p
            if (p != null) {
                val blocking = when {
                    p.code == 401 || p.code == 403 -> "Le serveur refuse ce film (HTTP ${p.code}) : identifiants ou abonnement non autorisés pour la VOD."
                    p.code == 404 -> "Film introuvable sur le serveur (HTTP 404) : fichier retiré ou adresse changée. Réglages → « Tout mettre à jour »."
                    p.code >= 400 -> "Erreur du serveur (HTTP ${p.code})."
                    p.kind == StreamProbe.Kind.HTML -> "Le serveur renvoie une page (${p.contentType?.substringBefore(';') ?: "texte"}) au lieu de la vidéo : VOD inactive sur ce compte ou lien expiré."
                    else -> null
                }
                if (blocking != null) { buffering = false; error = blocking; return }
                // On garde l'URL D'ORIGINE (une URL finale de redirection est parfois à jeton unique) ;
                // seul le type HLS est forcé quand le serveur renvoie une playlist.
                item = MediaItem.Builder().setUri(target.url)
                    .apply { if (p.kind == StreamProbe.Kind.HLS) setMimeType(MimeTypes.APPLICATION_M3U8) }
                    .build()
            }
        }
        exo.setMediaItem(item)
        exo.prepare()
        if (!target.isLive && startMs > 0) exo.seekTo(startMs)
        exo.play()
    }

    fun fmtVideo(f: Format?): String {
        if (f == null) return "?"
        val codec = when (f.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_MP4V -> "MPEG-4"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            else -> f.sampleMimeType ?: "?"
        }
        val size = if (f.width > 0 && f.height > 0) " ${f.width}×${f.height}" else ""
        val extra = f.codecs?.let { " ($it)" } ?: ""
        return codec + size + extra
    }

    fun fmtAudio(f: Format?): String {
        if (f == null) return "?"
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_AC3 -> "AC-3"; MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
            MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_DTS -> "DTS"; MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> f.sampleMimeType ?: "?"
        }
        return codec + (if (f.channelCount > 0) " ${f.channelCount}ch" else "")
    }

    /** Résumé technique du flux (panneau, erreurs) : serveur, codecs, décodeur, durée. */
    fun streamInfo(): String = buildString {
        probe?.let { append(it.summary()).append('\n') }
        append("vidéo ").append(fmtVideo(videoFmt))
        append(" · audio ").append(fmtAudio(audioFmt))
        decoder?.let { append("\ndécodeur ").append(it) }
        if (softwareDecoder) append(" (logiciel)")
        if (textureView) append(" · rendu TextureView")
        if (durMs > 0) append(" · durée ").append(fmtClock(durMs))
        if (!firstFrame && readyAt > 0) append(" · AUCUNE IMAGE")
    }

    /** Pourquoi l'écran reste noir, en clair. */
    fun pictureDiagnosis(): String {
        val vGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        return when {
            vGroups.isEmpty() -> "Ce fichier ne contient aucune piste vidéo lisible (fichier audio ou vidéo d'erreur du serveur)."
            vGroups.none { g -> (0 until g.length).any { g.isTrackSupported(it) } } ->
                "Vidéo ${fmtVideo(vGroups.first().getTrackFormat(0))} : aucun décodeur sur ce boîtier."
            else -> "Le décodeur ${decoder ?: "matériel"} ne produit aucune image pour ${fmtVideo(videoFmt)} (souvent HEVC 10 bits / Dolby Vision)."
        }
    }

    // ---- Différé sur le direct (rattrapage Xtream) ----
    // Retour arrière = nouveau flux « timeshift » démarrant à l'instant voulu (granularité : la
    // minute). Les appuis successifs s'accumulent 700 ms avant de recharger (appui long = recul rapide).
    var shiftPendingMin by remember { mutableIntStateOf(0) }
    var shiftJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pausedAtMs by remember { mutableStateOf(0L) }
    fun liveOrigin(): PlayTarget = currentTarget.liveOrigin ?: currentTarget
    val shiftable = currentTarget.isLive && timeshift != null && (canShift?.invoke(liveOrigin()) ?: false)
    /** Instant réel en cours de lecture (différé) ou maintenant (direct). */
    fun playheadWallMs(): Long = if (currentTarget.shiftStartMs > 0L) currentTarget.shiftStartMs + exo.currentPosition.coerceAtLeast(0L) else System.currentTimeMillis()
    fun delayMin(): Long = ((System.currentTimeMillis() - playheadWallMs()) / 60_000L).coerceAtLeast(0L)
    fun goLive() {
        val origin = liveOrigin()
        seekNote = "\u25cf Retour au direct"
        if (currentTarget.shiftStartMs > 0L) currentOnSwitch(origin) else { exo.seekToDefaultPosition(); exo.play() }
    }
    /** Charge le différé à l'instant réel [wallMs] (ou revient au direct s'il est trop proche de maintenant). */
    fun shiftTo(wallMs: Long) {
        val ts = timeshift ?: return
        if (wallMs >= System.currentTimeMillis() - 45_000L) { goLive(); return }
        val origin = liveOrigin()
        scope.launch {
            val t = ts(origin, wallMs)
            if (t == null) seekNote = "Cette cha\u00eene n'offre pas de rattrapage : impossible de reculer."
            else currentOnSwitch(t)
        }
    }
    fun shiftBy(deltaMin: Int) {
        if (!currentTarget.isLive) return
        if (!shiftable) { seekNote = "Cette cha\u00eene n'offre pas de rattrapage : pause et retour arri\u00e8re indisponibles."; return }
        shiftPendingMin += deltaMin
        val total = delayMin() - shiftPendingMin
        seekNote = if (total <= 0L) "\u25cf Retour au direct" else "\u23ea Diff\u00e9r\u00e9 \u2212$total min"
        shiftJob?.cancel()
        shiftJob = scope.launch {
            delay(700)
            val d = shiftPendingMin; shiftPendingMin = 0
            shiftTo(playheadWallMs() + d * 60_000L)
        }
    }

    /** Pause ↔ lecture, y compris pendant la mise en mémoire tampon (isPlaying y est faux). */
    fun togglePlay() {
        if (exo.playWhenReady) {
            exo.pause()
            pausedAtMs = System.currentTimeMillis()
            if (currentTarget.isLive) seekNote = if (shiftable) "\u23f8 Pause \u2014 OK pour reprendre o\u00f9 vous en \u00e9tiez" else "\u23f8 Pause (sans rattrapage : la reprise rejoint le direct)"
        } else {
            val pausedFor = if (pausedAtMs > 0L) System.currentTimeMillis() - pausedAtMs else 0L
            pausedAtMs = 0L
            // Direct mis en pause plus de 20 s : la mémoire tampon ne suit pas ; on reprend en différé
            // à l'instant de la pause (rattrapage) au lieu de sauter au direct.
            if (currentTarget.isLive && shiftable && pausedFor > 20_000L) {
                val resumeAt = playheadWallMs() - pausedFor
                exo.play()
                shiftTo(resumeAt)
            } else exo.play()
        }
        runCatching { playerView?.showController() }
    }

    fun seekBy(deltaMs: Long) {
        if (currentTarget.isLive) { shiftBy(if (deltaMs < 0) -1 else 1); return }
        if (!exo.isCurrentMediaItemSeekable) return
        val dur = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        val to = (exo.currentPosition + deltaMs).coerceIn(0L, dur)
        exo.seekTo(to)
        posMs = to
        seekNote = (if (deltaMs >= 0) "⏩ +" else "⏪ −") + "${kotlin.math.abs(deltaMs) / 1000} s   ${fmtClock(to)} / ${fmtClock(dur)}"
    }

    // Remontée de progression. Pour la VOD, on ignore une durée inconnue (sortie rapide, flux en
    // échec) : sinon on écrirait « durée 0 » et on EFFACERAIT le point de reprise existant.
    fun report(t0: PlayTarget) {
        val t = t0.liveOrigin ?: t0
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
                if (liveRetries >= 5) {
                    buffering = false
                    error = "Le serveur a coupé le flux et la reconnexion a échoué 5 fois."
                    return false
                }
                liveRetries++
                buffering = true
                scope.launch {
                    delay(1_000L * liveRetries)
                    // prepare() est sans effet si le lecteur n'est pas en IDLE (ex. après STATE_ENDED) : stop() d'abord.
                    runCatching { exo.stop(); exo.seekToDefaultPosition(); exo.prepare(); exo.play() }
                }
                return true
            }
            override fun onRenderedFirstFrame() { firstFrame = true; noPicture = false }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY && readyAt == 0L) readyAt = System.currentTimeMillis()
                if (playbackState == Player.STATE_ENDED) {
                    if (currentTarget.isLive) reconnectLive() else { ended = true; panelOpen = false }
                }
                runCatching { playerView?.keepScreenOn = exo.playWhenReady && !ended && error == null }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { error = null; liveRetries = 0 }
                PlaybackBridge.pipEligible = !currentTarget.isLive && exo.playWhenReady && error == null && !ended
                // Pas de veille pendant la lecture ; en pause/erreur, l'écran peut s'éteindre normalement.
                runCatching { playerView?.keepScreenOn = exo.playWhenReady && !ended && error == null }
            }
            override fun onTracksChanged(t: Tracks) { tracks = t; videoFmt = exo.videoFormat; audioFmt = exo.audioFormat }
            override fun onPlayerError(e: PlaybackException) {
                // HLS direct : décroché de la fenêtre live -> on se recale sans erreur.
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    runCatching { exo.seekToDefaultPosition(); exo.prepare() }
                    return
                }
                // Direct sans extension qui renvoie une playlist HLS : on force le type m3u8 (une fois).
                if (currentTarget.isLive && !hlsRetried && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    hlsRetried = true
                    runCatching {
                        exo.setMediaItem(MediaItem.Builder().setUri(currentTarget.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                        exo.prepare(); exo.play()
                    }
                    return
                }
                // Direct : erreur RÉSEAU transitoire -> reconnexion silencieuse. Un 403/404 (compte
                // limité, chaîne retirée) est définitif : on l'affiche tout de suite.
                val transient = e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                if (currentTarget.isLive && transient && reconnectLive()) return
                buffering = false
                panelOpen = false
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
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoder = decoderName
            }
        }
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
        // MediaSession : touches média système, commandes vocales (Alexa / Google), « en lecture ».
        val session = runCatching {
            androidx.media3.session.MediaSession.Builder(context, exo).setId("onyx-" + System.identityHashCode(exo)).build()
        }.getOrNull()
        onDispose {
            PlaybackBridge.pipEligible = false
            runCatching { session?.release() }
            exo.removeListener(listener)
            exo.removeAnalyticsListener(analytics)
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
    LaunchedEffect(exo, target.url) {
        ended = false
        panelOpen = false
        showInfo = true
        load()
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
    LaunchedEffect(exo, target.url) {
        while (isActive) {
            posMs = exo.currentPosition
            durMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            // Chien de garde « image noire » : prêt depuis 7 s, lecture lancée, aucune image rendue.
            // Repli automatique UNE fois par étape : rendu TextureView, puis décodeur logiciel ;
            // ensuite on explique clairement (codec, décodeur) au lieu de laisser l'écran noir.
            val vGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            val decodable = vGroups.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }
            // Pas de piste vidéo (radio, .mp3) : rien à surveiller — surtout pas de reconstruction du lecteur.
            if (vGroups.isNotEmpty() && !firstFrame && error == null && readyAt > 0 && exo.playWhenReady &&
                exo.playbackState == Player.STATE_READY && System.currentTimeMillis() - readyAt > 7_000) {
                when {
                    !decodable -> { noPicture = true; fallbackNote = null }
                    !textureView -> {
                        textureView = true; readyAt = System.currentTimeMillis()
                        fallbackNote = "Aucune image : passage au rendu TextureView…"
                    }
                    !softwareDecoder -> {
                        reloadAtMs = exo.currentPosition
                        softwareDecoder = true; readyAt = 0L
                        fallbackNote = "Toujours aucune image : essai du décodeur logiciel…"
                        playerGen++
                    }
                    else -> { noPicture = true; fallbackNote = null }
                }
            }
            delay(1_000)
        }
    }
    LaunchedEffect(fallbackNote) { if (fallbackNote != null) { delay(6_000); fallbackNote = null } }
    // Minuterie : à l'échéance, pause + sortie du lecteur.
    LaunchedEffect(sleepAt) {
        if (sleepAt <= 0L) { sleepLeft = 0L; return@LaunchedEffect }
        while (isActive) {
            sleepLeft = sleepAt - System.currentTimeMillis()
            if (sleepLeft <= 0L) { runCatching { exo.pause() }; onExit(); return@LaunchedEffect }
            delay(1_000)
        }
    }
    LaunchedEffect(exo, speed) { runCatching { exo.setPlaybackSpeed(speed) } }
    LaunchedEffect(seekNote) { if (seekNote != null) { delay(1_800); seekNote = null } }

    LaunchedEffect(exo, target.url) {
        while (isActive) {
            delay(5_000)
            runCatching { report(target) }
        }
    }
    // Dernière remontée à la sortie : on CAPTURE la cible de cet effet. Avec currentTarget, au
    // zapping ou à l'épisode suivant, la position de l'ANCIEN élément était enregistrée sous
    // l'id du NOUVEAU (épisode suivant marqué « vu », reprise au mauvais endroit).
    DisposableEffect(exo, target.url) {
        val captured = target
        onDispose { runCatching { report(captured) } }
    }

    LaunchedEffect(Unit) { repeat(10) { runCatching { focus.requestFocus() }; kotlinx.coroutines.delay(100) } }
    LaunchedEffect(panelOpen) {
        if (panelOpen) runCatching { panelFocus.requestFocus() }
        else if (!listOpen) runCatching { if (error != null || ended) overlayFocus.requestFocus() else focus.requestFocus() }
    }
    LaunchedEffect(listOpen) {
        if (listOpen) { delay(60); runCatching { listFocus.requestFocus() } }
        else runCatching { if (error != null || ended) overlayFocus.requestFocus() else focus.requestFocus() }
    }
    LaunchedEffect(error, ended) {
        delay(80)
        if (error != null || ended) runCatching { overlayFocus.requestFocus() } else runCatching { focus.requestFocus() }
    }

    BackHandler(enabled = true) { if (panelOpen) panelOpen = false else if (listOpen) listOpen = false else onExit() }

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
                if (ev.key == Key.Back && (panelOpen || listOpen)) {
                    if (ev.type == KeyEventType.KeyUp) { panelOpen = false; listOpen = false }
                    return@onPreviewKeyEvent true
                }
                // Liste des chaînes ouverte : les touches vont à la liste (OK = changer de chaîne).
                if (listOpen && !(ev.key == Key.Menu && ev.type == KeyEventType.KeyDown)) return@onPreviewKeyEvent false
                if (listOpen) { listOpen = false; return@onPreviewKeyEvent true }
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                // Suite de l'appui long qui a ouvert le panneau : avalée jusqu'au relâchement.
                if (isOk && okLatched) {
                    if (ev.type == KeyEventType.KeyUp) okLatched = false
                    return@onPreviewKeyEvent true
                }
                // Touches média de la télécommande (⏯ ⏪ ⏩ ⏹) : toujours actives.
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { if (error == null && !ended) togglePlay(); return@onPreviewKeyEvent true }
                        Key.MediaRewind -> { seekBy(-seekBackSeconds * 1000L); return@onPreviewKeyEvent true }
                        Key.MediaFastForward -> { seekBy(seekForwardSeconds * 1000L); return@onPreviewKeyEvent true }
                        Key.MediaStop -> { onExit(); return@onPreviewKeyEvent true }
                        else -> Unit
                    }
                }
                // Écran d'erreur ou de fin : les boutons (Réessayer / Lire maintenant / Retour) doivent
                // recevoir OK et les flèches ; on laisse donc tout passer aux enfants.
                if (error != null || ended) return@onPreviewKeyEvent false
                if (isOk && !panelOpen) {
                    when {
                        ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.isLongPress -> { panelOpen = true; okLatched = true }
                        ev.type == KeyEventType.KeyUp && !ev.nativeKeyEvent.isCanceled -> togglePlay()
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
                    // Direct avec rattrapage : ◀ = reculer d'une minute (différé) ; sinon ◀ = chaîne précédente.
                    currentTarget.isLive && ev.key == Key.DirectionLeft && shiftable -> { shiftBy(-1); true }
                    currentTarget.isLive && ev.key == Key.DirectionLeft -> { previous?.invoke()?.let { currentOnSwitch(it) }; true }
                    // Différé : ▶ = avancer d'une minute / revenir au direct ; direct : ▶ = liste des chaînes.
                    currentTarget.isLive && ev.key == Key.DirectionRight && currentTarget.shiftStartMs > 0L -> { shiftBy(+1); true }
                    currentTarget.isLive && ev.key == Key.DirectionRight && channelList != null -> { listOpen = true; true }
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
        key(textureView) { AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = if (textureView) LayoutInflater.from(ctx).inflate(R.layout.player_texture, null) as PlayerView else PlayerView(ctx)
                view.apply {
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
                // Style des sous-titres (Réglages → Lecture) : taille, fond, couleur.
                runCatching {
                    view.subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setApplyEmbeddedFontSizes(false)
                        setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale)
                        setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                if (subtitleYellow) android.graphics.Color.YELLOW else android.graphics.Color.WHITE,
                                if (subtitleBackground) android.graphics.Color.argb(160, 0, 0, 0) else android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            )
                        )
                    }
                }
            },
            onRelease = { it.player = null },
        ) }

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
                    if (target.isLive && target.shiftStartMs > 0L) Text("\u23ea DIFF\u00c9R\u00c9 \u2212${delayMin()} min", color = OnyxCyan, style = MaterialTheme.typography.labelLarge)
                    else if (target.isLive) Text("● DIRECT", color = OnyxLive, style = MaterialTheme.typography.labelLarge)
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
                        if (shiftable) append("OK pause  ·  ◀ reculer  ·  ▶ avancer / direct  ·  ")
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
            fallbackNote != null -> fallbackNote
            sleepLeft in 1..60_000L -> "💤 Arrêt dans ${sleepLeft / 1000} s"
            inCredits -> "▲ Passer le générique → épisode suivant"
            else -> null
        }

        // Écran noir malgré tout : on dit POURQUOI (codec, décodeur, réponse du serveur).
        if (noPicture && error == null && !ended && !panelOpen) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 28.dp, bottom = 96.dp, end = 120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xE60B0C14))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Aucune image", style = MaterialTheme.typography.titleLarge, color = OnyxLive)
                Text(pictureDiagnosis(), style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Text(streamInfo(), style = MaterialTheme.typography.bodySmall, color = OnyxMuted)
                Text("▲ ou OK long : panneau (rendu, décodeur, pistes)", style = MaterialTheme.typography.bodySmall, color = OnyxCyan)
            }
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

        // Liste des chaînes en superposition (direct) : ▲▼ parcourir, OK changer, Retour fermer.
        if (listOpen && channelList != null) {
            val list = remember(listOpen) { channelList().filterNot { it.id in emptySet<String>() } }
            val currentIdx = list.indexOfFirst { it.id == currentTarget.id }.coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 3).coerceAtLeast(0))
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(440.dp)
                    .background(Color(0xF00B0C14))
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                Text("Chaînes", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { c ->
                        androidx.tv.material3.ListItem(
                            selected = c.id == currentTarget.id,
                            onClick = { listOpen = false; currentOnSwitch(ca.onyxtv.player.ui.components.channelTarget(c)) },
                            headlineContent = { Text((c.number?.let { "$it · " } ?: "") + c.name, maxLines = 1) },
                            supportingContent = { c.groupTitle?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) } },
                            modifier = (if (c.id == list.getOrNull(currentIdx)?.id) Modifier.focusRequester(listFocus) else Modifier).fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
                }
            }
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

                if (target.isLive) {
                    Text("Direct", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (shiftable) {
                            Button(onClick = { panelOpen = false; shiftBy(-5) }) { Text("⏪ Reculer 5 min") }
                            Button(onClick = { panelOpen = false; shiftBy(-15) }) { Text("⏪ Reculer 15 min") }
                            if (target.shiftStartMs > 0L) Button(onClick = { panelOpen = false; goLive() }) { Text("● Revenir au direct") }
                        }
                        previous?.invoke()?.let { prev -> Button(onClick = { panelOpen = false; currentOnSwitch(prev) }) { Text("↩ Chaîne précédente") } }
                    }
                    Text(
                        if (shiftable) "OK = pause (reprise là où vous en étiez)  ·  ◀ −1 min  ·  ▶ +1 min / direct  ·  ⏪ ⏩ idem"
                        else "Cette chaîne n'offre pas de rattrapage : pause courte seulement, ◀ = chaîne précédente",
                        color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!target.isLive) {
                    Text("Navigation", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                    if (target.next != null) Button(onClick = { skipCredits() }) { Text("⏭ Épisode suivant") }
                    Text("◀ −$seekBackSeconds s   ▶ +$seekForwardSeconds s   ▲ générique → épisode suivant", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium)
                }

                Text("Minuterie de sommeil", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 15, 30, 60, 90).forEach { m ->
                        val on = if (m == 0) sleepAt == 0L else sleepAt > 0L && kotlin.math.abs((sleepAt - System.currentTimeMillis()) - m * 60_000L) < 90_000L
                        Button(onClick = { sleepAt = if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L }) {
                            Text((if (on) "✓ " else "") + (if (m == 0) "Off" else "$m min"))
                        }
                    }
                }
                if (sleepLeft > 0L) Text("Arrêt dans ${fmtClock(sleepLeft)}", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium)

                if (!target.isLive) {
                    Text("Vitesse", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                            Button(onClick = { speed = sp }) { Text((if (kotlin.math.abs(speed - sp) < 0.01f) "✓ " else "") + "×$sp") }
                        }
                    }
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

                Text("Image noire ?", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { textureView = !textureView; readyAt = System.currentTimeMillis(); noPicture = false }) {
                        Text(if (textureView) "Rendu : TextureView" else "Rendu : SurfaceView")
                    }
                    Button(onClick = {
                        reloadAtMs = exo.currentPosition; softwareDecoder = !softwareDecoder; noPicture = false; playerGen++
                    }) { Text(if (softwareDecoder) "Décodeur : logiciel" else "Décodeur : auto") }
                }

                Text("Infos flux", style = MaterialTheme.typography.titleMedium, color = OnyxCyan, modifier = Modifier.padding(top = 8.dp))
                Text(streamInfo(), color = OnyxMuted, style = MaterialTheme.typography.bodySmall)

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
                Text(streamInfo(), style = MaterialTheme.typography.bodySmall, color = OnyxMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { reloadAtMs = exo.currentPosition.coerceAtLeast(0L); scope.launch { load() } }, modifier = Modifier.focusRequester(overlayFocus)) { Text("Réessayer") }
                    if (target.isLive && zap != null) Button(onClick = { doZap(+1) }) { Text("Chaîne suivante") }
                    Button(onClick = onExit) { Text("Retour") }
                }
            }
        }
    }
}
