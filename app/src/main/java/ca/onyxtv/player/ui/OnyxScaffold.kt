package ca.onyxtv.player.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import ca.onyxtv.player.ui.components.ContextMenuOverlay
import ca.onyxtv.player.ui.components.ContextMenuRequest
import ca.onyxtv.player.ui.components.LocalContextMenu
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
    // Menu contextuel (appui long sur OK) : dessiné par-dessus, piloté par les touches ci-dessous.
    var contextMenu by remember { mutableStateOf<ContextMenuRequest?>(null) }
    var menuIndex by remember { mutableIntStateOf(0) }
    var menuOkArmed by remember { mutableStateOf(false) }
    // Zap par numéro depuis Accueil / Direct / Guide (chiffres de la télécommande).
    var zapDigits by remember { mutableStateOf("") }

    // Choix du profil à l'ouverture (quand il y en a plusieurs), AVANT le PIN du profil.
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val profileChosen by vm.profileChosen.collectAsStateWithLifecycle()
    val prefsEarly by vm.prefs.collectAsStateWithLifecycle()
    if (profiles.size > 1 && prefsEarly.askProfileAtStart && !profileChosen) {
        FocusRoot {
            Column(
                Modifier.align(Alignment.Center).width(520.dp).clip(RoundedCornerShape(16.dp)).background(OnyxSurfaceHi).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Qui regarde ?", style = MaterialTheme.typography.headlineMedium, color = OnyxText)
                profiles.forEach { pr ->
                    androidx.tv.material3.Button(onClick = { vm.setActiveProfile(pr.id) }, modifier = Modifier.fillMaxWidth()) { Text("👤 " + pr.name) }
                }
            }
        }
        return
    }

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
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val pendingUpdate = update.info?.takeIf { it.commit != prefs.dismissedUpdateCommit }
    if (pendingUpdate != null && playing == null && openDetail == null) {
        FocusRoot {
            UpdateScreen(
                label = pendingUpdate.label,
                downloading = update.downloading,
                ready = update.readyFile != null,
                error = update.error,
                onInstall = { if (update.readyFile != null) vm.installUpdate() else vm.downloadAndInstallUpdate() },
                onLater = { vm.dismissUpdate(pendingUpdate.commit) },
            )
        }
        return
    }

    // Reprise automatique de la dernière lecture à l'ouverture (option Réglages → Lecture).
    val recents by vm.recents.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var autoResumed by remember { mutableStateOf(false) }
    val unlockedGroups by vm.unlockedGroups.collectAsStateWithLifecycle()

    // Numéro tapé hors lecteur : on zappe 1,5 s après le dernier chiffre.
    LaunchedEffect(zapDigits) {
        if (zapDigits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = zapDigits.toIntOrNull()
        zapDigits = ""
        if (n != null) vm.channelByNumber(n)?.let { playing = it.toPlayTarget() }
    }
    // Chaîne précédente (rappel ◀ dans le lecteur).
    LaunchedEffect(playing?.id, playing?.isLive) { playing?.takeIf { it.isLive }?.id?.let { vm.noteLive(it) } }
    // Rappel de programme arrivé à échéance : dialogue « Regarder / Ignorer ».
    val dueReminder by vm.dueReminder.collectAsStateWithLifecycle()
    LaunchedEffect(dueReminder) {
        val r = dueReminder ?: return@LaunchedEffect
        menuIndex = 0; menuOkArmed = false
        contextMenu = ContextMenuRequest(
            title = "⏰ ${r.title}", subtitle = "commence sur ${r.channelName}",
            actions = listOf(
                ca.onyxtv.player.ui.components.MenuAction("▶ Regarder") { vm.channelById(r.channelId)?.let { playing = it.toPlayTarget() } },
                ca.onyxtv.player.ui.components.MenuAction("Ignorer") {},
            ),
        )
        vm.dismissDueReminder()
    }

    LaunchedEffect(prefs.resumeOnStart, recents, state.hasContent) {
        // Attendre le catalogue (URL fraîche) et ignorer un contenu d'une catégorie verrouillée.
        if (!autoResumed && prefs.resumeOnStart && recents.isNotEmpty() && state.hasContent) {
            autoResumed = true
            val hidden = ca.onyxtv.player.viewmodel.hiddenGroups(parental, unlockedGroups)
            val blocked = ca.onyxtv.player.viewmodel.hiddenIds(state.channels, state.vod, hidden)
            recents.firstOrNull { it.id !in blocked }?.let { playing = vm.freshTarget(it) }
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
    val railItemFocus = remember { FocusRequester() }
    var focusNonce by remember { mutableStateOf(0) }
    val detailFocus = remember { FocusRequester() }
    val playerFocus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    var railHasFocus by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val overlayOpen = playing != null || openDetail != null || contextMenu != null

    // Reprise du focus dès que rien n'est sélectionné (démarrage, fin du chargement, fermeture
    // d'une fiche/du lecteur, changement de page). On vise le contenu, avec repli sur le menu.
    LaunchedEffect(dest, overlayOpen, state.loading, state.hasContent) {
        if (overlayOpen) {
            // Le lecteur et les fiches placent EUX-MÊMES leur focus. Si le groupe parent le demande
            // aussi, les deux se disputent le focus et l'écran interne ne reçoit pas les touches
            // (lecteur « sourd » : pause/avance/panneau sans effet).
            return@LaunchedEffect
        }
        // Après une transition (Retour, fermeture de fiche/lecteur, changement de page), laisser
        // l'état de focus se stabiliser : juste après, le focus de l'écran qui disparaît peut
        // rester marqué actif un court instant. Sans cette pause on lit un focus « périmé » et on
        // abandonne la reprise trop tôt, laissant le focus perdu (il fallait réappuyer sur OK).
        delay(100)
        repeat(30) {
            if (railHasFocus || contentHasFocus) return@LaunchedEffect
            runCatching { railItemFocus.requestFocus() }
            delay(110)
        }
    }

    // Retour : on FORCE le focus du menu (sans s'arrêter si le contenu semble encore focalisé,
    // ce qui est souvent un état périmé juste après l'appui sur Retour).
    LaunchedEffect(focusNonce) {
        if (focusNonce == 0 || overlayOpen) return@LaunchedEffect
        // Laisser le changement de page / déplacement du requester s'appliquer, puis UNE demande.
        androidx.compose.runtime.withFrameNanos { }
        runCatching { railItemFocus.requestFocus() }
    }

    // Retour : depuis le contenu -> revenir au menu (évite de perdre le focus) ; depuis le menu
    // d'une autre page -> Accueil ; depuis l'Accueil -> laisser le système quitter l'app.
    // Filet de sécurité : si l'élément d'origine du menu a disparu (liste re-triée), aucune
    // touche n'atteint plus l'interception ci-dessus ; Retour doit quand même fermer le menu.
    BackHandler(enabled = contextMenu != null) { contextMenu = null }

    BackHandler(enabled = !overlayOpen && (contentHasFocus || dest != Dest.HOME)) {
        when {
            contentHasFocus -> { runCatching { railItemFocus.requestFocus() }; focusNonce++ }
            dest != Dest.HOME -> { dest = Dest.HOME; focusNonce++ }
        }
    }

    val railWidth by animateDpAsState(if (railHasFocus) 232.dp else 84.dp, label = "rail")

    CompositionLocalProvider(LocalContextMenu provides { req -> menuIndex = 0; menuOkArmed = false; contextMenu = req }) {
    Box(
        Modifier
            .fillMaxSize()
            .background(OnyxBg)
            // Menu contextuel : on intercepte les touches AVANT l'élément focalisé (qui garde son
            // focus : à la fermeture, rien n'est perdu). ▲ ▼ choisir, OK valider, Retour annuler.
            .onPreviewKeyEvent { ev ->
                if (contextMenu == null) {
                    if (!overlayOpen && ev.type == KeyEventType.KeyDown && (dest == Dest.HOME || dest == Dest.LIVE || dest == Dest.GUIDE || dest == Dest.MOSAIC)) {
                        val d = ca.onyxtv.player.player.DIGIT_KEYS[ev.key]
                        if (d != null) { if (zapDigits.length < 4) zapDigits += d; return@onPreviewKeyEvent true }
                    }
                    return@onPreviewKeyEvent false
                }
                val menu = contextMenu ?: return@onPreviewKeyEvent false
                if (menu.actions.isEmpty()) { contextMenu = null; return@onPreviewKeyEvent true }
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                val mine = isOk || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown || ev.key == Key.DirectionLeft ||
                    ev.key == Key.DirectionRight || ev.key == Key.Back || ev.key == Key.Escape
                if (!mine) return@onPreviewKeyEvent false   // volume, etc. : pas à nous
                when {
                    isOk && ev.type == KeyEventType.KeyDown -> {
                        if (ev.nativeKeyEvent.repeatCount == 0) menuOkArmed = true
                        true
                    }
                    isOk && ev.type == KeyEventType.KeyUp -> {
                        // Relâchement de l'appui long qui a OUVERT le menu : il doit atteindre la
                        // carte (elle remet son état « appui long » à zéro), on ne l'exploite pas.
                        if (!menuOkArmed) return@onPreviewKeyEvent false
                        menuOkArmed = false
                        contextMenu = null
                        menu.actions.getOrNull(menuIndex)?.run?.invoke()
                        true
                    }
                    ev.type != KeyEventType.KeyDown -> true
                    ev.key == Key.DirectionDown -> { menuIndex = (menuIndex + 1) % menu.actions.size; true }
                    ev.key == Key.DirectionUp -> { menuIndex = (menuIndex - 1 + menu.actions.size) % menu.actions.size; true }
                    ev.key == Key.Back || ev.key == Key.Escape -> { contextMenu = null; true }
                    else -> true
                }
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
                    // IMPORTANT : pas de focusProperties { canFocus } à l'extérieur du groupe. Une
                    // propriété externe écrase le canFocus=false du groupe et rend la Column elle-même
                    // focalisable (invisible) : après Retour, le focus s'y garait, les flèches ne
                    // trouvaient rien et seul OK (= entrer dans les enfants) réveillait un élément.
                    // Pendant une superposition on bloque l'ENTRÉE dans le menu, sans toucher à canFocus.
                    .focusProperties { enter = { if (overlayOpen) FocusRequester.Cancel else FocusRequester.Default } }
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
                if (prefs.diagnostics) {
                    Text(
                        "DIAG mode=${inputModeManager.inputMode} menu=${if (railHasFocus) "OUI" else "NON"} " +
                            "contenu=${if (contentHasFocus) "OUI" else "NON"} touche=${FocusBridge.lastKey.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnyxCyan,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Dest.entries.forEach { d ->
                    RailItem(
                        dest = d,
                        selected = d == dest,
                        expanded = railHasFocus,
                        focusRequester = if (d == dest) railItemFocus else null,
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
                    .focusGroup()
            ) {
                when (dest) {
                    Dest.HOME -> HomeScreen(vm, onPlay = { playing = it }, onGoLive = { dest = Dest.LIVE }, onGoSettings = { dest = Dest.SETTINGS }, onOpenDetail = { openDetail = it })
                    Dest.LIVE -> LiveTvScreen(vm, onPlay = { playing = it })
                    Dest.GUIDE -> GuideScreen(vm, onPlay = { playing = it })
                    Dest.VOD -> VodScreen(vm, onPlay = { playing = it }, onOpenDetail = { openDetail = it })
                    // Pas de 4 lecteurs qui tournent sous le lecteur plein écran (décodeurs/connexions).
                    Dest.MOSAIC -> if (playing == null) MosaicScreen(vm, onPlay = { playing = it }) else Box(Modifier.fillMaxSize())
                    Dest.DVR -> DvrScreen(vm, onPlay = { playing = it })
                    Dest.SETTINGS -> SettingsScreen(vm)
                    Dest.SEARCH -> SearchScreen(vm, onPlay = { playing = it }, onOpenDetail = { openDetail = it })
                }
            }
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
                    // Piège le focus dans la fiche SEULEMENT quand le lecteur n'est pas ouvert ;
                    // sinon le piège annulait la demande de focus du lecteur (lecteur « sourd »).
                    .focusProperties { exit = { if (playing == null) FocusRequester.Cancel else FocusRequester.Default } }
                    .focusGroup()
            ) {
                val active = playing == null
                if (item.kind == MediaKind.SERIES)
                    SeriesScreen(vm = vm, item = item, onPlay = { playing = it }, onBack = { openDetail = null }, active = active)
                else
                    MovieScreen(vm = vm, item = item, onPlay = { playing = it }, onBack = { openDetail = null }, active = active)
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
                    subtitleScale = prefs.subtitleScale,
                    subtitleBackground = prefs.subtitleBackground,
                    subtitleYellow = prefs.subtitleYellow,
                    channelList = { vm.zapList() },
                    previous = { vm.previousChannel()?.toPlayTarget() },
                    canShift = { t -> vm.canTimeshift(t) },
                    timeshift = { live, start -> vm.timeshiftTarget(live, start) },
                )
            }
        }

        contextMenu?.let { ContextMenuOverlay(it, menuIndex) }
        if (zapDigits.isNotEmpty() && playing == null) {
            Text(
                zapDigits,
                style = MaterialTheme.typography.displayLarge,
                color = OnyxText,
                modifier = Modifier.align(Alignment.TopEnd).padding(28.dp).clip(RoundedCornerShape(12.dp)).background(OnyxSurfaceHi).padding(horizontal = 22.dp, vertical = 8.dp),
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
    focusRequester: FocusRequester?,
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
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
    LaunchedEffect(hasFocus) {
        if (hasFocus) return@LaunchedEffect
        repeat(20) {
            delay(120)
            if (hasFocus || FocusBridge.nativeViewHasFocus()) return@LaunchedEffect
            runCatching { root.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(OnyxBg)
            .onFocusChanged { hasFocus = it.hasFocus }
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
