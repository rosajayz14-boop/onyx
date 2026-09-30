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
import androidx.compose.runtime.Composable
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
import ca.onyxtv.player.ui.dvr.DvrScreen
import ca.onyxtv.player.ui.home.HomeScreen
import ca.onyxtv.player.ui.live.LiveTvScreen
import ca.onyxtv.player.ui.mosaic.MosaicScreen
import ca.onyxtv.player.ui.search.SearchScreen
import ca.onyxtv.player.ui.settings.SettingsScreen
import ca.onyxtv.player.ui.theme.OnyxBg
import ca.onyxtv.player.ui.theme.OnyxBg2
import ca.onyxtv.player.ui.vod.VodScreen
import ca.onyxtv.player.viewmodel.OnyxViewModel

enum class Dest(val label: String, val icon: ImageVector) {
    SEARCH("Recherche", Icons.Rounded.Search),
    HOME("Accueil", Icons.Rounded.Home),
    LIVE("TV en direct", Icons.Rounded.LiveTv),
    VOD("Films & Séries", Icons.Rounded.Movie),
    MOSAIC("Mosaïque", Icons.Rounded.GridView),
    DVR("Enregistrements", Icons.Rounded.Dvr),
    SETTINGS("Réglages", Icons.Rounded.Settings),
}

@Composable
fun OnyxRoot(vm: OnyxViewModel = viewModel()) {
    var dest by remember { mutableStateOf(Dest.HOME) }
    var playing by remember { mutableStateOf<PlayTarget?>(null) }

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
                    Dest.HOME -> HomeScreen(vm, onPlay = { playing = it }, onGoLive = { dest = Dest.LIVE })
                    Dest.LIVE -> LiveTvScreen(vm, onPlay = { playing = it })
                    Dest.VOD -> VodScreen(vm, onPlay = { playing = it })
                    Dest.MOSAIC -> MosaicScreen(vm, onPlay = { playing = it })
                    Dest.DVR -> DvrScreen()
                    Dest.SETTINGS -> SettingsScreen(vm)
                    Dest.SEARCH -> SearchScreen(vm, onPlay = { playing = it })
                }
            }
        }

        playing?.let { target ->
            PlayerScreen(target = target, onExit = { playing = null })
        }
    }
}
