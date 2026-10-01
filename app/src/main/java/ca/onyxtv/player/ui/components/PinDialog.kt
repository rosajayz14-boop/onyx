package ca.onyxtv.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ca.onyxtv.player.ui.theme.OnyxLive
import ca.onyxtv.player.ui.theme.OnyxMuted
import ca.onyxtv.player.ui.theme.OnyxSurface

/**
 * Saisie du code PIN (contrôle parental). [onSubmit] renvoie true si le code est accepté ;
 * sinon un message d'erreur s'affiche et le champ est vidé.
 */
@Composable
fun PinDialog(
    title: String,
    subtitle: String? = null,
    onSubmit: (String) -> Boolean,
    onCancel: (() -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(6) { kotlinx.coroutines.delay(120); runCatching { fieldFocus.requestFocus() } }
    }

    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6050509)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(OnyxSurface)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🔒 $title", style = MaterialTheme.typography.headlineMedium)
            subtitle?.let { Text(it, color = OnyxMuted, style = MaterialTheme.typography.bodyLarge) }
            Md3(colorScheme = md3DarkColors()) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> if (v.length <= 4 && v.all { it.isDigit() }) { pin = v; wrong = false } },
                    label = { androidx.compose.material3.Text("Code PIN (4 chiffres)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, showKeyboardOnFocus = false),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.focusRequester(fieldFocus),
                )
            }
            if (wrong) Text("Code incorrect.", color = OnyxLive)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (pin.length == 4 && onSubmit(pin)) Unit else { wrong = true; pin = "" }
                }) { Text("Valider") }
                onCancel?.let { Button(onClick = it) { Text("Annuler") } }
            }
        }
    }
}
