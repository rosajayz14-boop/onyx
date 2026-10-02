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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.MaterialTheme as Md3
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.darkColorScheme as md3DarkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
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
    // Les réglages du contrôle parental sont eux-mêmes protégés par le PIN (sinon n'importe qui
    // avec la télécommande pouvait le désactiver).
    var parentalUnlocked by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    val state by vm.state.collectAsStateWithLifecycle()
    val allGroups = remember(state.channels, state.vod) {
        (state.groups + state.vod.mapNotNull { it.category }).distinct()
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
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
                    color = if (ok) OnyxCyan else OnyxLive,
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
                        val accounts by vm.accounts.collectAsStateWithLifecycle()
                        accounts[s.id]?.let { a ->
                            val days = a.daysLeft
                            val exp = a.expiresAt?.let { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it)) }
                            val warn = days != null && days <= 7
                            Text(
                                buildString {
                                    append("Compte : ").append(a.status)
                                    if (exp != null) append(" · expire le ").append(exp)
                                    if (days != null) append(if (days < 0) " (EXPIRÉ)" else " (dans $days j)")
                                    if (a.maxCons != null) append(" · connexions ").append(a.activeCons ?: 0).append('/').append(a.maxCons)
                                },
                                color = if (warn) OnyxLive else OnyxCyan,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
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
        if (parental.enabled && !parentalUnlocked) {
            item {
                Card(onClick = { askPin = true }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("🔒 Contrôle parental actif", style = MaterialTheme.typography.titleLarge)
                        Text("Appuyez sur OK et entrez le PIN pour modifier le code, les catégories verrouillées ou le verrouillage au démarrage.", color = OnyxMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else item { ParentalCard(vm, parental.enabled, parental.lockAtStart) }
        if (parental.enabled && parentalUnlocked) {
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
                        Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp))
                        Text(if (locked) "🔒 Verrouillée" else "🔓 Libre", color = if (locked) OnyxLive else OnyxCyan, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (askPin) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
        ) {
            ca.onyxtv.player.ui.components.PinDialog(
                title = "Contrôle parental",
                subtitle = "Entrez le PIN pour modifier les réglages.",
                onSubmit = { pin -> val ok = vm.unlockApp(pin); if (ok) { parentalUnlocked = true; askPin = false }; ok },
                onCancel = { askPin = false },
            )
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
            Column(Modifier.weight(1f, fill = false).padding(end = 12.dp)) {
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
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        // Sans autocorrection (clavier « brut » avec chiffres) et SANS ouverture automatique du
        // clavier au simple passage du focus : il s'ouvre quand on appuie sur OK dans le champ.
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrect = false, showKeyboardOnFocus = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun AddM3uCard(vm: OnyxViewModel) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }

    FormCard("Ajouter une liste M3U") {
        Field("Nom", label) { label = it }
        Field("URL .m3u / .m3u8", url, KeyboardType.Uri) { url = it }
        Field("URL EPG XMLTV (optionnel)", epg, KeyboardType.Uri) { epg = it }
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
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    // Serveur figé (BuildConfig.DEFAULT_SERVER) : seul le compte est demandé.
    val server = ca.onyxtv.player.BuildConfig.DEFAULT_SERVER

    FormCard("Ajouter un compte") {
        Text(
            "Serveur : ${server.removePrefix("http://").removePrefix("https://")}  ·  entrez simplement votre identifiant et votre mot de passe.",
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Field("Nom (optionnel)", label) { label = it }
        Field("Nom d'utilisateur", user, KeyboardType.Ascii) { user = it }
        Field("Mot de passe", pass, KeyboardType.Ascii) { pass = it }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (user.isNotBlank() && pass.isNotBlank()) {
                vm.addXtream(label.ifBlank { "Mon compte" }, server, user, pass)
                label = ""; user = ""; pass = ""
            }
        }) { Text("Connecter le compte") }
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
        Field("Nouveau code PIN (4 chiffres)", pin, KeyboardType.NumberPassword, password = true) { v -> if (v.length <= 4 && v.all { it.isDigit() }) pin = v }
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
            Button(onClick = { vm.refreshEpg(); epgNote = "Guide en cours de rechargement depuis le serveur…" }) {
                Text("🗓 Mettre à jour le guide (EPG)")
            }
            Button(onClick = { vm.testEpg() }) { Text("🔎 Tester le guide") }
        }
        epgNote?.let { Text(it, color = OnyxCyan, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        val epgTest by vm.epgTest.collectAsStateWithLifecycle()
        epgTest?.let {
            Text(it, color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            "Liste de lecture ET guide TV sont mis à jour automatiquement une fois par jour, en arrière-plan, et à l'ouverture si les données datent de plus de 24 h.",
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
            "Dans le lecteur : ◀ / ▶ reculent / avancent ; ▲ en fin d'épisode lance le suivant.",
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val steps = listOf(10 to 30, 15 to 30, 30 to 60, 10 to 10)
            Button(onClick = {
                val i = steps.indexOfFirst { it.first == prefs.seekBackSeconds && it.second == prefs.seekForwardSeconds }
                val n = steps[(i.coerceAtLeast(0) + 1) % steps.size]
                vm.setSeekSteps(n.first, n.second)
            }) { Text("◀ ${prefs.seekBackSeconds} s / ▶ ${prefs.seekForwardSeconds} s") }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setLivePreview(!prefs.livePreview) }) {
            Text("Aperçu vidéo de la chaîne dans TV en direct : ${if (prefs.livePreview) "Oui" else "Non"}")
        }
        Spacer(Modifier.height(8.dp))
        Text("Sous-titres", color = OnyxCyan, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val scales = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)
            Button(onClick = {
                val i = scales.indexOfFirst { kotlin.math.abs(it - prefs.subtitleScale) < 0.01f }
                vm.setSubtitleStyle(scale = scales[(i.coerceAtLeast(0) + 1) % scales.size])
            }) { Text("Taille ×${prefs.subtitleScale}") }
            Button(onClick = { vm.setSubtitleStyle(background = !prefs.subtitleBackground) }) { Text("Fond : ${if (prefs.subtitleBackground) "sombre" else "aucun"}") }
            Button(onClick = { vm.setSubtitleStyle(yellow = !prefs.subtitleYellow) }) { Text("Couleur : ${if (prefs.subtitleYellow) "jaune" else "blanc"}") }
        }
    }
}

@Composable
private fun AppCard(vm: OnyxViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    val shortSha = ca.onyxtv.player.core.update.UpdateChecker.currentCommit.take(7)
    FormCard("Application") {
        Text(
            "ONYX TV ${ca.onyxtv.player.core.update.UpdateChecker.currentVersion} · build ${ca.onyxtv.player.BuildConfig.VERSION_CODE} ($shortSha)" +
                (update.checkedAt.takeIf { it > 0 }?.let { " · vérifié ${relative(it)}" } ?: ""),
            color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
        )
        if (update.checkedAt > 0 && update.info == null && update.error == null && !update.checking) {
            Text("✓ Vous avez la dernière version.", color = OnyxCyan, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        }
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
        val prefsDiag by vm.prefs.collectAsStateWithLifecycle()
        Button(onClick = { vm.setDiagnostics(!prefsDiag.diagnostics) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Mode diagnostic (cadre touches/focus à l'écran) : ${if (prefsDiag.diagnostics) "Activé" else "Désactivé"}")
        }
        val lastCrash by vm.lastCrash.collectAsStateWithLifecycle()
        lastCrash?.let { crash ->
            Text("⚠ Dernier plantage enregistré :", color = OnyxLive, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(crash.lines().take(8).joinToString("\n"), color = OnyxMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Button(onClick = { vm.clearCrash() }, modifier = Modifier.padding(top = 6.dp)) { Text("Effacer le journal") }
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
