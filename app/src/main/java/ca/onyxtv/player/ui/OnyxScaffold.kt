package ca.onyxtv.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dvr
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.R
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.player.PlayerScreen
import ca.onyxtv.player.ui.components.PinDialog
import ca.onyxtv.player.ui.components.toPlayTarget
import ca.onyxtv.player.ui.dvr.DvrScreen
import ca.onyxtv.player.ui.guide.GuideScreen
import ca.onyxtv.player.ui.home.HomeScreen
import ca.onyxtv.player.ui.live.LiveTvScreen
import ca.onyxtv.player.ui.mosaic.MosaicScreen
import ca.onyxtv.player.ui.movie.MovieScreen
import ca.onyxtv.player.ui.search.SearchScreen
import ca.onyxtv.player.ui.series.SeriesScreen
import ca.onyxtv.player.ui.settings.SettingsScreen
import ca.onyxtv.player.ui.vod.VodScreen
import ca.onyxtv.player.ui.theme.OnyxBg
import ca.onyxtv.player.ui.theme.OnyxBg2
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurfaceHi
import ca.onyxtv.player.ui.theme.OnyxText
import ca.onyxtv.player.viewmodel.OnyxViewModel
import kotlinx.coroutines.delay

enum class Dest(val label: String, val icon: ImageVector) {
    SEARCH("Recherche", Icons.Rounded.Search),
    HOME("Accueil", Icons.Rounded.Home),
    LIVE("TV en direct", Icons.Rounded.LiveTv),
    GUIDE("Guide TV", Icons.Rounded.Today),
    VOD("Films & Séries", Icons.Rounded.Movie),
    MOSAIC("Mosaïque", Icons.Rounded.GridView),
    DVR("Enregistrements", Icons.Rounded.Dvr),
    SETTINGS("Réglages", Icons.Rounded.Settings),
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun OnyxRoot(vm: OnyxViewModel = viewModel()) {
    var dest by remember { mutableStateOf(Dest.HOME) }
    var playing by remember { mutableStateOf<PlayTarget?>(null) }
    var openDetail by remember { mutableStateOf<VodItem?>(null) }

    // Verrouillage de l'application au démarrage (contrôle parental).
    val parental by vm.parental.collectAsStateWithLifecycle()
    val appUnlocked by vm.appUnlocked.collectAsStateWithLifecycle()
    if (parental.enabled && parental.lockAtStart && !appUnlocked) {
        FocusRoot {
            PinDialog(
                title = "ONYX TV est verrouillé",
                subtitle = "Entrez votre code PIN pour continuer.",
                onSubmit = { vm.unlockApp(it) },
            )
        }
        return
    }

    // Mise à jour disponible : écran dédié (jamais par-dessus le contenu → le focus reste maîtrisé).
    val update by vm.update.collectAsStateWithLifecycle()
    var dismissedUpdate by remember { mutableStateOf<String?>(null) }
    val pendingUpdate = update.info?.takeIf { it.commit != dismissedUpdate }
    if (pendingUpdate != null && playing == null && openDetail == null) {
        FocusRoot {
            UpdateScreen(
                label = pendingUpdate.label,
                downloading = update.downloading,
                ready = update.readyFile != null,
                error = update.error,
                onInstall = { if (update.readyFile != null) vm.installUpdate() else vm.downloadAndInstallUpdate() },
                onLater = { dismissedUpdate = pendingUpdate.commit },
            )
        }
        return
    }

    // Reprise automatique de la dernière lecture à l'ouverture (option Réglages → Lecture).
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var autoResumed by remember { mutableStateOf(false) }
    LaunchedEffect(prefs.resumeOnStart, recents) {
        if (!autoResumed && prefs.resumeOnStart && recents.isNotEmpty()) {
            autoResumed = true
            playing = recents.first().toPlayTarget()
        }
    }

    // Vérification de mise à jour à chaque retour au premier plan (limitée à 1×/jour dans le VM).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START) vm.checkForUpdate() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ---- Focus ----
    // Le menu latéral (barre) et le contenu sont deux groupes de focus voisins. La navigation
    // gauche/droite entre eux est gérée par la recherche de focus native de Compose (fiable).
    // Le tv-material NavigationDrawer, qui piégeait le focus (flèches sans effet, menu bloqué),
    // n'est plus utilisé.
    val railFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    val playerFocus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    var railHasFocus by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var rootHasFocus by remember { mutableStateOf(false) }
    val overlayOpen = playing != null || openDetail != null

    // Cible de focus courante, pour le pont Activity (FocusBridge) et la reprise automatique.
    val requestCurrentFocus: () -> Unit = {
        when {
            playing != null -> runCatching { playerFocus.requestFocus() }
            openDetail != null -> runCatching { detailFocus.requestFocus() }
            else -> runCatching { railFocus.requestFocus() }
        }
    }
    SideEffect { FocusBridge.requestFocus = requestCurrentFocus }
    DisposableEffect(Unit) {
        FocusBridge.hasFocus = false
        onDispose { FocusBridge.hasFocus = false; FocusBridge.requestFocus = null }
    }

    // Mode télécommande (surbrillance visible) demandé une fois au démarrage.
    LaunchedEffect(Unit) { runCatching { inputModeManager.requestInputMode(InputMode.Keyboard) } }

    // Reprise du focus dès que rien n'est sélectionné (démarrage, fin du chargement, fermeture
    // d'une fiche/du lecteur, changement de page). On vise le contenu, avec repli sur le menu.
    LaunchedEffect(dest, overlayOpen, state.loading, state.hasContent) {
        if (overlayOpen) {
            requestCurrentFocus()
            return@LaunchedEffect
        }
        // Au démarrage / après fermeture d'une superposition : placer le focus sur le menu
        // (toujours focalisable). L'utilisateur entre dans le contenu avec la flèche droite.
        repeat(15) { i ->
            if (rootHasFocus || FocusBridge.nativeViewHasFocus()) return@LaunchedEffect
            delay(if (i == 0) 150 else 120)
            runCatching { railFocus.requestFocus() }
            if (FocusBridge.hasFocus) return@LaunchedEffect
        }
    }

    val railWidth by animateDpAsState(if (railHasFocus) 232.dp else 84.dp, label = "rail")

    Box(
        Modifier
            .fillMaxSize()
            .background(OnyxBg)
            .onFocusChanged {
                rootHasFocus = it.hasFocus
                FocusBridge.hasFocus = it.hasFocus
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            // ---- Barre latérale (menu) ----
            Column(
                Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .background(OnyxBg2)
                    .onFocusChanged { railHasFocus = it.hasFocus }
                    .focusRequester(railFocus)
                    .focusProperties { canFocus = !overlayOpen }
                    .focusGroup()
                    .onPreviewKeyEvent { ev ->
                        // Flèche droite depuis le menu : entrer dans le contenu.
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight)
                            focusManager.moveFocus(FocusDirection.Right)
                        else false
                    }
                    .padding(vertical = 16.dp, horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 18.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = "ONYX",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(34.dp),
                    )
                    if (railHasFocus) {
                        Spacer(Modifier.width(12.dp))
                        Text("ONYX", style = MaterialTheme.typography.titleLarge, color = OnyxText)
                    }
                }
                Dest.entries.forEach { d ->
                    RailItem(
                        dest = d,
                        selected = d == dest,
                        expanded = railHasFocus,
                        onClick = { dest = d },
                    )
                }
            }

            // ---- Contenu ----
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { contentHasFocus = it.hasFocus }
            ) {
                when (dest) {
                    Dest.HOME -> HomeScreen(vm, onPlay = { playing = it }, onGoLive = { dest = Dest.LIVE }, onGoSettings = { dest = Dest.SETTINGS }, onOpenDetail = { openDetail = it })
                    Dest.LIVE -> LiveTvScreen(vm, onPlay = { playing = it })
                    Dest.GUIDE -> GuideScreen(vm, onPlay = { playing = it })
                    Dest.VOD -> VodScreen(vm, onPlay = { playing = it }, onOpenDetail = { openDetail = it })
                    Dest.MOSAIC -> MosaicScreen(vm, onPlay = { playing = it })
                    Dest.DVR -> DvrScreen(vm, onPlay = { playing = it })
                    Dest.SETTINGS -> SettingsScreen(vm)
                    Dest.SEARCH -> SearchScreen(vm, onPlay = { playing = it }, onOpenDetail = { openDetail = it })
                }
            }
        }

        // Cadre de diagnostic (Réglages → Application → Mode diagnostic).
        if (prefs.diagnostics) {
            Text(
                "DIAG · page ${dest.name} · touche ${FocusBridge.lastKey.value}" +
                    " · menu ${if (railHasFocus) "OUI" else "NON"} · contenu ${if (contentHasFocus) "OUI" else "NON"}" +
                    " · récup ${FocusBridge.rescued.intValue} · mode ${inputModeManager.inputMode}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color(0xCC000000)).padding(8.dp),
            )
        }

        // Horloge discrète (mise à jour chaque 30 s), masquée pendant la lecture.
        if (playing == null) {
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
            Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
                style = MaterialTheme.typography.titleMedium,
                color = OnyxMuted,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 28.dp),
            )
        }

        // Chaque superposition est un groupe de focus « piégé » : les flèches ne peuvent pas en
        // sortir vers l'écran caché en dessous (exit = Cancel).
        openDetail?.let { item ->
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(detailFocus)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
            ) {
                Box(Modifier.fillMaxSize().focusProperties { canFocus = playing == null }) {
                    if (item.kind == MediaKind.SERIES)
                        SeriesScreen(vm = vm, item = item, onPlay = { playing = it }, onBack = { openDetail = null })
                    else
                        MovieScreen(vm = vm, item = item, onPlay = { playing = it }, onBack = { openDetail = null })
                }
            }
        }

        playing?.let { target ->
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(playerFocus)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
            ) {
                PlayerScreen(
                    target = target,
                    onExit = { playing = null },
                    onProgress = vm::onPlaybackProgress,
                    zap = { delta -> vm.neighborChannel(target.id, delta)?.toPlayTarget() },
                    onSwitch = { playing = it },
                    zapToNumber = { n -> vm.channelByNumber(n)?.toPlayTarget() },
                    nowPlaying = { t -> vm.nowPlaying(t) },
                    seekBackSeconds = prefs.seekBackSeconds,
                    seekForwardSeconds = prefs.seekForwardSeconds,
                )
            }
        }
    }
}

/** Élément du menu latéral : focalisable (recherche de focus native), surbrillance nette au focus. */
@Composable
private fun RailItem(
    dest: Dest,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> OnyxCyan
        selected -> OnyxSurfaceHi
        else -> Color.Transparent
    }
    val fg = if (focused) OnyxBg else OnyxText
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = dest.icon, contentDescription = dest.label, tint = fg, modifier = Modifier.size(26.dp))
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(dest.label, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

/**
 * Écran plein autonome (PIN, mise à jour) : suit le focus pour le pont Activity et sait le
 * replacer dans son premier élément si une touche arrive alors que rien n'est sélectionné.
 */
@Composable
private fun FocusRoot(content: @Composable BoxScope.() -> Unit) {
    val root = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    SideEffect { FocusBridge.requestFocus = { runCatching { root.requestFocus() } } }
    DisposableEffect(Unit) {
        FocusBridge.hasFocus = false
        onDispose { FocusBridge.hasFocus = false }
    }
    LaunchedEffect(hasFocus) {
        if (hasFocus) return@LaunchedEffect
        repeat(20) { i ->
            delay(if (i == 0) 250 else 120)
            if (FocusBridge.hasFocus || FocusBridge.nativeViewHasFocus()) return@LaunchedEffect
            runCatching { root.requestFocus() }
            if (FocusBridge.hasFocus) return@LaunchedEffect
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(OnyxBg)
            .onFocusChanged { hasFocus = it.hasFocus; FocusBridge.hasFocus = it.hasFocus }
            .focusRequester(root)
            .focusGroup(),
        content = content,
    )
}

/** Écran plein « Nouvelle version » : le focus est placé sur « Plus tard » pour ne jamais bloquer l'utilisateur. */
@Composable
private fun UpdateScreen(
    label: String,
    downloading: Float?,
    ready: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    val laterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(3) { delay(120); if (runCatching { laterFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    androidx.activity.compose.BackHandler(enabled = true) { onLater() }
    Box(Modifier.fillMaxSize().background(OnyxBg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(600.dp).background(OnyxBg2).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("✨ Nouvelle version d'ONYX TV", style = MaterialTheme.typography.headlineMedium)
            Text(label, color = OnyxMuted)
            downloading?.let { p -> Text("Téléchargement… ${(p * 100).toInt()} %", color = MaterialTheme.colorScheme.secondary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.tv.material3.Button(
                    onClick = onLater,
                    modifier = Modifier.focusRequester(laterFocus),
                ) { Text("Plus tard") }
                if (downloading == null) {
                    androidx.tv.material3.Button(onClick = onInstall) { Text(if (ready) "📦 Installer" else "⬇ Installer maintenant") }
                }
            }
            Text(
                "L'installation se fait sans quitter l'app ; comptes, favoris et réglages sont conservés. " +
                    "Retour = plus tard.",
                color = OnyxMuted, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
