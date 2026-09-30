package ca.onyxtv.player.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme as Md3
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.darkColorScheme as md3DarkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.MediaCard
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.viewmodel.OnyxViewModel
import ca.onyxtv.player.viewmodel.hiddenGroups

private data class SearchHit(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val seed: String,
    val initials: String,
    val aspect: Float,
    val badge: String?,
    val target: PlayTarget?,
    val series: VodItem?,
)

@Composable
fun SearchScreen(
    vm: OnyxViewModel,
    onPlay: (PlayTarget) -> Unit,
    onOpenSeries: (VodItem) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val unlocked by vm.unlockedGroups.collectAsStateWithLifecycle()
    val hidden = hiddenGroups(parental, unlocked)
    var query by remember { mutableStateOf("") }

    val results = remember(query, state, hidden) {
        val q = query.trim().lowercase()
        if (q.length < 2) emptyList()
        else buildList {
            state.channels.filterNot { it.groupTitle in hidden }.forEach {
                add(SearchHit(it.name, it.groupTitle, it.logoUrl, it.id, it.name.take(2).uppercase(), 16f / 9f, "DIRECT", it.toPlayTarget(), null))
            }
            state.vod.filterNot { it.category in hidden }.forEach {
                val isSeries = it.kind == MediaKind.SERIES
                add(
                    SearchHit(
                        it.name, it.category ?: it.year, it.posterUrl, it.id, it.name.take(1).uppercase(), 2f / 3f,
                        if (isSeries) "SÉRIE" else null,
                        if (isSeries) null else it.toPlayTarget(),
                        if (isSeries) it else null,
                    )
                )
            }
        }.filter { it.title.lowercase().contains(q) }.take(80)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp)) {
        Md3(colorScheme = md3DarkColors()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { androidx.compose.material3.Text("Rechercher une chaîne, un film, une série…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (results.isEmpty()) {
            Text(
                when {
                    query.isBlank() -> "Tapez pour rechercher dans vos chaînes et contenus."
                    query.trim().length < 2 -> "Tapez au moins 2 caractères."
                    else -> "Aucun résultat pour « $query »."
                },
                color = OnyxMuted,
                modifier = Modifier.padding(top = 20.dp),
            )
        } else {
            Text("${results.size} résultat${if (results.size > 1) "s" else ""}", color = OnyxMuted, modifier = Modifier.padding(top = 12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(results, key = { it.seed }) { hit ->
                    MediaCard(
                        title = hit.title,
                        subtitle = hit.subtitle,
                        imageUrl = hit.image,
                        seed = hit.seed,
                        width = 150.dp,
                        aspectRatio = hit.aspect,
                        initials = hit.initials,
                        badge = hit.badge,
                        onClick = {
                            hit.series?.let(onOpenSeries) ?: hit.target?.let(onPlay)
                        },
                    )
                }
            }
        }
    }
}
