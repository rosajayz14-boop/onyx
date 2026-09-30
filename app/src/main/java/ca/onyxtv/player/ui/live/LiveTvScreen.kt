package ca.onyxtv.player.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.Thumbnail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurfaceHi
import ca.onyxtv.player.viewmodel.OnyxViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HM = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun fmt(ms: Long) = HM.format(Date(ms))

@Composable
fun LiveTvScreen(vm: OnyxViewModel, onPlay: (PlayTarget) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val channels = state.channels

    if (channels.isEmpty()) {
        EmptyState(
            title = "Aucune chaîne",
            hint = "Ajoutez une liste M3U ou un compte Xtream dans Réglages.",
        )
        return
    }

    var selected by remember(channels) { mutableStateOf(channels.first()) }

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        LazyColumn(
            modifier = Modifier.width(380.dp).fillMaxHeight(),
        ) {
            items(channels) { c ->
                ListItem(
                    selected = c.id == selected.id,
                    onClick = { onPlay(c.toPlayTarget()) },
                    leadingContent = {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(6.dp))
                        ) {
                            Thumbnail(c.logoUrl, c.name.take(2).uppercase(), c.id, Modifier.fillMaxSize())
                        }
                    },
                    headlineContent = {
                        Text((c.number?.let { "$it · " } ?: "") + c.name, maxLines = 1)
                    },
                    supportingContent = {
                        c.groupTitle?.let { Text(it, maxLines = 1, color = OnyxMuted) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .onFocusChanged { if (it.isFocused) selected = c },
                )
            }
        }

        Spacer(Modifier.width(24.dp))

        EpgPanel(vm = vm, channel = selected, onPlay = onPlay)
    }
}

@Composable
private fun EpgPanel(vm: OnyxViewModel, channel: Channel, onPlay: (PlayTarget) -> Unit) {
    val programs by produceState(initialValue = emptyList<EpgProgram>(), channel.id) {
        value = runCatching { vm.epgFor(channel) }.getOrDefault(emptyList())
    }
    val now = System.currentTimeMillis()
    val current = programs.firstOrNull { it.isLiveAt(now) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            Thumbnail(channel.logoUrl, channel.name.take(2).uppercase(), channel.id, Modifier.fillMaxSize())
        }

        Text(
            channel.name,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        current?.let {
            Text("● EN DIRECT · ${it.title}", color = OnyxLive, style = MaterialTheme.typography.titleMedium)
        }

        Button(onClick = { onPlay(channel.toPlayTarget()) }, modifier = Modifier.padding(vertical = 14.dp)) {
            Text("Regarder")
        }

        if (programs.isEmpty()) {
            Text("Guide indisponible pour cette chaîne.", color = OnyxMuted)
        } else {
            LazyColumn {
                items(programs) { p -> EpgRow(p, now) }
            }
        }
    }
}

@Composable
private fun EpgRow(p: EpgProgram, now: Long) {
    val live = p.isLiveAt(now)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (live) OnyxSurfaceHi else Color.Transparent)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${fmt(p.start)} – ${fmt(p.stop)}",
            style = MaterialTheme.typography.bodyMedium,
            color = OnyxMuted,
            modifier = Modifier.width(120.dp),
        )
        Text(
            p.title,
            style = MaterialTheme.typography.titleMedium,
            color = if (live) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
        )
    }
}
