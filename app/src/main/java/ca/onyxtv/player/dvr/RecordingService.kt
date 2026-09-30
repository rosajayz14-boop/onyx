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

/**
 * Service de premier plan qui capture un flux live vers un fichier .ts du dossier privé
 * de l'application (aucune permission de stockage nécessaire). Plusieurs enregistrements
 * peuvent tourner en parallèle ; le service s'arrête de lui-même quand il n'en reste plus.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private lateinit var store: RecordingStore

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return stopIfIdle()
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Enregistrement"
                val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 60).coerceIn(1, 8 * 60)
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

        val job = scope.launch {
            store.upsert(info)
            val deadline = info.startedAt + minutes * 60_000L
            var status = RecordingStatus.DONE
            var error: String? = null
            var size = 0L
            try {
                val request = Request.Builder().url(url).header("User-Agent", "ONYX-TV/0.1 (Android TV)").build()
                Http.client.newCall(request).execute().use { resp ->
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
            ContextCompat.startForegroundService(context, i)
        }
    }
}
