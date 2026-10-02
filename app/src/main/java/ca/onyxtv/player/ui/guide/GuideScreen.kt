package ca.onyxtv.player.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.components.EmptyState
import ca.onyxtv.player.ui.components.LoadingState
import ca.onyxtv.player.ui.components.Thumbnail
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface
import ca.onyxtv.player.ui.theme.OnyxSurfaceHi
import ca.onyxtv.player.ui.theme.OnyxViolet
import ca.onyxtv.player.viewmodel.OnyxViewModel
import ca.onyxtv.player.viewmodel.hiddenGroups
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Géométrie de la grille ----
private const val DP_PER_MIN = 6                 // 30 min = 180 dp
private val CHANNEL_COL = 220.dp
private val ROW_H = 66.dp
private const val WINDOW_BEFORE_MIN = 30L        // marge avant « maintenant »
private const val WINDOW_MIN = 6 * 60L           // fenêtre visible/défilable : 6 h
private const val SLOT_MIN = 30L
private const val MS_PER_MIN = 60_000L

private const val GROUP_ALL = "\u0000all"
private const val GROUP_FAV = "\u0000fav"

private val HM = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun fmt(ms: Long) = HM.format(Date(ms))
private fun minutesToDp(minutes: Long) = (minutes * DP_PER_MIN).toInt().dp

/** Élément focalisé dans la grille : une chaîne et, si disponible, un programme. */
private data class GuideFocus(val channel: Channel, val program: EpgProgram?)

/**
 * Guide TV en grille (style Helix / TiviMate) : chaînes en lignes, temps en colonnes,
 * blocs proportionnels à la durée, ligne « maintenant », fiche du programme focalisé.
 * OK sur un programme : regarder (direct), revoir (rattrapage) ou enregistrer.
 */
@Composable
fun GuideScreen(vm: OnyxViewModel, onPlay: (PlayTarget) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val unlocked by vm.unlockedGroups.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val hidden = hiddenGroups(parental, unlocked) + prefs.hiddenCategories
    val channels = remember(state.channels, hidden, prefs.hiddenChannelIds) { state.channels.filterNot { it.groupTitle in hidden || it.id in prefs.hiddenChannelIds } }
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val reminderIds = remember(reminders) { reminders.mapTo(HashSet()) { it.id } }

    if (state.loading && channels.isEmpty()) { LoadingState("Chargement du guide…"); return }
    if (channels.isEmpty()) {
        EmptyState("Guide indisponible", "Ajoutez une liste M3U (avec EPG) ou un compte Xtream dans Réglages.")
        return
    }

    // Horloge de la grille (rafraîchie chaque minute) et fenêtre temporelle.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    // Période affichée : maintenant, ce soir (20 h), demain (même heure).
    var dayMode by remember { mutableIntStateOf(0) }
    val windowStart = remember(now / (SLOT_MIN * MS_PER_MIN), dayMode) {
        val slot = SLOT_MIN * MS_PER_MIN
        val base = when (dayMode) {
            1 -> java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 20); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis - WINDOW_BEFORE_MIN * MS_PER_MIN
            2 -> now + 24 * 3_600_000L - WINDOW_BEFORE_MIN * MS_PER_MIN
            else -> now - WINDOW_BEFORE_MIN * MS_PER_MIN
        }
        (base / slot) * slot
    }
    val windowEnd = windowStart + WINDOW_MIN * MS_PER_MIN
    val nowX = if (now in windowStart..windowEnd) minutesToDp((now - windowStart) / MS_PER_MIN) else (-100).dp

    val groups = remember(channels) { channels.mapNotNull { it.groupTitle }.distinct() }
    var group by remember { mutableStateOf(GROUP_ALL) }
    val shown = remember(channels, group, favorites) {
        when (group) {
            GROUP_ALL -> channels
            GROUP_FAV -> channels.filter { it.id in favorites }
            else -> channels.filter { it.groupTitle == group }
        }
    }

    var focus by remember { mutableStateOf<GuideFocus?>(null) }
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Position initiale : un peu avant « maintenant ».
    LaunchedEffect(Unit) {
        val px = with(density) { minutesToDp(WINDOW_BEFORE_MIN - 20).toPx() }
        hScroll.scrollTo(px.toInt().coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp)) {
        val epgStatus by vm.epgStatus.collectAsStateWithLifecycle()
        epgStatus?.let { st ->
            val stale = st.coverageEnd in 1 until now
            if (st.matched == 0 || stale || prefs.diagnostics) {
                val endTxt = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(st.coverageEnd))
                Text(
                    when {
                        st.programmes == 0 -> "Guide vide : ${st.detail.ifBlank { "le serveur n'a renvoyé aucun programme" }} — Réglages → « Tester le guide » pour le détail."
                        stale -> "Le guide du fournisseur s'arrête le $endTxt (périmé côté serveur). Ajoutez un guide supplémentaire : Réglages → votre compte → « URL du guide supplémentaire »."
                        else -> "Guide : ${st.programmes} programmes · ${st.guideChannels} chaînes · appariées ${st.matched}/${st.checked}" +
                            (if (st.matched == 0) " — aucune chaîne du compte ne correspond aux identifiants du guide (Réglages → « Tester le guide »)." else "")
                    },
                    color = if (st.matched == 0 || stale) OnyxLive else OnyxMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (prefs.diagnostics && shown.isNotEmpty()) {
            val diag by produceState("EPG DIAG · …", shown.first().id, state.epgVersion) {
                value = "EPG DIAG · " + runCatching { vm.epgDiag(shown.first()) }.getOrElse { "err ${it.message}" }
            }
            Text(diag, color = OnyxCyan, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        DetailsPanel(
            focus = focus,
            now = now,
            onWatch = { ch -> onPlay(ch.toPlayTarget()) },
            onCatchup = { ch, p -> scope.launch { vm.catchupTarget(ch, p)?.let(onPlay) } },
            onRecord = { ch, p ->
                val remaining = ((p.stop - now) / MS_PER_MIN).toInt().coerceIn(1, 5 * 60 + 45)
                vm.startRecording(ch, remaining)
            },
            reminderIds = reminderIds,
            onRemind = { ch, p -> vm.toggleReminder(ch, p, record = false) },
            onSchedule = { ch, p -> vm.toggleReminder(ch, p, record = true) },
        )

        // Filtres de catégorie
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { Chip("Maintenant", dayMode == 0) { dayMode = 0 } }
            item { Chip("Ce soir", dayMode == 1) { dayMode = 1 } }
            item { Chip("Demain", dayMode == 2) { dayMode = 2 } }
            item { Text("│", color = OnyxMuted, modifier = Modifier.padding(horizontal = 4.dp)) }
            item { Chip("Toutes", group == GROUP_ALL) { group = GROUP_ALL } }
            item { Chip("★ Favoris", group == GROUP_FAV) { group = GROUP_FAV } }
            items(groups, key = { it }) { g -> Chip(g, group == g) { group = g } }
        }

        // En-tête des heures (défile avec les lignes)
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            Box(Modifier.width(CHANNEL_COL), contentAlignment = Alignment.CenterStart) {
                Text("${shown.size} chaînes · " + java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(windowStart + WINDOW_BEFORE_MIN * MS_PER_MIN)), color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                Row {
                    for (i in 0 until (WINDOW_MIN / SLOT_MIN)) {
                        Box(Modifier.width(minutesToDp(SLOT_MIN)).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            Text(fmt(windowStart + i * SLOT_MIN * MS_PER_MIN), color = OnyxMuted, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(OnyxLive))
            }
        }

        if (shown.isEmpty()) {
            EmptyState("Aucune chaîne", if (group == GROUP_FAV) "Ajoutez des favoris depuis TV en direct." else "Aucune chaîne dans cette catégorie.")
            return
        }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.id }) { ch ->
                GuideRow(
                    vm = vm,
                    channel = ch,
                    epgVersion = state.epgVersion,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    nowX = nowX,
                    hScroll = hScroll,
                    isFavorite = ch.id in favorites,
                    onFocus = { p -> focus = GuideFocus(ch, p) },
                    onOpen = { p ->
                        when {
                            p == null || p.isLiveAt(now) || p.start > now -> onPlay(ch.toPlayTarget())
                            ch.archiveDays > 0 -> scope.launch { (vm.catchupTarget(ch, p) ?: ch.toPlayTarget()).let(onPlay) }
                            else -> onPlay(ch.toPlayTarget())
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(if (selected) "● $label" else label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailsPanel(
    focus: GuideFocus?,
    now: Long,
    onWatch: (Channel) -> Unit,
    onCatchup: (Channel, EpgProgram) -> Unit,
    onRecord: (Channel, EpgProgram) -> Unit,
    reminderIds: Set<String> = emptySet(),
    onRemind: (Channel, EpgProgram) -> Unit = { _, _ -> },
    onSchedule: (Channel, EpgProgram) -> Unit = { _, _ -> },
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OnyxSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (focus == null) {
            Text("Déplacez-vous dans la grille : OK pour regarder, revoir ou enregistrer.", color = OnyxMuted)
            return
        }
        val ch = focus.channel
        val p = focus.program
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp))) {
            Thumbnail(ch.logoUrl, ch.name.take(2).uppercase(), ch.id, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (ch.number?.let { "$it · " } ?: "") + ch.name + (ch.groupTitle?.let { "  ·  $it" } ?: ""),
                color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (p == null) {
                Text("Aucune information de programme", style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            } else {
                val live = p.isLiveAt(now)
                Text(p.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${fmt(p.start)} – ${fmt(p.stop)}  ·  ${p.durationMs / MS_PER_MIN} min" +
                        (if (live) "  ·  ● EN DIRECT" else if (p.stop <= now) "  ·  Terminé" else "  ·  À venir"),
                    color = if (live) OnyxLive else OnyxMuted, style = MaterialTheme.typography.bodyMedium,
                )
                p.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (live) {
                    Box(Modifier.fillMaxWidth(0.6f).padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(OnyxSurfaceHi)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.progressAt(now)).background(OnyxCyan))
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onWatch(ch) }) { Text("▶ Regarder") }
            if (p != null && p.stop <= now && ch.archiveDays > 0) Button(onClick = { onCatchup(ch, p) }) { Text("↺ Revoir") }
            if (p != null && p.isLiveAt(now)) Button(onClick = { onRecord(ch, p) }) { Text("● Enregistrer") }
            if (p != null && p.start > now) {
                val rem = ca.onyxtv.player.core.data.reminderId(ch.id, p.start, false) in reminderIds
                val rec = ca.onyxtv.player.core.data.reminderId(ch.id, p.start, true) in reminderIds
                Button(onClick = { onRemind(ch, p) }) { Text(if (rem) "✓ Rappel" else "⏰ Rappel") }
                Button(onClick = { onSchedule(ch, p) }) { Text(if (rec) "✓ Programmé" else "● Programmer") }
            }
        }
    }
}

@Composable
private fun GuideRow(
    vm: OnyxViewModel,
    channel: Channel,
    epgVersion: Int,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    nowX: androidx.compose.ui.unit.Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    isFavorite: Boolean,
    onFocus: (EpgProgram?) -> Unit,
    onOpen: (EpgProgram?) -> Unit,
) {
    val allPrograms by produceState(initialValue = emptyList<EpgProgram>(), channel.id, epgVersion) {
        value = runCatching { vm.epgFor(channel) }.getOrDefault(emptyList()).sortedBy { it.start }
    }
    // Filtrage sur la fenêtre COURANTE (recalculée chaque minute) : la grille ne se fige pas.
    val programs = remember(allPrograms, windowStart, windowEnd) {
        allPrograms.filter { it.stop > windowStart && it.start < windowEnd }
    }

    Row(Modifier.fillMaxWidth().height(ROW_H)) {
        // Cellule chaîne
        Card(
            onClick = { onOpen(null) },
            modifier = Modifier
                .width(CHANNEL_COL)
                .fillMaxHeight()
                .padding(end = 6.dp)
                .onFocusChanged { if (it.isFocused) onFocus(programs.firstOrNull { p -> p.isLiveAt(now) }) },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))) {
                    Thumbnail(channel.logoUrl, channel.name.take(2).uppercase(), channel.id, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (channel.number?.let { "$it  " } ?: "") + channel.name,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isFavorite) Text("★", color = OnyxCyan)
            }
        }

        // Ligne de programmes (défilement horizontal partagé) + ligne « maintenant »
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Row(Modifier.fillMaxHeight()) {
                if (programs.isEmpty()) {
                    ProgramBlock(
                        title = "Aucun programme",
                        time = null,
                        widthDp = minutesToDp(windowEnd.minus(windowStart) / MS_PER_MIN),
                        live = false,
                        past = false,
                        onFocus = { onFocus(null) },
                        onClick = { onOpen(null) },
                    )
                } else {
                    var cursor = windowStart
                    programs.forEach { p ->
                        val start = maxOf(p.start, windowStart)
                        val end = minOf(p.stop, windowEnd)
                        if (start > cursor) Filler(minutesToDp((start - cursor) / MS_PER_MIN))
                        if (end > start) {
                            ProgramBlock(
                                title = p.title,
                                time = "${fmt(p.start)} – ${fmt(p.stop)}",
                                widthDp = minutesToDp((end - start) / MS_PER_MIN),
                                live = p.isLiveAt(now),
                                past = p.stop <= now,
                                onFocus = { onFocus(p) },
                                onClick = { onOpen(p) },
                            )
                        }
                        cursor = maxOf(cursor, end)
                    }
                    if (cursor < windowEnd) Filler(minutesToDp((windowEnd - cursor) / MS_PER_MIN))
                }
            }
            Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(OnyxLive.copy(alpha = 0.85f)))
        }
    }
}

@Composable
private fun Filler(widthDp: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .width(widthDp)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(OnyxSurface.copy(alpha = 0.5f)),
    )
}

@Composable
private fun ProgramBlock(
    title: String,
    time: String?,
    widthDp: androidx.compose.ui.unit.Dp,
    live: Boolean,
    past: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = when {
                live -> OnyxViolet.copy(alpha = 0.35f)
                past -> OnyxSurface.copy(alpha = 0.6f)
                else -> OnyxSurfaceHi
            },
        ),
        modifier = Modifier
            .width(widthDp.coerceAtLeast(18.dp))
            .fillMaxHeight()
            .padding(end = 2.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (past) OnyxMuted else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            time?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (live) OnyxCyan else OnyxMuted, maxLines = 1)
            }
        }
    }
}
