package ca.onyxtv.player.ui.dvr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.ui.theme.OnyxMuted

/**
 * Enregistrements (DVR) + Catch-up.
 *
 * L'enregistrement des flux live nécessite un service de fond qui écrit le flux
 * sur le stockage (voir docs/ROADMAP.md). Cet écran est le point d'entrée : il
 * listera les enregistrements planifiés et terminés, et la reprise (catch-up).
 */
@Composable
fun DvrScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(36.dp)
    ) {
        Text("Enregistrements", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Vos enregistrements planifiés et terminés, ainsi que la reprise (catch-up), apparaîtront ici.",
            style = MaterialTheme.typography.bodyLarge,
            color = OnyxMuted,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "À implémenter : service d'enregistrement (capture du flux vers le stockage), " +
                "gestion de l'espace disque et intégration catch-up des fournisseurs Xtream.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnyxMuted,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
