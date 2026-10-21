package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gifitup.shared.presentation.settings.ServerSettingsStateHolder

/**
 * The only place the server origin can change (ADR-0013): typed by the user, never filled in from a link or an intent.
 * The logic is the state holder's; this renders it.
 */
@Composable
internal fun SettingsScreen(
    holder: ServerSettingsStateHolder,
    currentOrigin: String,
    defaultOrigin: String,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf(currentOrigin) }
    val problem by holder.problem.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Text("Only change this if the person who runs your server told you to. Default: $defaultOrigin")
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("https://…") }, singleLine = true)
        problem?.let { Text(Texts.settings(it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { holder.apply(text) }) { Text("Use this server") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
