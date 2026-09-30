package ca.onyxtv.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.MediaCard
import ca.onyxtv.player.ui.components.Rail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.viewmodel.OnyxViewModel

@Composable
fun HomeScreen(
    vm: OnyxViewModel,
    onPlay: (PlayTarget) -> Unit,
    onGoLive: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val channels = state.channels
    val vod = state.vod

    if (!state.loading && channels.isEmpty() && vod.isEmpty()) {
        EmptyState(
            title = "Bienvenue sur ONYX TV",
            hint = "Ajoutez une liste M3U ou un compte Xtream dans Réglages pour commencer.",
        )
        return
    }

    val featuredTitle = vod.firstOrNull()?.name ?: channels.firstOrNull()?.name ?: "ONYX TV"
    val featuredTarget = vod.firstOrNull()?.toPlayTarget() ?: channels.firstOrNull()?.toPlayTarget()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 36.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 36.dp),
    ) {
        item {
            Hero(
                title = featuredTitle,
                onPlay = { featuredTarget?.let(onPlay) },
                onLive = onGoLive,
            )
        }
        if (channels.isNotEmpty()) {
            item {
                Rail("En direct maintenant") {
                    items(channels.take(24)) { c ->
                        MediaCard(
                            title = (c.number?.let { "$it · " } ?: "") + c.name,
                            subtitle = c.groupTitle,
                            imageUrl = c.logoUrl,
                            seed = c.id,
                            initials = c.name.take(2).uppercase(),
                            onClick = { onPlay(c.toPlayTarget()) },
                        )
                    }
                }
            }
        }
        if (vod.isNotEmpty()) {
            item {
                Rail("Recommandé pour vous", badge = "IA ONYX") {
                    items(vod.take(24)) { v ->
                        MediaCard(
                            title = v.name,
                            subtitle = v.category ?: v.year,
                            imageUrl = v.posterUrl,
                            seed = v.id,
                            width = 130.dp,
                            aspectRatio = 2f / 3f,
                            initials = v.name.take(1).uppercase(),
                            onClick = { onPlay(v.toPlayTarget()) },
                        )
                    }
                }
            }
            item {
                Rail("Films & séries") {
                    items(vod.asReversed().take(24)) { v ->
                        MediaCard(
                            title = v.name,
                            subtitle = v.category ?: v.year,
                            imageUrl = v.posterUrl,
                            seed = "r" + v.id,
                            width = 130.dp,
                            aspectRatio = 2f / 3f,
                            initials = v.name.take(1).uppercase(),
                            onClick = { onPlay(v.toPlayTarget()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(title: String, onPlay: () -> Unit, onLive: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF3A1D6E), Color(0xFF0C1030), Color(0xFF08202E))
                )
            )
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(36.dp)
        ) {
            Text(
                "RECOMMANDÉ PAR L'IA ONYX",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                title,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "Reprenez là où vous vous êtes arrêté.",
                style = MaterialTheme.typography.bodyLarge,
                color = OnyxMuted,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onPlay) { Text("Lecture") }
                Button(onClick = onLive) { Text("TV en direct") }
            }
        }
    }
}
