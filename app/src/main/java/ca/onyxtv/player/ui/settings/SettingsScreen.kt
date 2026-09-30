package ca.onyxtv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme as Md3
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.darkColorScheme as md3DarkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface
import ca.onyxtv.player.viewmodel.OnyxViewModel

@Composable
fun SettingsScreen(vm: OnyxViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Réglages", style = MaterialTheme.typography.headlineLarge) }
        item { Text("Sources configurées", style = MaterialTheme.typography.titleLarge) }

        if (sources.isEmpty()) {
            item {
                Text(
                    "Aucune source. Ajoutez une liste M3U ou un compte Xtream ci-dessous.",
                    color = OnyxMuted,
                )
            }
        } else {
            items(sources, key = { it.id }) { s -> SourceRow(s) { vm.removeSource(s.id) } }
        }

        item { AddM3uCard(vm) }
        item { AddXtreamCard(vm) }
    }
}

@Composable
private fun SourceRow(source: PlaylistSource, onRemove: () -> Unit) {
    val (type, detail) = when (source) {
        is PlaylistSource.M3u -> "M3U" to source.url
        is PlaylistSource.Xtream -> "Xtream" to source.server
    }
    Card(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text(source.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$type · $detail",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnyxMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Retirer (OK)", color = OnyxLive, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FormCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OnyxSurface)
            .padding(20.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        // Les champs de saisie utilisent Material 3 (sombre) pour un rendu correct sur TV.
        Md3(colorScheme = md3DarkColors()) {
            Column(content = content)
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
private fun AddM3uCard(vm: OnyxViewModel) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }

    FormCard("Ajouter une liste M3U") {
        Field("Nom", label) { label = it }
        Field("URL .m3u / .m3u8", url) { url = it }
        Field("URL EPG XMLTV (optionnel)", epg) { epg = it }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (url.isNotBlank()) {
                vm.addM3u(label, url, epg.ifBlank { null })
                label = ""; url = ""; epg = ""
            }
        }) { Text("Ajouter la liste") }
    }
}

@Composable
private fun AddXtreamCard(vm: OnyxViewModel) {
    var label by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    FormCard("Ajouter un compte Xtream Codes") {
        Field("Nom", label) { label = it }
        Field("Serveur (http://exemple.tv:8080)", server) { server = it }
        Field("Nom d'utilisateur", user) { user = it }
        Field("Mot de passe", pass) { pass = it }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (server.isNotBlank() && user.isNotBlank()) {
                vm.addXtream(label, server, user, pass)
                label = ""; server = ""; user = ""; pass = ""
            }
        }) { Text("Ajouter le compte") }
    }
}
