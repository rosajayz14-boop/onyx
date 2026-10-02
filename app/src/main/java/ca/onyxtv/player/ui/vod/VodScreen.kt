package ca.onyxtv.player.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.LoadingState
import ca.onyxtv.player.ui.components.MediaCard
import ca.onyxtv.player.ui.components.LocalContextMenu
import ca.onyxtv.player.ui.components.vodMenu
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.viewmodel.OnyxViewModel
import ca.onyxtv.player.viewmodel.hiddenGroups

@Composable
fun VodScreen(
    vm: OnyxViewModel,
    onPlay: (PlayTarget) -> Unit,
    onOpenDetail: (VodItem) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val showMenu = LocalContextMenu.current
    val recents by vm.recents.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val unlocked by vm.unlockedGroups.collectAsStateWithLifecycle()
    val hidden = hiddenGroups(parental, unlocked)
    val vod = remember(state.vod, hidden) { state.vod.filterNot { it.category in hidden } }

    if (state.loading && vod.isEmpty()) {
        LoadingState("Chargement du catalogue…")
        return
    }
    if (vod.isEmpty()) {
        EmptyState(
            title = "Aucun film ou série",
            hint = state.sourceErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: "Les contenus à la demande proviennent des comptes Xtream. Ajoutez-en un dans Réglages, ou « Tout mettre à jour ».",
        )
        return
    }

    var kind by remember { mutableStateOf(MediaKind.MOVIE) }
    val ofKind = remember(vod, kind) { vod.filter { it.kind == kind } }
    val categories = remember(ofKind) { ofKind.mapNotNull { it.category }.distinct() }
    var category by remember(kind) { mutableStateOf<String?>(null) }
    val shown = remember(ofKind, category) { if (category == null) ofKind else ofKind.filter { it.category == category } }
    val progressById = remember(recents) { recents.filter { it.resumable }.associate { it.id to it.progress } }
    val movieCount = remember(vod) { vod.count { it.kind == MediaKind.MOVIE } }
    val seriesCount = remember(vod) { vod.count { it.kind == MediaKind.SERIES } }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Films & Séries", style = MaterialTheme.typography.headlineLarge)
                CategoryChip("Films ($movieCount)", kind == MediaKind.MOVIE) { kind = MediaKind.MOVIE }
                CategoryChip("Séries ($seriesCount)", kind == MediaKind.SERIES) { kind = MediaKind.SERIES }
            }
            Text("${shown.size} titres", color = OnyxMuted, style = MaterialTheme.typography.bodyLarge)
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { CategoryChip("Toutes", category == null) { category = null } }
            items(categories, key = { it }) { c -> CategoryChip(c, category == c) { category = c } }
        }

        if (shown.isEmpty()) {
            EmptyState(
                title = if (kind == MediaKind.SERIES) "Aucune série" else "Aucun film",
                hint = state.sourceErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")
                    ?: "Ce compte ne propose pas ce type de contenu dans cette catégorie. Réglages → « Tout mettre à jour » pour recharger.",
            )
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(shown, key = { it.id }) { v ->
                MediaCard(
                    title = v.name,
                    subtitle = v.category ?: v.year,
                    imageUrl = v.posterUrl,
                    seed = v.id,
                    width = 140.dp,
                    aspectRatio = 2f / 3f,
                    initials = v.name.take(1).uppercase(),
                    progress = progressById[v.id],
                    badge = when {
                        v.id in favorites -> "★"
                        v.kind == MediaKind.SERIES -> "SÉRIE"
                        else -> null
                    },
                    onClick = { onOpenDetail(v) },
                    onLongClick = {
                        showMenu(vodMenu(v, v.id in favorites, openDetail = { onOpenDetail(v) }, play = { onPlay(v.toPlayTarget()) }, toggleFavorite = { vm.toggleFavorite(v.id) }))
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        // Pas de couleur forcée : au focus, le bouton tv-material passe sur fond clair et le
        // texte cyan/blanc devenait invisible. La sélection se lit au préfixe.
        Text(
            if (selected) "● $label" else label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
