package ca.onyxtv.player.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.ErrorBanner
import ca.onyxtv.player.ui.components.LoadingState
import ca.onyxtv.player.ui.components.PinDialog
import ca.onyxtv.player.ui.components.Thumbnail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.components.LocalContextMenu
import ca.onyxtv.player.ui.components.channelMenu
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurfaceHi
import ca.onyxtv.player.viewmodel.OnyxViewModel
import ca.onyxtv.player.viewmodel.hiddenGroups
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HM = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun fmt(ms: Long) = HM.format(Date(ms))

/** Groupes virtuels (en plus des catégories du fournisseur). */
private const val GROUP_ALL = "\u0000all"
private const val GROUP_FAV = "\u0000fav"

@Composable
fun LiveTvScreen(vm: OnyxViewModel, onPlay: (PlayTarget) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val showMenu = LocalContextMenu.current
    val parental by vm.parental.collectAsStateWithLifecycle()
    val unlocked by vm.unlockedGroups.collectAsStateWithLifecycle()
    val hidden = hiddenGroups(parental, unlocked)
    val channels = state.channels

    if (state.loading && channels.isEmpty()) {
        LoadingState("Chargement des chaînes…")
        return
    }
    if (channels.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            state.error?.let { ErrorBanner(it, Modifier.padding(24.dp)) }
            EmptyState(
                title = "Aucune chaîne",
                hint = "Ajoutez une liste M3U ou un compte Xtream dans Réglages.",
            )
        }
        return
    }

    val groups = remember(channels) { channels.mapNotNull { it.groupTitle }.distinct() }
    // Comptages calculés UNE fois (et non catégories × chaînes à chaque affichage).
    val countByGroup = remember(channels) { channels.groupingBy { it.groupTitle }.eachCount() }
    val visibleCount = remember(channels, hidden) { channels.count { it.groupTitle !in hidden } }
    val favCount = remember(channels, favorites, hidden) { channels.count { it.id in favorites && it.groupTitle !in hidden } }
    var group by remember { mutableStateOf(GROUP_ALL) }
    var pendingLocked by remember { mutableStateOf<String?>(null) }
    val filtered = remember(channels, group, favorites, hidden) {
        when (group) {
            GROUP_ALL -> channels.filterNot { it.groupTitle in hidden }
            GROUP_FAV -> channels.filter { it.id in favorites && it.groupTitle !in hidden }
            else -> channels.filter { it.groupTitle == group }
        }
    }
    // Id sélectionné stable : un changement de la liste (favori ajouté/retiré) ne ramène plus
    // la sélection sur la première chaîne alors que le focus est ailleurs.
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = remember(filtered, selectedId) { filtered.firstOrNull { it.id == selectedId } ?: filtered.firstOrNull() }
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    fun selectGroup(g: String) {
        if (g in hidden) pendingLocked = g else group = g
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(24.dp)) {
            // Colonne des catégories
            LazyColumn(modifier = Modifier.width(230.dp).fillMaxHeight()) {
                item {
                    Text("Catégories", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                }
                item { GroupItem("Toutes", visibleCount, group == GROUP_ALL) { group = GROUP_ALL } }
                item { GroupItem("★ Favoris", favCount, group == GROUP_FAV) { group = GROUP_FAV } }
                items(groups, key = { it }) { g ->
                    val locked = parental.enabled && g in parental.lockedGroups
                    GroupItem(
                        name = if (locked) "🔒 $g" else g,
                        count = countByGroup[g] ?: 0,
                        selected = group == g,
                    ) { selectGroup(g) }
                }
            }

            Spacer(Modifier.width(16.dp))

            // Liste des chaînes du groupe
            if (filtered.isEmpty()) {
                Box(Modifier.width(380.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        if (group == GROUP_FAV) "Aucun favori. Sélectionnez une chaîne puis « Ajouter aux favoris »."
                        else "Aucune chaîne dans cette catégorie.",
                        color = OnyxMuted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.width(380.dp).fillMaxHeight()) {
                    items(filtered, key = { it.id }) { c ->
                        ListItem(
                            selected = c.id == selected?.id,
                            onClick = { onPlay(c.toPlayTarget()) },
                            onLongClick = { showMenu(channelMenu(c, c.id in favorites, play = { onPlay(c.toPlayTarget()) }, toggleFavorite = { vm.toggleFavorite(c.id) })) },
                            leadingContent = {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))) {
                                    Thumbnail(c.logoUrl, c.name.take(2).uppercase(), c.id, Modifier.fillMaxSize())
                                }
                            },
                            headlineContent = {
                                Text((c.number?.let { "$it · " } ?: "") + c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { NowNextLine(vm, c, state.epgVersion) },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (c.archiveDays > 0) Text("↺", color = OnyxMuted)
                                    if (c.id in favorites) Text("★", color = OnyxCyan)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .onFocusChanged { if (it.isFocused) selectedId = c.id },
                        )
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            selected?.let { ch ->
                EpgPanel(
                    vm = vm,
                    channel = ch,
                    preview = prefs.livePreview,
                    epgVersion = state.epgVersion,
                    isFavorite = ch.id in favorites,
                    onToggleFavorite = { vm.toggleFavorite(ch.id) },
                    onPlay = onPlay,
                )
            }
        }

        pendingLocked?.let { g ->
            PinDialog(
                title = "Catégorie verrouillée",
                subtitle = g,
                onSubmit = { pin ->
                    val ok = vm.unlockGroup(g, pin)
                    if (ok) { group = g; pendingLocked = null }
                    ok
                },
                onCancel = { pendingLocked = null },
            )
        }
    }
}

@Composable
private fun GroupItem(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(count.toString(), color = OnyxMuted, style = MaterialTheme.typography.bodyMedium) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** « ● En cours » avec barre de progression, puis « À suivre », sous le nom de la chaîne. */
@Composable
private fun NowNextLine(vm: OnyxViewModel, c: Channel, epgVersion: Int) {
    val programs by produceState(initialValue = emptyList<EpgProgram>(), c.id, epgVersion) {
        value = runCatching { vm.epgFor(c) }.getOrDefault(emptyList())
    }
    val now = System.currentTimeMillis()
    val cur = programs.firstOrNull { it.isLiveAt(now) }
    val next = programs.firstOrNull { it.start >= (cur?.stop ?: now) }
    if (cur == null) {
        c.groupTitle?.let { Text(it, maxLines = 1, color = OnyxMuted, overflow = TextOverflow.Ellipsis) }
        return
    }
    Column {
        Text("● " + cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth(0.9f).padding(top = 3.dp, bottom = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(OnyxSurfaceHi)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(cur.progressAt(now)).background(OnyxCyan))
        }
        next?.let { Text("À suivre : ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = OnyxMuted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun EpgPanel(
    vm: OnyxViewModel,
    channel: Channel,
    preview: Boolean,
    epgVersion: Int,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val programs by produceState(initialValue = emptyList<EpgProgram>(), channel.id, epgVersion) {
        value = runCatching { vm.epgFor(channel) }.getOrDefault(emptyList())
    }
    val now = System.currentTimeMillis()
    val current = programs.firstOrNull { it.isLiveAt(now) }
    var recordNote by remember(channel.id) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            if (preview) ChannelPreview(channel, Modifier.fillMaxSize())
            else Thumbnail(channel.logoUrl, channel.name.take(2).uppercase(), channel.id, Modifier.fillMaxSize())
        }

        Text(
            channel.name,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 16.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(channel.groupTitle, channel.archiveDays.takeIf { it > 0 }?.let { "Rattrapage $it j" }).joinToString("  ·  "),
            color = OnyxMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        current?.let { p ->
            Text("● EN DIRECT · ${p.title}", color = OnyxLive, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .padding(top = 6.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(OnyxSurfaceHi)
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.progressAt(now)).background(OnyxCyan))
            }
            Text("${fmt(p.start)} – ${fmt(p.stop)}", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) {
            Button(onClick = { onPlay(channel.toPlayTarget()) }) { Text("Regarder") }
            Button(onClick = onToggleFavorite) {
                Text(if (isFavorite) "★ Retirer" else "☆ Favori")
            }
            Button(onClick = {
                vm.startRecording(channel, 60)
                recordNote = "Enregistrement lancé (1 h) — voir « Enregistrements »."
            }) { Text("● Enregistrer 1 h") }
        }
        recordNote?.let { Text(it, color = OnyxLive, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }

        if (programs.isEmpty()) {
            Text("Guide indisponible pour cette chaîne.", color = OnyxMuted)
        } else {
            LazyColumn {
                items(programs) { p ->
                    val catchup = channel.archiveDays > 0 && p.stop <= now
                    EpgRow(
                        p = p,
                        live = p.isLiveAt(now),
                        catchup = catchup,
                        onCatchup = {
                            scope.launch { vm.catchupTarget(channel, p)?.let(onPlay) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgRow(p: EpgProgram, live: Boolean, catchup: Boolean, onCatchup: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (live) OnyxSurfaceHi else Color.Transparent)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${fmt(p.start)} – ${fmt(p.stop)}",
                style = MaterialTheme.typography.bodyMedium,
                color = OnyxMuted,
                modifier = Modifier.width(120.dp),
            )
            Text(
                p.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (live) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (catchup) Text("↺ Revoir", color = OnyxCyan, style = MaterialTheme.typography.labelLarge)
        }
    }
    // Toujours une carte focalisable : sans cela (pas de rattrapage), la liste ne défilait pas
    // à la télécommande et seuls les premiers programmes étaient visibles.
    Card(onClick = { if (catchup) onCatchup() }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
}
