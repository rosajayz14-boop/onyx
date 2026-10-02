package ca.onyxtv.player.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.onyxtv.player.MainActivity
import ca.onyxtv.player.R
import ca.onyxtv.player.core.data.Reminder
import ca.onyxtv.player.dvr.RecordingService
import java.util.concurrent.TimeUnit

/**
 * Rappel de programme / enregistrement programmé, même si l'app est fermée : notification
 * système à l'heure voulue ; pour un enregistrement, tentative de lancement du service DVR
 * (Android peut le refuser depuis l'arrière-plan : on prévient alors d'ouvrir l'app).
 * Quand l'app est ouverte, c'est le ViewModel qui agit directement (dialogue / enregistrement).
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: "Programme"
        val channel = inputData.getString("channel") ?: ""
        val record = inputData.getBoolean("record", false)
        val url = inputData.getString("url")
        val channelId = inputData.getString("channelId") ?: ""
        val minutes = inputData.getInt("minutes", 60)
        if (record && !url.isNullOrBlank()) {
            val started = runCatching { RecordingService.start(applicationContext, url, "$channel — $title", channelId, minutes) }.isSuccess
            if (!started) notify("Enregistrement programmé", "Ouvrez ONYX TV pour enregistrer « $title » sur $channel.")
            return Result.success()
        }
        notify("⏰ $title", "commence maintenant sur $channel — ouvrez ONYX TV.")
        return Result.success()
    }

    private fun notify(title: String, text: String) = runCatching {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Rappels de programmes", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100_000).toInt(), n)
    }

    companion object {
        private const val CHANNEL = "onyx_reminders"

        fun schedule(context: Context, r: Reminder, url: String?) {
            val fireAt = if (r.record) r.start - 30_000L else r.start - 2 * 60_000L
            val delay = (fireAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val minutes = ((r.stop - r.start) / 60_000L).toInt().coerceIn(1, 5 * 60 + 45)
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .putString("title", r.title).putString("channel", r.channelName)
                        .putBoolean("record", r.record).putString("url", url)
                        .putString("channelId", r.channelId).putInt("minutes", minutes)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("reminder:${r.id}", ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("reminder:$id")
        }
    }
}
