package ca.onyxtv.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import ca.onyxtv.player.core.data.RecentItem
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface
import ca.onyxtv.player.ui.theme.OnyxViolet
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs

// ---- Conversions vers une cible de lecture ----
fun Channel.toPlayTarget() = PlayTarget(
    id = id,
    url = url,
    title = (number?.let { "$it · " } ?: "") + name,
    subtitle = groupTitle,
    imageUrl = logoUrl,
    isLive = true,
)

fun VodItem.toPlayTarget() = PlayTarget(
    id = id,
    url = url,
    title = name,
    subtitle = category ?: year,
    imageUrl = posterUrl,
    isLive = false,
)

fun RecentItem.toPlayTarget() = PlayTarget(
    id = id,
    url = url,
    title = title,
    subtitle = subtitle,
    imageUrl = imageUrl,
    isLive = live,
    startPositionMs = if (resumable) positionMs else 0L,
)

// ---- Dégradés de repli pour les vignettes sans logo/affiche ----
private val cardBrushes = listOf(
    listOf(Color(0xFF3A1D6E), Color(0xFF0C1030)),
    listOf(Color(0xFF0B3A53), Color(0xFF08202E)),
    listOf(Color(0xFF5A1836), Color(0xFF1A0A2E)),
    listOf(Color(0xFF123A2E), Color(0xFF07211B)),
    listOf(Color(0xFF1A2A5C), Color(0xFF070B1E)),
    listOf(Color(0xFF4A1030), Color(0xFF10061E)),
).map { Brush.linearGradient(it) }

fun brushFor(seed: String): Brush = cardBrushes[Math.floorMod(seed.hashCode(), cardBrushes.size)]

@Composable
fun Thumbnail(
    imageUrl: String?,
    initials: String?,
    seed: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(brushFor(seed))) {
        if (!imageUrl.isNullOrBlank()) {
            // Décodage en taille réduite : évite les bitmaps géants (logos 2000 px) qui font planter les box.
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(imageUrl).size(360).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!initials.isNullOrBlank()) {
            Text(
                text = initials,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Carte de contenu générique (chaîne, film, série…). */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp,
    aspectRatio: Float = 16f / 9f,
    initials: String? = null,
    /** Progression de reprise 0f..1f (barre en bas de la vignette), null = aucune. */
    progress: Float? = null,
    /** Petit badge en haut à gauche (ex. « ★ », « DIRECT »). */
    badge: String? = null,
    /** Appui long sur OK (menu contextuel : favoris, retirer des récents…). */
    onLongClick: (() -> Unit)? = null,
) {
    Card(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(width)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                Thumbnail(imageUrl, initials, seed, Modifier.fillMaxSize())
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(badge, style = MaterialTheme.typography.labelLarge, color = OnyxCyan)
                    }
                }
                if (progress != null && progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x66000000))
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(Brush.horizontalGradient(listOf(OnyxViolet, OnyxCyan)))
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnyxMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** En-tête de section avec badge optionnel (ex. « IA »). */
@Composable
fun RailHeader(title: String, badge: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (badge != null) {
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(badge, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/** Rangée horizontale défilable. */
@Composable
fun Rail(
    title: String,
    badge: String? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.padding(vertical = 12.dp)) {
        RailHeader(title, badge)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp),   // marge pour la carte agrandie au focus
            content = content,
        )
    }
}

/** État vide (aucune source configurée). */
@Composable
fun EmptyState(title: String, hint: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = OnyxMuted)
        }
    }
}

/** État de chargement plein écran. */
@Composable
fun LoadingState(message: String = "Chargement de vos contenus…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = OnyxCyan)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = OnyxMuted)
        }
    }
}

/** Bandeau d'erreur discret en haut d'un écran. */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OnyxSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = OnyxLive, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
