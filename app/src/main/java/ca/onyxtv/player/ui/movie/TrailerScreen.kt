package ca.onyxtv.player.ui.movie

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxMuted
import kotlinx.coroutines.delay

/** Identifiant vidéo YouTube extrait d'une URL (watch, youtu.be, embed) ou d'un id nu. */
fun youtubeId(raw: String): String? {
    val v = raw.trim()
    if (Regex("^[A-Za-z0-9_-]{11}$").matches(v)) return v
    return Regex("(?:v=|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})").find(v)?.groupValues?.get(1)
}

/**
 * Bande-annonce YouTube lue DANS l'application (lecteur YouTube embarqué plein écran).
 * Lecture automatique ; Retour ferme. Le chargement se fait avec l'origine youtube.com
 * pour que l'intégration soit acceptée comme sur un site web.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerScreen(videoId: String, title: String, onBack: () -> Unit) {
    var loaded by remember { mutableStateOf(false) }
    var showHeader by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = true) { onBack() }
    LaunchedEffect(Unit) { delay(4_000); showHeader = false }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                runCatching { wv.loadUrl("about:blank"); wv.stopLoading(); wv.destroy() }
            }
        }
    }
    // Accueil / veille : sans cela, le son YouTube continue en arrière-plan.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { webView?.onPause(); webView?.pauseTimers() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { webView?.onResume(); webView?.resumeTimers() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // API officielle « IFrame Player » de YouTube, servie avec l'origine https://www.youtube.com.
    // C'est la méthode fiable : charger directement .../embed/ID donne l'erreur 153 (référent
    // manquant), et imbriquer une simple iframe donne souvent un code d'erreur. L'API IFrame,
    // elle, est acceptée comme sur un vrai site.
    val html = remember(videoId) {
        """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
        #player{position:absolute;top:0;left:0;width:100%;height:100%}</style></head>
        <body><div id="player"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var player;
        function onYouTubeIframeAPIReady(){
          player=new YT.Player('player',{
            width:'100%',height:'100%',videoId:'$videoId',
            playerVars:{autoplay:1,controls:1,rel:0,modestbranding:1,playsinline:1,fs:1,iv_load_policy:3},
            events:{onReady:function(e){e.target.playVideo();}}
          });
        }
        </script></body></html>
        """.trimIndent()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(AColor.BLACK)
                    keepScreenOn = true   // empêche la veille pendant la bande-annonce
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    // YouTube sert un lecteur intégrable fiable aux navigateurs de bureau ;
                    // avec l'UA WebView par défaut, l'intégration est souvent refusée (écran noir).
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loaded = true }
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
                    requestFocus()
                    webView = this
                }
            },
        )

        if (!loaded) {
            CircularProgressIndicator(color = OnyxCyan, modifier = Modifier.align(Alignment.Center))
        }

        if (showHeader) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 28.dp, vertical = 20.dp)
            ) {
                Text("🎬 Bande-annonce", style = MaterialTheme.typography.labelLarge, color = OnyxCyan)
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("Retour pour fermer", style = MaterialTheme.typography.bodyMedium, color = OnyxMuted)
            }
        }
    }
}
