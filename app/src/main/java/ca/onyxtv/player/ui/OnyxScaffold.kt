package ca.onyxtv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dvr
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import ca.onyxtv.player.R
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.player.PlayerScreen
import ca.onyxtv.player.ui.components.PinDialog
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.dvr.DvrScreen
import ca.onyxtv.player.ui.guide.GuideScreen
import ca.onyxtv.player.ui.home.HomeScreen
import ca.onyxtv.player.ui.live.LiveTvScreen
import ca.onyxtv.player.ui.mosaic.MosaicScreen
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.ui.movie.MovieScreen
import ca.onyxtv.player.ui.search.SearchScreen
import ca.onyxtv.player.ui.series.SeriesScreen
import ca.onyxtv.player.ui.settings.SettingsScreen
import ca.onyxtv.player.ui.theme.OnyxBg
import ca.onyxtv.player.ui.theme.OnyxBg2
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.vod.VodScreen
import ca.onyxtv.player.viewmodel.OnyxViewModel

enum class Dest(val label: String, val icon: ImageVector) {
    SEARCH("Recherche", Icons.Rounded.Search),
    HOME("Accueil", Icons.Rounded.Home),
    LIVE("TV en direct", Icons.Rounded.LiveTv),
    GUIDE("Guide TV", Icons.Rounded.Today),
    VOD("Films & Séries", Icons.Rounded.Movie),
    MOSAIC("Mosaïque", Icons.Rounded.GridView),
    DVR("Enregistrements", Icons.Rounded.Dvr),
    SETTINGS("Réglages", Icons.Rounded.Settings),
}

@Composable
fun OnyxRoot(vm: OnyxViewModel = viewModel()) {
    var dest by remember { mutableStateOf(Dest.HOME) }
    var playing by remember { mutableStateOf<PlayTarget?>(null) }
    var openDetail by remember { mutableStateOf<VodItem?>(null) }

    // Verrouillage de l'application au démarrage (contrôle parental).
    val parental by vm.parental.collectAsStateWithLifecycle()
    val appUnlocked by vm.appUnlocked.collectAsStateWithLifecycle()
    if (parental.enabled && parental.lockAtStart && !appUnlocked) {
        Box(Modifier.fillMaxSize().background(OnyxBg)) {
            PinDialog(
                title = "ONYX TV est verrouillé",
                subtitle = "Entrez votre code PIN pour continuer.",
                onSubmit = { vm.unlockApp(it) },
            )
        }
        return
    }

    // Reprise automatique de la dernière lecture à l'ouverture (option Réglages → Lecture).
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    var autoResumed by remember { mutableStateOf(false) }
    LaunchedEffect(prefs.resumeOnStart, recents) {
        if (!autoResumed && prefs.resumeOnStart && recents.isNotEmpty()) {
            autoResumed = true
            playing = recents.first().toPlayTarget()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(OnyxBg)
    ) {
        NavigationDrawer(
            drawerContent = { drawerValue ->
                Column(
                    Modifier
                        .fillMaxHeight()
                        .background(OnyxBg2)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 16.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = "ONYX",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(30.dp),
                        )
                        if (drawerValue == DrawerValue.Open) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "ONYX",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }

                    Dest.entries.forEach { d ->
                        NavigationDrawerItem(
                            selected = d == dest,
                            onClick = { dest = d },
                            leadingContent = {
                                Icon(imageVector = d.icon, contentDescription = d.label)
                            },
                        ) {
                            Text(d.label)
                        }
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                when (dest) {
                    Dest.HOME -> HomeScreen(vm, onPlay = { playing = it }, onGoLive = { dest = Dest.LIVE }, onGoSettings = { dest = Dest.SETTINGS }, onOpenDetail = { openDetail = it })
                    Dest.LIVE -> LiveTvScreen(vm, onPlay = { playing = it })
                    Dest.GUIDE -> GuideScreen(vm, onPlay = { playing = it })
                    Dest.VOD -> VodScreen(vm, onPlay = { playing = it }, onOpenDetail = { openDetail = it })
                    Dest.MOSAIC -> MosaicScreen(vm, onPlay = { playing = it })
                    Dest.DVR -> DvrScreen(vm, onPlay = { playing = it })
                    Dest.SETTINGS -> SettingsScreen(vm)
                    Dest.SEARCH -> SearchScreen(vm, onPlay = { playing = it }, onOpenDetail = { openDetail = it })
                }
            }
        }

        // Horloge discrète (mise à jour chaque 30 s), masquée pendant la lecture.
        if (playing == null) {
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
            Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
                style = MaterialTheme.typography.titleMedium,
                color = OnyxMuted,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 28.dp),
            )
        }

        // Fiche série par-dessus la navigation ; le lecteur reste au-dessus de tout.
        openDetail?.let { item ->
            if (item.kind == MediaKind.SERIES)
                SeriesScreen(vm = vm, item = item, onPlay = { playing = it }, onBack = { openDetail = null })
            else
                MovieScreen(vm = vm, item = item, onPlay = { playing = it }, onBack = { openDetail = null })
        }

        playing?.let { target ->
            PlayerScreen(
                target = target,
                onExit = { playing = null },
                onProgress = vm::onPlaybackProgress,
                zap = { delta -> vm.neighborChannel(target.id, delta)?.toPlayTarget() },
                onSwitch = { playing = it },
                zapToNumber = { n -> vm.channelByNumber(n)?.toPlayTarget() },
                nowPlaying = { t -> vm.nowPlaying(t) },
                seekBackSeconds = prefs.seekBackSeconds,
                seekForwardSeconds = prefs.seekForwardSeconds,
            )
        }
    }
}
