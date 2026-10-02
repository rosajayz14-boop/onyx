package ca.onyxtv.player.dvr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ca.onyxtv.player.R
import ca.onyxtv.player.core.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.job

/**
 * Service de premier plan qui capture un flux live vers un fichier .ts du dossier privé
 * de l'application (aucune permission de stockage nécessaire). Plusieurs enregistrements
 * peuvent tourner en parallèle ; le service s'arrête de lui-même quand il n'en reste plus.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private lateinit var store: RecordingStore
    // Un enregistrement de plusieurs heures doit survivre à la veille : verrou CPU + Wi-Fi.
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(applicationContext)
        createChannel()
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "onyx:dvr").apply { setReferenceCounted(false); acquire() }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "onyx:dvr").apply { setReferenceCounted(false); acquire() }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }; wifiLock = null
    }

    /** Android 15 : budget de 6 h / 24 h pour un service dataSync ; au-delà le système nous prévient
     *  et tue l'app si on ne s'arrête pas vite. On clôt proprement tous les enregistrements. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseLocks()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return stopIfIdle()
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Enregistrement"
                val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
                // Plafond sous le budget Android 15 (6 h de service dataSync par 24 h).
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 60).coerceIn(1, 5 * 60 + 45)
                startRecording(url, title, channelId, minutes)
            }
            ACTION_STOP -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id != null) jobs.remove(id)?.cancel()
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(url: String, title: String, channelId: String, minutes: Int) {
        val id = UUID.randomUUID().toString()
        val file = File(RecordingStore.recordingsDir(this), "$id.ts")
        val info = RecordingInfo(
            id = id,
            channelId = channelId,
            channelName = title,
            filePath = file.absolutePath,
            startedAt = System.currentTimeMillis(),
            plannedMinutes = minutes,
        )
        goForeground("Enregistrement : $title")
        acquireLocks()
        // Une playlist HLS (.m3u8) copiée octet par octet donne un fichier texte inutilisable :
        // pour un direct Xtream on enregistre toujours le flux MPEG-TS continu.
        val streamUrl = if (url.contains("/live/")) url.replace(Regex("\\.m3u8(\\?.*)?$"), ".ts$1") else url

        val job = scope.launch {
            store.upsert(info)
            val deadline = info.startedAt + minutes * 60_000L
            var status = RecordingStatus.DONE
            var error: String? = null
            var size = 0L
            try {
                val request = Request.Builder().url(streamUrl).header("User-Agent", "ONYX-TV/1.0 (Android TV)").build()
                // Client SANS délai global (Http.client coupait chaque enregistrement après 240 s) ;
                // l'appel est annulé avec la coroutine (Stop immédiat, sans attendre le readTimeout).
                val call = Http.stream.newCall(request)
                coroutineContext.job.invokeOnCompletion { call.cancel() }
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Réponse vide")
                    body.byteStream().use { input ->
                        file.outputStream().buffered().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var lastFlush = System.currentTimeMillis()
                            while (System.currentTimeMillis() < deadline) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                size += n
                                val now = System.currentTimeMillis()
                                if (now - lastFlush > 5_000) {
                                    out.flush()
                                    lastFlush = now
                                    store.update(id) { it.copy(sizeBytes = size) }
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = RecordingStatus.STOPPED
            } catch (e: Exception) {
                status = if (size > 0) RecordingStatus.STOPPED else RecordingStatus.FAILED
                error = e.message
            }
            // Mise à jour finale (hors du contexte annulé si besoin).
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                store.update(id) {
                    it.copy(
                        status = status,
                        endedAt = System.currentTimeMillis(),
                        sizeBytes = maxOf(size, file.length()),
                        error = error,
                    )
                }
            }
            jobs.remove(id)
            stopIfIdle()
        }
        jobs[id] = job
    }

    private fun stopIfIdle(): Int {
        if (jobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            releaseLocks()
            stopSelf()
        } else {
            goForeground("${jobs.size} enregistrement(s) en cours")
        }
        return START_NOT_STICKY
    }

    private fun goForeground(text: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ONYX TV — DVR")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Enregistrements", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "onyx_dvr"
        private const val NOTIF_ID = 4201
        const val ACTION_START = "ca.onyxtv.player.dvr.START"
        const val ACTION_STOP = "ca.onyxtv.player.dvr.STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_ID = "id"

        fun start(context: Context, url: String, title: String, channelId: String, minutes: Int) {
            val i = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_MINUTES, minutes)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context, id: String) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id)
            // startService (pas startForegroundService) : si le service est déjà arrêté, un
            // startForegroundService sans startForeground() ferait planter l'app (Android 8+).
            runCatching { context.startService(i) }
        }
    }
}
