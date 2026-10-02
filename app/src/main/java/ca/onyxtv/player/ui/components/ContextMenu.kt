package ca.onyxtv.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface
import ca.onyxtv.player.ui.theme.OnyxText

/** Une entrée du menu contextuel (appui long sur OK). */
data class MenuAction(val label: String, val run: () -> Unit)

/** Menu contextuel demandé par un écran : titre de l'élément + actions. */
data class ContextMenuRequest(val title: String, val subtitle: String? = null, val actions: List<MenuAction>)

/**
 * Ouvre un menu contextuel au-dessus de l'écran courant. Fourni par la racine (OnyxRoot).
 * Le menu N'UTILISE PAS le focus Compose : l'élément d'origine garde le focus, la racine
 * intercepte les touches (▲ ▼ OK Retour). À la fermeture, le focus est donc exactement
 * où il était (pas de « sélectionné nulle part »).
 */
val LocalContextMenu = compositionLocalOf<(ContextMenuRequest) -> Unit> { {} }

/** Actions standard pour un film ou une série. */
fun vodMenu(
    item: VodItem,
    isFavorite: Boolean,
    openDetail: () -> Unit,
    play: (() -> Unit)?,
    toggleFavorite: () -> Unit,
    extra: List<MenuAction> = emptyList(),
    hideCategory: (() -> Unit)? = null,
): ContextMenuRequest = ContextMenuRequest(
    title = item.name,
    subtitle = listOfNotNull(if (item.kind == MediaKind.SERIES) "Série" else "Film", item.year, item.category).joinToString(" · "),
    actions = buildList {
        add(MenuAction(if (isFavorite) "★ Retirer des favoris" else "☆ Ajouter aux favoris", toggleFavorite))
        addAll(extra)
        add(MenuAction("Ouvrir la fiche", openDetail))
        if (play != null) add(MenuAction("▶ Lire maintenant", play))
        if (hideCategory != null && item.category != null) add(MenuAction("🙈 Masquer la catégorie « ${item.category} »", hideCategory))
    },
)

/** Actions standard pour une chaîne. */
fun channelMenu(
    channel: Channel,
    isFavorite: Boolean,
    play: () -> Unit,
    toggleFavorite: () -> Unit,
    extra: List<MenuAction> = emptyList(),
    hideChannel: (() -> Unit)? = null,
): ContextMenuRequest = ContextMenuRequest(
    title = (channel.number?.let { "$it · " } ?: "") + channel.name,
    subtitle = channel.groupTitle,
    actions = buildList {
        add(MenuAction(if (isFavorite) "★ Retirer des favoris" else "☆ Ajouter aux favoris", toggleFavorite))
        addAll(extra)
        add(MenuAction("▶ Regarder", play))
        if (hideChannel != null) add(MenuAction("🙈 Masquer cette chaîne", hideChannel))
    },
)

/** Dessin du menu (aucun élément focalisable : la sélection est pilotée par la racine). */
@Composable
fun ContextMenuOverlay(request: ContextMenuRequest, selected: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3050509)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(OnyxSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(request.title, style = MaterialTheme.typography.headlineSmall, color = OnyxText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            request.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.padding(top = 10.dp))
            request.actions.forEachIndexed { i, a ->
                val on = i == selected
                Text(
                    a.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Color.Black else OnyxText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) OnyxCyan else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Text("▲ ▼ choisir · OK valider · Retour annuler", color = OnyxMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
