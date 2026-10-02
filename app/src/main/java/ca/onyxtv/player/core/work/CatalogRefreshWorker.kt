package ca.onyxtv.player.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.onyxtv.player.core.data.CatalogCache
import ca.onyxtv.player.core.data.OnyxRepository
import ca.onyxtv.player.core.data.PlaylistStore
import java.util.concurrent.TimeUnit

/**
 * Mise à jour quotidienne du catalogue en arrière-plan (chaînes, films, séries), même si
 * l'application n'est pas ouverte. Le cache n'est remplacé que si TOUTES les sources ont
 * répondu, pour ne jamais perdre de contenus à cause d'un serveur momentanément en panne.
 */
class CatalogRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = OnyxRepository(PlaylistStore(ctx))
        val cache = CatalogCache(ctx)
        return runCatching {
            val snapshot = repo.loadCatalog()
            if (snapshot.isEmpty && snapshot.reports.isEmpty()) return Result.success() // aucune source
            if (snapshot.reports.all { it.error == null } && !snapshot.isEmpty) cache.save(snapshot)
            // Guide TV : retéléchargé chaque jour, stocké sur disque pour l'ouverture suivante.
            runCatching { repo.refreshEpgFromNetwork() }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "onyx_catalog_refresh_daily"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
