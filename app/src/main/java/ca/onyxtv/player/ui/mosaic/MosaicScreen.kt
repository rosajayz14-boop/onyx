package ca.onyxtv.player.ui.mosaic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Card
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.Thumbnail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface
import ca.onyxtv.player.viewmodel.OnyxViewModel
import ca.onyxtv.player.viewmodel.hiddenGroups

private const val SLOTS = 4

/**
 * Mosaïque multi-écran : 4 chaînes lues SIMULTANÉMENT (un ExoPlayer par tuile).
 * Le son suit la tuile focalisée ; OK sur une tuile l'ouvre en plein écran.
 * La liste de gauche remplit les tuiles à tour de rôle.
 */
@Composable
fun MosaicScreen(vm: OnyxViewModel, onPlay: (PlayTarget) -> Unit) {
    // Pas d'écran de veille sur les 4 tuiles.
    val rootView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(rootView) { rootView.keepScreenOn = true; onDispose { rootView.keepScreenOn = false } }
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val unlocked by vm.unlockedGroups.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val hidden = hiddenGroups(parental, unlocked) + prefs.hiddenCategories
    val channels = remember(state.channels, hidden, prefs.hiddenChannelIds) { state.channels.filterNot { it.groupTitle in hidden || it.id in prefs.hiddenChannelIds } }

    if (channels.isEmpty()) {
        EmptyState("Mosaïque indisponible", "Ajoutez des chaînes dans Réglages pour utiliser le multi-écran.")
        return
    }

    // Remplissage initial : favoris d'abord, puis premières chaînes.
    var slots by remember(channels) {
        val favs = channels.filter { it.id in favorites }
        val initial = (favs + channels.filterNot { it.id in favorites }).take(SLOTS)
        mutableStateOf(List<Channel?>(SLOTS) { initial.getOrNull(it) })
    }
    var active by remember { mutableIntStateOf(0) }
    var nextSlot by remember { mutableIntStateOf(0) }

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Text("Chaînes", style = MaterialTheme.typography.titleLarge)
            Text("OK place la chaîne dans la tuile ${nextSlot + 1}", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(4.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(channels, key = { it.id }) { c ->
                    ListItem(
                        selected = slots.any { it?.id == c.id },
                        onClick = {
                            slots = slots.toMutableList().also { it[nextSlot] = c }
                            nextSlot = (nextSlot + 1) % SLOTS
                        },
                        headlineContent = { Text((c.number?.let { "$it · " } ?: "") + c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { c.groupTitle?.let { Text(it, maxLines = 1, color = OnyxMuted, overflow = TextOverflow.Ellipsis) } },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(20.dp))

        Column(Modifier.fillMaxSize()) {
            Text(
                "Le son suit la tuile sélectionnée · OK = plein écran",
                color = OnyxMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in 0 until 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (col in 0 until 2) {
                            val i = row * 2 + col
                            MosaicTile(
                                channel = slots[i],
                                active = active == i,
                                onFocus = { active = i },
                                onOpen = { slots[i]?.let { onPlay(it.toPlayTarget()) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MosaicTile(
    channel: Channel?,
    active: Boolean,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(OnyxSurface)
        ) {
            if (channel == null) {
                Text(
                    "Tuile vide\nChoisissez une chaîne dans la liste",
                    color = OnyxMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            } else {
                val context = LocalContext.current
                var failed by remember(channel.url) { mutableStateOf(false) }
                // Même construction que le lecteur principal (UA accepté, redirections http->https,
                // repli décodeur) : sinon des tuiles échouaient là où le lecteur fonctionnait.
                val exo = remember(channel.url) {
                    ca.onyxtv.player.player.buildPlayer(context, false).apply {
                        setMediaItem(MediaItem.fromUri(channel.url))
                        prepare()
                        playWhenReady = true
                        volume = 0f
                    }
                }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(exo, lifecycleOwner) {
                    val listener = object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { failed = true }
                        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) failed = false }
                    }
                    // Accueil / veille : on coupe les 4 flux (décodeurs + connexions), on les reprend au retour.
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        when (e) {
                            androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { exo.stop() }
                            androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { exo.prepare(); exo.play() }
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
                // Tuile inactive : piste audio DÉSACTIVÉE (plus de décodage inutile), pas seulement muette.
                LaunchedEffect(exo, active) {
                    exo.volume = if (active) 1f else 0f
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !active).build()
                }
                // Flux en échec : un nouvel essai toutes les 15 s (le fournisseur coupe souvent brièvement).
                LaunchedEffect(failed) {
                    if (failed) {
                        kotlinx.coroutines.delay(15_000)
                        runCatching { exo.stop(); exo.prepare(); exo.play() }
                        failed = false
                    }
                }

                if (failed) {
                    Thumbnail(channel.logoUrl, channel.name.take(2).uppercase(), channel.id, Modifier.fillMaxSize())
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exo
                                useController = false
                                isFocusable = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            }
                        },
                        update = { view -> if (view.player !== exo) view.player = exo },
                    )
                }

                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (channel.number?.let { "$it · " } ?: "") + channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            failed -> "Indisponible"
                            active -> "🔊 DIRECT"
                            else -> "● DIRECT"
                        },
                        color = if (active) OnyxCyan else OnyxLive,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
