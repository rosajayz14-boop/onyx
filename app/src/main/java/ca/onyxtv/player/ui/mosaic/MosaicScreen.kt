package ca.onyxtv.player.ui.mosaic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.Thumbnail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxLive

/**
 * Mosaïque multi-écran. Affiche plusieurs chaînes en direct sur un seul écran.
 * Le focus/OK ouvre la chaîne en plein écran.
 *
 * NOTE v2 : la lecture simultanée de N flux dépend fortement du décodeur matériel du boîtier.
 * On instancie ici une grille de tuiles (aperçu + info) ; brancher un ExoPlayer par tuile
 * est une amélioration prévue (voir docs/ROADMAP.md).
 */
@Composable
fun MosaicScreen(vm: ca.onyxtv.player.viewmodel.OnyxViewModel, onPlay: (PlayTarget) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val channels = state.channels

    if (channels.isEmpty()) {
        EmptyState("Mosaïque indisponible", "Ajoutez des chaînes dans Réglages pour utiliser le multi-écran.")
        return
    }

    var cols by remember { mutableIntStateOf(2) }
    val count = cols * cols
    val shown = channels.take(count)

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            Button(onClick = { cols = 2 }) { Text("2 × 2") }
            Button(onClick = { cols = 3 }) { Text("3 × 3") }
            Text(
                "Plusieurs chaînes en direct, un seul écran.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown) { c ->
                Card(onClick = { onPlay(c.toPlayTarget()) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        Thumbnail(c.logoUrl, c.name.take(2).uppercase(), c.id, Modifier.fillMaxSize())
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
                                (c.number?.let { "$it · " } ?: "") + c.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                            )
                            Text("● DIRECT", color = OnyxLive, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
