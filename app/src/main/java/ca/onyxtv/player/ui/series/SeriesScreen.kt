package ca.onyxtv.player.ui.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.Episode
import ca.onyxtv.player.core.model.SeriesDetail
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.LoadingState
import ca.onyxtv.player.ui.components.Thumbnail
import ca.onyxtv.player.ui.theme.OnyxBg
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.viewmodel.OnyxViewModel

/** Fiche série : affiche, résumé, saisons et épisodes (avec reprise). */
@Composable
fun SeriesScreen(
    vm: OnyxViewModel,
    item: VodItem,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val load by produceState<Result<SeriesDetail?>?>(initialValue = null, item.id) {
        value = runCatching { vm.seriesDetail(item) }
    }

    BackHandler(enabled = true) { onBack() }

    Box(Modifier.fillMaxSize().background(OnyxBg)) {
        val result = load
        val detail = result?.getOrNull()
        when {
            result == null -> LoadingState("Chargement de la série…")
            detail == null || detail.seasons.isEmpty() -> Column(Modifier.fillMaxSize()) {
                EmptyState(
                    title = "Série indisponible",
                    hint = result.exceptionOrNull()?.message?.let { "Le serveur n'a pas répondu ($it)." }
                        ?: "Aucun épisode n'a été renvoyé par le serveur pour cette série.",
                )
                Button(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 36.dp)) { Text("Retour") }
            }
            else -> SeriesContent(
                item = item,
                detail = detail,
                isFavorite = item.id in favorites,
                onToggleFavorite = { vm.toggleFavorite(item.id) },
                progressById = recents.filter { it.resumable }.associate { it.id to it.progress },
                positionById = recents.filter { it.resumable }.associate { it.id to it.positionMs },
                onPlay = onPlay,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun SeriesContent(
    item: VodItem,
    detail: SeriesDetail,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    progressById: Map<String, Float>,
    positionById: Map<String, Long>,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
) {
    val seasons = detail.seasonNumbers
    var season by remember(detail) { mutableIntStateOf(seasons.first()) }
    val episodes = detail.seasons[season].orEmpty()

    fun target(ep: Episode) = PlayTarget(
        id = ep.id,
        url = ep.url,
        title = "S${ep.season}E${ep.number} · ${ep.title}",
        subtitle = detail.name,
        imageUrl = ep.imageUrl ?: detail.coverUrl ?: item.posterUrl,
        isLive = false,
        startPositionMs = positionById[ep.id] ?: 0L,
    )

    // Épisode « suivant » : premier épisode entamé, sinon le premier de la saison.
    val nextUp = episodes.firstOrNull { progressById.containsKey(it.id) } ?: episodes.firstOrNull()

    Row(Modifier.fillMaxSize().padding(32.dp)) {
        Column(Modifier.width(230.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Thumbnail(detail.coverUrl ?: item.posterUrl, item.name.take(1).uppercase(), item.id, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("← Retour") }
        }

        Spacer(Modifier.width(28.dp))

        Column(Modifier.fillMaxHeight()) {
            Text(detail.name, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    item.year,
                    item.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" },
                    item.category,
                    "${seasons.size} saison${if (seasons.size > 1) "s" else ""} · ${detail.episodeCount} épisodes",
                ).joinToString("  ·  "),
                color = OnyxMuted,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            (detail.plot ?: item.plot)?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                nextUp?.let { ep ->
                    Button(onClick = { onPlay(target(ep)) }) {
                        Text(if (progressById.containsKey(ep.id)) "▶ Reprendre S${ep.season}E${ep.number}" else "▶ Lire S${ep.season}E${ep.number}")
                    }
                }
                Button(onClick = onToggleFavorite) {
                    Text(if (isFavorite) "★ Retirer des favoris" else "☆ Ajouter aux favoris")
                }
            }

            if (seasons.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                    items(seasons, key = { it }) { s ->
                        Button(onClick = { season = s }) {
                            Text("Saison $s", color = if (s == season) OnyxCyan else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(episodes, key = { it.id }) { ep ->
                    val progress = progressById[ep.id]
                    ListItem(
                        selected = false,
                        onClick = { onPlay(target(ep)) },
                        headlineContent = {
                            Text("E${ep.number} · ${ep.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val meta = listOfNotNull(
                                ep.durationSecs?.let { "${it / 60} min" },
                                ep.plot?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, maxLines = 2, overflow = TextOverflow.Ellipsis, color = OnyxMuted)
                        },
                        trailingContent = {
                            if (progress != null) Text("${(progress * 100).toInt()} %", color = OnyxCyan)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}
