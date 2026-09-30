package ca.onyxtv.player

import android.app.Application
import ca.onyxtv.player.dvr.RecordingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Point d'entrée de l'application ONYX TV. */
class OnyxApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Au démarrage du processus, aucun enregistrement ne peut être en cours :
        // les entrées restées « RECORDING » (coupure, crash) sont clôturées proprement.
        appScope.launch { runCatching { RecordingStore(this@OnyxApp).markInterrupted() } }
    }
}
