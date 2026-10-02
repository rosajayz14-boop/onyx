package ca.onyxtv.player.ui.movie

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import ca.onyxtv.player.core.model.MovieDetail
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.Thumbnail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxBg
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxViolet
import ca.onyxtv.player.viewmodel.OnyxViewModel
import coil.compose.AsyncImage

/** Note en étoiles « ★★★★☆ » sur 5 (demi-étoile arrondie). */
private fun starsText(stars: Float): String {
    val full = stars.toInt()
    val half = stars - full >= 0.5f
    return "★".repeat(full) + (if (half) "½" else "") + "☆".repeat(5 - full - if (half) 1 else 0)
}

/**
 * Fiche film : affiche, fond, titre, note en étoiles, genre, durée, résumé, réalisateur,
 * acteurs, bande-annonce (YouTube) et lecture / reprise.
 */
@Composable
fun MovieScreen(
    vm: OnyxViewModel,
    item: VodItem,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
    /** false tant que le lecteur est ouvert par-dessus : à son retour (true), on refocalise « Lire ». */
    active: Boolean = true,
) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val detail by produceState<MovieDetail?>(initialValue = null, item.id) {
        value = runCatching { vm.movieDetail(item) }.getOrNull() ?: MovieDetail(plot = item.plot)
    }
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Bande-annonce : YouTube lu dans l'app (lecteur embarqué) ; lien direct (mp4…) lu par ONYX.
    var trailerId by remember { mutableStateOf<String?>(null) }
    // Focus sur « Lire » à l'ouverture, au retour de la bande-annonce ET au retour du lecteur.
    // On vérifie le focus RÉEL (onFocusChanged) : requestFocus() renvoie Unit, « isSuccess »
    // voulait seulement dire « requester attaché », pas « focalisé ».
    var playFocused by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(item.id, trailerId, active) {
        if (trailerId != null || !active) return@LaunchedEffect
        repeat(12) {
            androidx.compose.runtime.withFrameNanos { }
            if (playFocused) return@LaunchedEffect
            runCatching { playFocus.requestFocus() }
            kotlinx.coroutines.delay(80)
        }
    }
    val recent = recents.firstOrNull { it.id == item.id && it.resumable }
    val seen = recents.any { it.id == item.id && it.finished }

    BackHandler(enabled = true) { onBack() }
    fun openTrailer(url: String) {
        val id = youtubeId(url)
        if (id != null) trailerId = id
        else onPlay(PlayTarget(url = url, title = "Bande-annonce · ${item.name}", subtitle = item.name, imageUrl = item.posterUrl))
    }

    trailerId?.let { id ->
        TrailerScreen(videoId = id, title = item.name, onBack = { trailerId = null })
        return
    }

    Box(Modifier.fillMaxSize().background(OnyxBg)) {
        val backdrop = detail?.backdropUrl ?: item.posterUrl
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                alpha = 0.28f, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(OnyxBg, Color.Transparent))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, OnyxBg))))
        }

        Row(Modifier.fillMaxSize().padding(36.dp)) {
            Column(Modifier.width(240.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                    Thumbnail(item.posterUrl, item.name.take(1).uppercase(), item.id, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("← Retour") }
            }

            Spacer(Modifier.width(32.dp))

            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text(item.name, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)

                val d = detail
                val stars = d?.stars
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (stars != null && d.rating != null) {
                        Text(starsText(stars), color = Color(0xFFFFC94D), style = MaterialTheme.typography.titleLarge)
                        Text(String.format(java.util.Locale.getDefault(), "%.1f / 10", d.rating), color = OnyxMuted, style = MaterialTheme.typography.bodyLarge)
                    } else if (d == null) {
                        Text("Chargement de la fiche…", color = OnyxMuted)
                    }
                }
                Text(
                    listOfNotNull(
                        d?.releaseDate?.take(4) ?: item.year,
                        d?.genre,
                        d?.duration,
                        item.category,
                    ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    color = OnyxMuted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                    Button(
                        // URL ACTUELLE du catalogue + position mémorisée (l'URL stockée dans le récent peut être périmée).
                        onClick = { onPlay(item.toPlayTarget().copy(startPositionMs = recent?.positionMs ?: 0L)) },
                        modifier = Modifier.focusRequester(playFocus).onFocusChanged { playFocused = it.isFocused },
                    ) {
                        Text(if (recent != null) "▶ Reprendre (${(recent.progress * 100).toInt()} %)" else "▶ Lire")
                    }
                    if (recent != null) Button(onClick = { onPlay(item.toPlayTarget()) }) { Text("↺ Depuis le début") }
                    if (seen && recent == null) Text("✓ Vu", color = OnyxMuted, modifier = Modifier.padding(start = 6.dp))
                    d?.trailerUrl?.let { url -> Button(onClick = { openTrailer(url) }) { Text("🎬 Bande-annonce") } }
                    Button(onClick = { vm.toggleFavorite(item.id) }) {
                        Text(if (item.id in favorites) "★ Retirer des favoris" else "☆ Ajouter aux favoris")
                    }
                }

                (d?.plot ?: item.plot)?.takeIf { it.isNotBlank() }?.let {
                    Text("Synopsis", color = OnyxCyan, style = MaterialTheme.typography.titleMedium)
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                }
                d?.director?.takeIf { it.isNotBlank() }?.let {
                    Text("Réalisation", color = OnyxCyan, style = MaterialTheme.typography.titleMedium)
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                }
                d?.cast?.takeIf { it.isNotBlank() }?.let {
                    Text("Avec", color = OnyxCyan, style = MaterialTheme.typography.titleMedium)
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                }
                if (d != null && d.plot.isNullOrBlank() && d.cast.isNullOrBlank() && d.trailerUrl == null) {
                    Text("Le fournisseur ne renseigne pas de fiche détaillée pour ce film.", color = OnyxMuted)
                }
                if (d?.trailerUrl == null && d != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(OnyxViolet.copy(alpha = 0.15f)).padding(10.dp)) {
                        Text("Pas de bande-annonce fournie pour ce titre.", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
