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
import androidx.compose.runtime.produceState
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
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.ErrorBanner
import ca.onyxtv.player.ui.components.LoadingState
import ca.onyxtv.player.ui.components.MediaCard
import ca.onyxtv.player.ui.components.Rail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.components.LocalContextMenu
import ca.onyxtv.player.ui.components.MenuAction
import ca.onyxtv.player.ui.components.channelMenu
import ca.onyxtv.player.ui.components.vodMenu
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.viewmodel.OnyxViewModel
import ca.onyxtv.player.viewmodel.hiddenGroups
import ca.onyxtv.player.viewmodel.hiddenIds
import coil.compose.AsyncImage

@Composable
fun HomeScreen(
    vm: OnyxViewModel,
    onPlay: (PlayTarget) -> Unit,
    onGoLive: () -> Unit,
    onGoSettings: () -> Unit,
    onOpenDetail: (VodItem) -> Unit,
) {
    // Un film comme une série s'ouvre sur sa fiche (résumé, casting, bande-annonce / épisodes).
    val openVod: (VodItem) -> Unit = { v -> onOpenDetail(v) }
    val showMenu = LocalContextMenu.current
    val state by vm.state.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val unlocked by vm.unlockedGroups.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val hidden = hiddenGroups(parental, unlocked) + prefs.hiddenCategories
    // Les catégories verrouillées (contrôle parental) et les éléments masqués n'apparaissent pas.
    val channels = remember(state.channels, hidden, prefs.hiddenChannelIds) { state.channels.filterNot { it.groupTitle in hidden || it.id in prefs.hiddenChannelIds } }
    val vod = remember(state.vod, hidden) { state.vod.filterNot { it.category in hidden } }

    if (state.loading && channels.isEmpty() && vod.isEmpty()) {
        LoadingState()
        return
    }
    if (channels.isEmpty() && vod.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            (state.error ?: state.sourceErrors.takeIf { it.isNotEmpty() }?.joinToString("\n"))?.let { ErrorBanner(it, Modifier.padding(24.dp)) }
            Box(Modifier.weight(1f)) {
                EmptyState(
                    title = "Bienvenue sur ONYX TV",
                    hint = if (state.reports.isEmpty()) "Ajoutez une liste M3U ou un compte Xtream pour commencer."
                           else "Aucun contenu n'a pu être chargé. Vérifiez vos sources ou relancez la mise à jour.",
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 48.dp),
            ) {
                Button(onClick = onGoSettings) { Text("Ouvrir les Réglages") }
                if (state.reports.isNotEmpty()) Button(onClick = { vm.refresh() }) { Text("🔄 Tout mettre à jour") }
            }
        }
        return
    }

    // Les contenus des catégories verrouillées ne doivent pas réapparaître via « Reprendre ».
    val hiddenIdSet = remember(state.channels, state.vod, hidden) { hiddenIds(state.channels, state.vod, hidden) }
    val visibleRecents = remember(recents, hiddenIdSet) { if (hiddenIdSet.isEmpty()) recents else recents.filterNot { it.id in hiddenIdSet } }
    // Épisodes : un seul par série (le plus récent) dans « Continuer la série » ; le reste dans « Reprendre ».
    val seriesRecents = remember(visibleRecents) { visibleRecents.filter { it.seriesId != null && !it.finished }.distinctBy { it.seriesId } }
    val otherRecents = remember(visibleRecents) { visibleRecents.filter { it.seriesId == null } }
    val vodById = remember(vod) { vod.associateBy { it.id } }
    val resumable = remember(otherRecents) { otherRecents.filter { it.resumable } }
    // Favoris dans L'ORDRE choisi par l'utilisateur (menu appui long : Monter / Descendre).
    val favChannels = remember(channels, favorites) { val byId = channels.associateBy { it.id }; favorites.mapNotNull { byId[it] } }
    // Appui long sur une carte film/série : favoris en premier, puis fiche / lecture.
    val vodLongPress: (VodItem) -> Unit = { v ->
        showMenu(vodMenu(v, v.id in favorites, openDetail = { openVod(v) }, play = { onPlay(v.toPlayTarget()) }, toggleFavorite = { vm.toggleFavorite(v.id) },
            hideCategory = v.category?.let { cat -> { vm.hideCategory(cat, true) } }))
    }
    val channelLongPress: (Channel) -> Unit = { c ->
        showMenu(channelMenu(c, c.id in favorites, play = { onPlay(c.toPlayTarget()) }, toggleFavorite = { vm.toggleFavorite(c.id) },
            hideChannel = { vm.hideChannel(c.id, true) }))
    }
    // Rangée « Mes favoris » : en plus, réordonner.
    fun moveActions(id: String) = listOf(
        MenuAction("⬆ Monter") { vm.moveFavorite(id, -1) },
        MenuAction("⬇ Descendre") { vm.moveFavorite(id, +1) },
    )
    val favVod = remember(vod, favorites) { val byId = vod.associateBy { it.id }; favorites.mapNotNull { byId[it] } }
    val recommendedAll by vm.recommended.collectAsStateWithLifecycle()
    val recommended = remember(recommendedAll, hidden) { recommendedAll.filterNot { it.category in hidden } }
    val tmdbAll by vm.tmdbSuggestions.collectAsStateWithLifecycle()
    val tmdb = remember(tmdbAll, hidden) { tmdbAll.filterNot { it.category in hidden } }
    // Comptes qui expirent bientôt (bandeau d'alerte).
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val expiring = remember(accounts) { accounts.entries.filter { (it.value.daysLeft ?: 99) <= 7 } }
    // Les panneaux Xtream renvoient les films du plus ancien au plus récent : la fin de liste = derniers ajouts.
    val newMovies = remember(vod) { vod.asReversed().asSequence().filter { it.kind == MediaKind.MOVIE }.take(24).toList() }

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
    val heroAction: () -> Unit = {
        when {
            heroResume != null -> onPlay(vm.freshTarget(heroResume))
            heroVod != null -> openVod(heroVod)
            else -> channels.firstOrNull()?.let { onPlay(it.toPlayTarget()) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 36.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 36.dp),
    ) {
        update.info?.let { info ->
            item(key = "update") {
                Text(
                    "✨ Nouvelle version disponible (${info.label}) — Réglages → Application pour l'installer.",
                    color = OnyxCyan,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        if (state.loading && state.hasContent) {
            item(key = "updating") {
                Text(
                    "Mise à jour en cours… ${state.progress ?: ""}",
                    color = OnyxMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        if (expiring.isNotEmpty()) {
            item(key = "expiry") {
                val labels = expiring.map { (id, a) -> (sources.firstOrNull { it.id == id }?.label ?: "Compte") to a }
                Text(
                    labels.joinToString("\n") { (l, a) ->
                        val d = a.daysLeft ?: 0
                        if (d < 0) "⚠ L'abonnement « $l » est expiré." else "⚠ L'abonnement « $l » expire dans $d jour${if (d > 1) "s" else ""} — pensez à le renouveler."
                    },
                    color = OnyxLive, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        if (state.sourceErrors.isNotEmpty() && !state.loading) {
            item(key = "error") {
                Column(Modifier.padding(bottom = 12.dp)) {
                    ErrorBanner(state.sourceErrors.joinToString("\n"))
                    Button(onClick = { vm.refresh() }, modifier = Modifier.padding(top = 8.dp)) { Text("Réessayer la mise à jour") }
                }
            }
        }
        item(key = "hero") {
            Hero(
                kicker = heroKicker,
                title = heroTitle,
                hint = heroHint,
                imageUrl = heroImage,
                onPlay = heroAction,
                onLive = onGoLive,
            )
        }
        if (seriesRecents.isNotEmpty()) {
            item(key = "series") {
                Rail("Continuer la série") {
                    items(seriesRecents.take(20), key = { "s" + it.id }) { r ->
                        val series = r.seriesId?.let { vodById[it] }
                        MediaCard(
                            title = series?.name ?: r.subtitle ?: r.title,
                            subtitle = r.title,
                            imageUrl = series?.posterUrl ?: r.imageUrl,
                            seed = r.seriesId ?: r.id,
                            width = 130.dp,
                            aspectRatio = 2f / 3f,
                            initials = (series?.name ?: r.title).take(1).uppercase(),
                            progress = if (r.resumable) r.progress else null,
                            badge = "SÉRIE",
                            onClick = { onPlay(vm.freshTarget(r)) },
                            onLongClick = {
                                showMenu(
                                    ca.onyxtv.player.ui.components.ContextMenuRequest(
                                        title = series?.name ?: r.title, subtitle = r.title,
                                        actions = buildList {
                                            add(MenuAction(if (r.resumable) "▶ Reprendre l'épisode" else "▶ Lire l'épisode") { onPlay(vm.freshTarget(r)) })
                                            if (series != null) add(MenuAction("Ouvrir la série") { openVod(series) })
                                            add(MenuAction("✕ Retirer") { vm.removeRecent(r.id) })
                                        },
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
        if (otherRecents.isNotEmpty()) {
            item(key = "resume") {
                Rail("Reprendre") {
                    items(otherRecents.take(20), key = { it.id }) { r ->
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
                            onClick = { onPlay(vm.freshTarget(r)) },
                            onLongClick = {
                                val inCatalog = vod.firstOrNull { it.id == r.id } ?: channels.firstOrNull { it.id == r.id }
                                showMenu(
                                    ca.onyxtv.player.ui.components.ContextMenuRequest(
                                        title = r.title, subtitle = r.subtitle,
                                        actions = buildList {
                                            add(MenuAction("✕ Retirer de « Reprendre »") { vm.removeRecent(r.id) })
                                            if (inCatalog != null) add(MenuAction(if (r.id in favorites) "★ Retirer des favoris" else "☆ Ajouter aux favoris") { vm.toggleFavorite(r.id) })
                                            add(MenuAction(if (r.resumable) "▶ Reprendre" else "▶ Lire") { onPlay(vm.freshTarget(r)) })
                                            if (r.resumable) add(MenuAction("↺ Depuis le début") { onPlay(vm.freshTarget(r).copy(startPositionMs = 0L)) })
                                        },
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
        if (favChannels.isNotEmpty() || favVod.isNotEmpty()) {
            item(key = "favorites") {
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
                            onLongClick = { showMenu(channelMenu(c, true, play = { onPlay(c.toPlayTarget()) }, toggleFavorite = { vm.toggleFavorite(c.id) }, extra = moveActions(c.id))) },
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
                            onClick = { openVod(v) },
                            onLongClick = { showMenu(vodMenu(v, true, openDetail = { openVod(v) }, play = { onPlay(v.toPlayTarget()) }, toggleFavorite = { vm.toggleFavorite(v.id) }, extra = moveActions(v.id))) },
                        )
                    }
                }
            }
        }
        if (channels.isNotEmpty()) {
            item(key = "live") {
                Rail("En direct maintenant") {
                    items(channels.take(24), key = { it.id }) { c ->
                        // Programme en cours (guide) sous le nom de la chaîne.
                        val nowTitle by produceState<String?>(null, c.id, state.epgVersion) {
                            val t = System.currentTimeMillis()
                            value = runCatching { vm.epgFor(c) }.getOrNull()?.firstOrNull { it.isLiveAt(t) }?.title
                        }
                        MediaCard(
                            title = (c.number?.let { "$it · " } ?: "") + c.name,
                            subtitle = nowTitle ?: c.groupTitle,
                            imageUrl = c.logoUrl,
                            seed = c.id,
                            initials = c.name.take(2).uppercase(),
                            onClick = { onPlay(c.toPlayTarget()) },
                            onLongClick = { channelLongPress(c) },
                        )
                    }
                }
            }
        }
        if (tmdb.isNotEmpty()) {
            item(key = "tmdb") {
                Rail("Tendances de la semaine", badge = "TMDB") {
                    items(tmdb, key = { "t" + it.id }) { v ->
                        MediaCard(
                            title = v.name,
                            subtitle = v.year ?: v.category,
                            imageUrl = v.posterUrl,
                            seed = v.id,
                            width = 130.dp,
                            aspectRatio = 2f / 3f,
                            initials = v.name.take(1).uppercase(),
                            onClick = { openVod(v) },
                            onLongClick = { vodLongPress(v) },
                        )
                    }
                }
            }
        }
        if (recommended.isNotEmpty()) {
            item(key = "recommended") {
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
                            onClick = { openVod(v) },
                            onLongClick = { vodLongPress(v) },
                        )
                    }
                }
            }
        }
        if (vod.isNotEmpty()) {
            item(key = "new") {
                Rail("Ajouts récents · films") {
                    items(newMovies, key = { "n" + it.id }) { v ->
                        MediaCard(
                            title = v.name,
                            subtitle = v.category ?: v.year,
                            imageUrl = v.posterUrl,
                            seed = "r" + v.id,
                            width = 130.dp,
                            aspectRatio = 2f / 3f,
                            initials = v.name.take(1).uppercase(),
                            onClick = { openVod(v) },
                            onLongClick = { vodLongPress(v) },
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
