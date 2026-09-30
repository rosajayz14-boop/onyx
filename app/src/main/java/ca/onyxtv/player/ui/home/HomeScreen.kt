package ca.onyxtv.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.ErrorBanner
import ca.onyxtv.player.ui.components.LoadingState
import ca.onyxtv.player.ui.components.MediaCard
import ca.onyxtv.player.ui.components.Rail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.viewmodel.OnyxViewModel
import ca.onyxtv.player.viewmodel.recommendVod
import coil.compose.AsyncImage

@Composable
fun HomeScreen(
    vm: OnyxViewModel,
    onPlay: (PlayTarget) -> Unit,
    onGoLive: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val channels = state.channels
    val vod = state.vod

    if (state.loading && channels.isEmpty() && vod.isEmpty()) {
        LoadingState()
        return
    }
    if (channels.isEmpty() && vod.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            state.error?.let { ErrorBanner(it, Modifier.padding(24.dp)) }
            EmptyState(
                title = "Bienvenue sur ONYX TV",
                hint = "Ajoutez une liste M3U ou un compte Xtream dans Réglages pour commencer.",
            )
        }
        return
    }

    val resumable = remember(recents) { recents.filter { it.resumable } }
    val favChannels = remember(channels, favorites) { channels.filter { it.id in favorites } }
    val favVod = remember(vod, favorites) { vod.filter { it.id in favorites } }
    val recommended = remember(vod, favorites, recents) { recommendVod(vod, favorites, recents) }

    // Mise en avant : reprise en cours > film recommandé > première chaîne.
    val heroResume = resumable.firstOrNull()
    val heroVod = recommended.firstOrNull()
    val heroTitle = heroResume?.title ?: heroVod?.name ?: channels.firstOrNull()?.name ?: "ONYX TV"
    val heroKicker = when {
        heroResume != null -> "REPRENDRE LA LECTURE"
        heroVod != null -> "RECOMMANDÉ POUR VOUS"
        else -> "EN DIRECT"
    }
    val heroHint = when {
        heroResume != null -> "Reprenez là où vous vous êtes arrêté (${(heroResume.progress * 100).toInt()} %)."
        heroVod != null -> heroVod.category?.let { "Dans « $it »" } ?: "Sélection du jour."
        else -> "Vos chaînes en direct, prêtes à zapper."
    }
    val heroImage = heroResume?.imageUrl ?: heroVod?.posterUrl
    val heroTarget: PlayTarget? = heroResume?.toPlayTarget() ?: heroVod?.toPlayTarget() ?: channels.firstOrNull()?.toPlayTarget()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 36.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 36.dp),
    ) {
        state.error?.let { err ->
            item { ErrorBanner(err, Modifier.padding(bottom = 12.dp)) }
        }
        item {
            Hero(
                kicker = heroKicker,
                title = heroTitle,
                hint = heroHint,
                imageUrl = heroImage,
                onPlay = { heroTarget?.let(onPlay) },
                onLive = onGoLive,
            )
        }
        if (recents.isNotEmpty()) {
            item {
                Rail("Reprendre") {
                    items(recents.take(20), key = { it.id }) { r ->
                        MediaCard(
                            title = r.title,
                            subtitle = r.subtitle,
                            imageUrl = r.imageUrl,
                            seed = r.id,
                            width = if (r.live) 168.dp else 130.dp,
                            aspectRatio = if (r.live) 16f / 9f else 2f / 3f,
                            initials = r.title.take(2).uppercase(),
                            progress = if (r.resumable) r.progress else null,
                            badge = if (r.live) "DIRECT" else null,
                            onClick = { onPlay(r.toPlayTarget()) },
                        )
                    }
                }
            }
        }
        if (favChannels.isNotEmpty() || favVod.isNotEmpty()) {
            item {
                Rail("Mes favoris", badge = "★") {
                    items(favChannels, key = { "c" + it.id }) { c ->
                        MediaCard(
                            title = (c.number?.let { "$it · " } ?: "") + c.name,
                            subtitle = c.groupTitle,
                            imageUrl = c.logoUrl,
                            seed = c.id,
                            initials = c.name.take(2).uppercase(),
                            badge = "DIRECT",
                            onClick = { onPlay(c.toPlayTarget()) },
                        )
                    }
                    items(favVod, key = { "v" + it.id }) { v ->
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
        }
        if (channels.isNotEmpty()) {
            item {
                Rail("En direct maintenant") {
                    items(channels.take(24), key = { it.id }) { c ->
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
        if (recommended.isNotEmpty()) {
            item {
                Rail("Recommandé pour vous", badge = "ONYX") {
                    items(recommended, key = { it.id }) { v ->
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
        }
        if (vod.isNotEmpty()) {
            item {
                Rail("Nouveautés films") {
                    items(vod.asReversed().take(24), key = { "n" + it.id }) { v ->
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
private fun Hero(
    kicker: String,
    title: String,
    hint: String,
    imageUrl: String?,
    onPlay: () -> Unit,
    onLive: () -> Unit,
) {
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
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(Color(0xE6050509), Color.Transparent)))
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(36.dp)
        ) {
            Text(
                kicker,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                title,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodyLarge,
                color = OnyxMuted,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay) { Text("Lecture") }
                Button(onClick = onLive) { Text("TV en direct") }
            }
        }
    }
}
