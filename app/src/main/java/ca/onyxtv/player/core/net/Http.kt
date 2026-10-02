package ca.onyxtv.player.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Petit client HTTP partagé (OkHttp) pour M3U, Xtream et XMLTV. */
object Http {

    // Les catalogues Xtream (films/séries) pèsent souvent plusieurs Mo et les serveurs sont
    // lents : on laisse largement le temps de répondre plutôt que d'échouer en silence.
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val UA = "ONYX-TV/1.0 (Android TV)"

    /** Client pour les GROS téléchargements (guide xmltv de 100 Mo, catalogues) : pas de limite
     *  globale d'appel (callTimeout), seul le readTimeout protège contre un serveur muet. */
    val bulk: OkHttpClient by lazy { client.newBuilder().callTimeout(15, TimeUnit.MINUTES).build() }

    /** Client pour les flux continus (enregistrement DVR) : AUCUN délai global, seul le readTimeout veille. */
    val stream: OkHttpClient by lazy { client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    /** Dossier temporaire pour les grosses réponses (défini au démarrage de l'app). */
    @Volatile var tempDir: java.io.File? = null

    /** Dossier permanent de l'app (cache disque du guide), défini au démarrage. */
    @Volatile var dataDir: java.io.File? = null

    /** Télécharge en flux vers [file] (mémoire constante, quelle que soit la taille). */
    suspend fun getToFile(url: String, file: java.io.File): java.io.File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        bulk.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Réponse vide")
            body.byteStream().use { input -> file.outputStream().buffered().use { out -> input.copyTo(out, 64 * 1024) } }
        }
        file
    }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Message lisible pour l'utilisateur à partir d'une exception réseau. */
    fun describe(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "délai dépassé (serveur lent ou catalogue très volumineux)"
        is UnknownHostException -> "serveur introuvable (vérifiez l'adresse)"
        is IOException -> e.message ?: "erreur réseau"
        else -> e.message ?: e.javaClass.simpleName
    }
}
