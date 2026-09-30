package ca.onyxtv.player.ui.vod

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.MediaCard
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.viewmodel.OnyxViewModel

@Composable
fun VodScreen(vm: OnyxViewModel, onPlay: (PlayTarget) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val vod = state.vod

    if (vod.isEmpty()) {
        EmptyState(
            title = "Aucun film ou série",
            hint = "Les contenus à la demande proviennent des comptes Xtream. Ajoutez-en un dans Réglages.",
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        contentPadding = PaddingValues(vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(vod) { v ->
            MediaCard(
                title = v.name,
                subtitle = v.category ?: v.year,
                imageUrl = v.posterUrl,
                seed = v.id,
                width = 140.dp,
                aspectRatio = 2f / 3f,
                initials = v.name.take(1).uppercase(),
                onClick = { onPlay(v.toPlayTarget()) },
            )
        }
    }
}
