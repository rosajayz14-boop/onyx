package ca.onyxtv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme as Md3
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.darkColorScheme as md3DarkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface
import ca.onyxtv.player.viewmodel.OnyxUiState
import kotlinx.coroutines.delay
import ca.onyxtv.player.viewmodel.OnyxViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: OnyxViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val status by vm.sourceStatus.collectAsStateWithLifecycle()
    val parental by vm.parental.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val allGroups = remember(state.channels, state.vod) {
        (state.groups + state.vod.mapNotNull { it.category }).distinct()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Réglages", style = MaterialTheme.typography.headlineLarge) }
        status?.let { msg ->
            item {
                val ok = msg.startsWith("Connect") || msg.startsWith("Liste ajoutée")
                Text(
                    msg,
                    color = if (ok) OnyxLive else Md3.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(OnyxSurface)
                        .padding(14.dp),
                )
            }
        }
        item { UpdateCard(vm, state) }
        item { Text("Sources configurées", style = MaterialTheme.typography.titleLarge) }

        if (sources.isEmpty()) {
            item {
                Text(
                    "Aucune source. Ajoutez une liste M3U ou un compte Xtream ci-dessous.",
                    color = OnyxMuted,
                )
            }
        } else {
            items(sources, key = { it.id }) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceRow(s) { vm.removeSource(s.id) }
                    if (s is PlaylistSource.Xtream) {
                        val hls = s.liveExtension == "m3u8"
                        Button(onClick = { vm.setLiveExtension(s.id, if (hls) "ts" else "m3u8") }) {
                            Text("Flux live : ${if (hls) "HLS (m3u8)" else "MPEG-TS"} — basculer si certaines chaînes ne se lisent pas")
                        }
                    }
                }
            }
        }

        item { AddM3uCard(vm) }
        item { AddXtreamCard(vm) }

        // ---- Lecture ----
        item { PlaybackCard(vm) }

        // ---- Application (version, mises à jour) ----
        item { AppCard(vm) }

        // ---- Contrôle parental ----
        item { ParentalCard(vm, parental.enabled, parental.lockAtStart) }
        if (parental.enabled) {
            item {
                Text(
                    if (allGroups.isEmpty()) "Ajoutez une source pour choisir les catégories à verrouiller."
                    else "Catégories verrouillées (OK pour basculer) — demandées par PIN à chaque lancement :",
                    color = OnyxMuted,
                )
            }
            items(allGroups, key = { "lock:$it" }) { g ->
                val locked = g in parental.lockedGroups
                Card(onClick = { vm.toggleLockedGroup(g) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
                        Text(if (locked) "🔒 Verrouillée" else "🔓 Libre", color = if (locked) OnyxLive else OnyxCyan, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: PlaylistSource, onRemove: () -> Unit) {
    val (type, detail) = when (source) {
        is PlaylistSource.M3u -> "M3U" to source.url
        is PlaylistSource.Xtream -> "Xtream" to source.server
    }
    // Suppression en deux temps pour éviter un effacement accidentel à la télécommande.
    var confirm by remember(source.id) { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(6_000); confirm = false } }
    Card(onClick = { if (confirm) onRemove() else confirm = true }, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text(source.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$type · $detail",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnyxMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (confirm) "Confirmer la suppression ? (OK)" else "Retirer",
                color = if (confirm) OnyxLive else OnyxMuted,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun FormCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OnyxSurface)
            .padding(20.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        // Les champs de saisie utilisent Material 3 (sombre) pour un rendu correct sur TV.
        Md3(colorScheme = md3DarkColors()) {
            Column(content = content)
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
private fun AddM3uCard(vm: OnyxViewModel) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }

    FormCard("Ajouter une liste M3U") {
        Field("Nom", label) { label = it }
        Field("URL .m3u / .m3u8", url) { url = it }
        Field("URL EPG XMLTV (optionnel)", epg) { epg = it }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (url.isNotBlank()) {
                vm.addM3u(label, url, epg.ifBlank { null })
                label = ""; url = ""; epg = ""
            }
        }) { Text("Ajouter la liste") }
    }
}

@Composable
private fun AddXtreamCard(vm: OnyxViewModel) {
    var label by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    FormCard("Ajouter un compte Xtream Codes") {
        Field("Nom", label) { label = it }
        Field("Serveur (http://exemple.tv:8080)", server) { server = it }
        Field("Nom d'utilisateur", user) { user = it }
        Field("Mot de passe", pass) { pass = it }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (server.isNotBlank() && user.isNotBlank()) {
                vm.addXtream(label, server, user, pass)
                label = ""; server = ""; user = ""; pass = ""
            }
        }) { Text("Ajouter le compte") }
    }
}

@Composable
private fun ParentalCard(vm: OnyxViewModel, enabled: Boolean, lockAtStart: Boolean) {
    var pin by remember { mutableStateOf("") }

    FormCard("Contrôle parental") {
        Text(
            if (enabled) "PIN actif. Vous pouvez verrouiller des catégories ci-dessous ou l'application au démarrage."
            else "Définissez un code à 4 chiffres pour verrouiller des catégories (ex. adultes) ou l'application.",
            color = OnyxMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Field("Nouveau code PIN (4 chiffres)", pin) { v -> if (v.length <= 4 && v.all { it.isDigit() }) pin = v }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (pin.length == 4) { vm.setPin(pin); pin = "" } }) {
                Text(if (enabled) "Changer le PIN" else "Activer le PIN")
            }
            if (enabled) {
                Button(onClick = { vm.setPin(null) }) { Text("Désactiver") }
                Button(onClick = { vm.setLockAtStart(!lockAtStart) }) {
                    Text("Verrouiller au démarrage : ${if (lockAtStart) "Oui" else "Non"}")
                }
            }
        }
    }
}

private fun relative(ts: Long): String {
    if (ts <= 0) return "jamais"
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "à l'instant"
        m < 60 -> "il y a $m min"
        m < 48 * 60 -> "il y a ${m / 60} h"
        else -> SimpleDateFormat("dd/MM à HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/** Mise à jour du catalogue et du guide, avec bilan par source. */
@Composable
private fun UpdateCard(vm: OnyxViewModel, state: OnyxUiState) {
    var epgNote by remember { mutableStateOf<String?>(null) }
    FormCard("Mise à jour des données") {
        Text(
            if (state.loading) "Mise à jour en cours… ${state.progress ?: ""}"
            else "Dernière mise à jour : ${relative(state.updatedAt)} · " +
                "${state.channels.size} chaînes · ${state.vod.count { it.kind == ca.onyxtv.player.core.model.MediaKind.MOVIE }} films · " +
                "${state.vod.count { it.kind == ca.onyxtv.player.core.model.MediaKind.SERIES }} séries",
            color = if (state.loading) OnyxCyan else OnyxMuted,
            style = MaterialTheme.typography.bodyLarge,
        )
        state.reports.forEach { r ->
            Text(
                "${r.label} (${r.type}) — ${r.channels} chaînes · ${r.movies} films · ${r.series} séries" +
                    (r.error?.let { "\n⚠ $it" } ?: ""),
                color = if (r.error != null) OnyxLive else OnyxMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.refresh() }) { Text(if (state.loading) "Mise à jour…" else "🔄 Tout mettre à jour") }
            Button(onClick = { vm.refreshEpg(); epgNote = "Guide vidé : il sera retéléchargé à l'affichage des chaînes." }) {
                Text("🗓 Mettre à jour le guide (EPG)")
            }
        }
        epgNote?.let { Text(it, color = OnyxCyan, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        Text(
            "Le catalogue est rafraîchi automatiquement à l'ouverture s'il date de plus de 6 h ; le guide est mis en cache 30 min.",
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PlaybackCard(vm: OnyxViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    FormCard("Lecture") {
        Text(
            "La position de lecture est mémorisée quand vous quittez l'app (Accueil, veille) et chaque 5 s.",
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setResumeOnStart(!prefs.resumeOnStart) }) {
            Text("Reprendre la dernière lecture à l'ouverture : ${if (prefs.resumeOnStart) "Oui" else "Non"}")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Dans le lecteur : ▲ passe l'intro (début) ou lance l'épisode suivant (fin) ; ◀ / ▶ reculent / avancent.",
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val intros = listOf(60, 85, 90, 120)
            Button(onClick = { vm.setIntroSkip(intros[(intros.indexOf(prefs.introSkipSeconds).coerceAtLeast(0) + 1) % intros.size]) }) {
                Text("Passer l'intro : ${prefs.introSkipSeconds} s")
            }
            val steps = listOf(10 to 30, 15 to 30, 30 to 60, 10 to 10)
            Button(onClick = {
                val i = steps.indexOfFirst { it.first == prefs.seekBackSeconds && it.second == prefs.seekForwardSeconds }
                val n = steps[(i.coerceAtLeast(0) + 1) % steps.size]
                vm.setSeekSteps(n.first, n.second)
            }) { Text("◀ ${prefs.seekBackSeconds} s / ▶ ${prefs.seekForwardSeconds} s") }
        }
    }
}

@Composable
private fun AppCard(vm: OnyxViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    val shortSha = ca.onyxtv.player.core.update.UpdateChecker.currentCommit.take(7)
    FormCard("Application") {
        Text(
            "ONYX TV ${ca.onyxtv.player.core.update.UpdateChecker.currentVersion} · build $shortSha" +
                (update.checkedAt.takeIf { it > 0 }?.let { " · vérifié ${relative(it)}" } ?: ""),
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Les mises à jour sont vérifiées automatiquement une fois par jour ; le catalogue est actualisé chaque jour en arrière-plan.",
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
        )
        update.info?.let { info ->
            Text(
                "✨ Nouvelle version disponible : ${info.label}" + (info.sizeBytes.takeIf { it > 0 }?.let { " (${it / (1024 * 1024)} Mo)" } ?: ""),
                color = OnyxCyan, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp),
            )
        }
        update.downloading?.let { p ->
            Text("Téléchargement… ${(p * 100).toInt()} %", color = OnyxCyan, modifier = Modifier.padding(top = 6.dp))
        }
        update.error?.let { Text(it, color = OnyxLive, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                update.readyFile != null -> Button(onClick = { vm.installUpdate() }) { Text("📦 Installer la mise à jour") }
                update.info != null -> Button(onClick = { vm.downloadAndInstallUpdate() }) {
                    Text(if (update.downloading != null) "Téléchargement…" else "⬇ Télécharger et installer")
                }
            }
            Button(onClick = { vm.checkForUpdate(force = true) }) {
                Text(if (update.checking) "Vérification…" else "Vérifier les mises à jour")
            }
        }
        if (update.info != null) {
            Text(
                "Si l'installation est refusée (« Application non installée »), désinstallez l'ancienne version puis réinstallez : " +
                    "cela arrive quand la clé de signature a changé.",
                color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
