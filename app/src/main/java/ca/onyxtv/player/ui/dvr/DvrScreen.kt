package ca.onyxtv.player.ui.dvr

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.dvr.RecordingInfo
import ca.onyxtv.player.dvr.RecordingStatus
import ca.onyxtv.player.player.PlayTarget
import ca.onyxtv.player.ui.theme.OnyxCyan
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface
import ca.onyxtv.player.viewmodel.OnyxViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f Go", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f Mo", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} Ko"
}

private fun fmtDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/** Enregistrements : liste, lecture, arrêt et suppression. */
@Composable
fun DvrScreen(vm: OnyxViewModel, onPlay: (PlayTarget) -> Unit) {
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val active = recordings.count { it.status == RecordingStatus.RECORDING }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Enregistrements", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (active > 0) "● $active enregistrement(s) en cours"
                else "Depuis TV en direct, sélectionnez une chaîne puis « ● Enregistrer » pour capturer le flux ici.",
                color = if (active > 0) OnyxLive else OnyxMuted,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (recordings.isEmpty()) {
            item {
                Text(
                    "Aucun enregistrement pour le moment. Les fichiers sont stockés dans l'espace privé de l'app " +
                        "et peuvent être relus même hors connexion.",
                    color = OnyxMuted,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        items(recordings, key = { it.id }) { r ->
            RecordingRow(
                r = r,
                onPlay = {
                    onPlay(
                        PlayTarget(
                            id = "rec:${r.id}",
                            url = Uri.fromFile(File(r.filePath)).toString(),
                            title = r.channelName,
                            subtitle = "Enregistrement du ${DATE.format(Date(r.startedAt))}",
                            isLive = false,
                        )
                    )
                },
                onStop = { vm.stopRecording(r.id) },
                onDelete = { vm.deleteRecording(r.id) },
            )
        }
    }
}

@Composable
private fun RecordingRow(r: RecordingInfo, onPlay: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
    val recording = r.status == RecordingStatus.RECORDING
    val playable = !recording && File(r.filePath).exists() && r.sizeBytes > 0
    val (label, color) = when (r.status) {
        RecordingStatus.RECORDING -> "● EN COURS" to OnyxLive
        RecordingStatus.DONE -> "Terminé" to OnyxCyan
        RecordingStatus.STOPPED -> "Arrêté" to OnyxMuted
        RecordingStatus.FAILED -> "Échec" to OnyxLive
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OnyxSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(r.channelName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            listOfNotNull(
                DATE.format(Date(r.startedAt)),
                fmtDuration(r.durationMs) + if (recording) " / ${r.plannedMinutes} min prévues" else "",
                fmtSize(r.sizeBytes),
                r.error?.let { "Erreur : $it" },
            ).joinToString("  ·  "),
            color = OnyxMuted,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (playable) Button(onClick = onPlay) { Text("▶ Lire") }
            if (recording) Button(onClick = onStop) { Text("■ Arrêter") }
            Button(onClick = onDelete) { Text("Supprimer") }
        }
    }
}
