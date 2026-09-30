package ca.onyxtv.player

import android.app.Application
import android.util.Log
import ca.onyxtv.player.core.work.CatalogRefreshWorker
import ca.onyxtv.player.dvr.RecordingStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

/** Point d'entrée de l'application ONYX TV. */
class OnyxApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        ca.onyxtv.player.core.net.Http.tempDir = cacheDir.resolve("net")
        // Au démarrage du processus, aucun enregistrement ne peut être en cours :
        // les entrées restées « RECORDING » (coupure, crash) sont clôturées proprement.
        appScope.launch { runCatching { RecordingStore(this@OnyxApp).markInterrupted() } }
        // Mise à jour quotidienne du catalogue en arrière-plan.
        runCatching { CatalogRefreshWorker.schedule(this) }
    }

    /**
     * Chargeur d'images économe : les boîtiers TV ont peu de mémoire et une liste de milliers
     * de logos peut saturer le tas. Cache mémoire limité, cache disque modéré, pas de fondu.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(120L * 1024 * 1024).build() }
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()

    /** Journal du dernier plantage (filesDir/crash.log), consultable dans Réglages → Application. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText("${Date()}\nThread: ${thread.name}\n${Log.getStackTraceString(e)}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        const val CRASH_FILE = "crash.log"
    }
}
