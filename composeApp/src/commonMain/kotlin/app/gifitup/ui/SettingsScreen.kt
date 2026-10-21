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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gifitup.shared.domain.origin.ServerOriginSetting
import kotlinx.coroutines.launch

/**
 * The only place the server origin can change (ADR-0013): typed by the user, never filled in from a link or an intent.
 * It is refused while the device has an identity; [onChanged] rebuilds everything bound to the old origin.
 */
@Composable
internal fun SettingsScreen(
    setting: ServerOriginSetting,
    currentOrigin: String,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf(currentOrigin) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Text("Only change this if the person who runs your server told you to. Default: ${setting.default}")
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("https://…") }, singleLine = true)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            scope.launch {
                when (setting.set(text)) {
                    is ServerOriginSetting.SetResult.Changed -> onChanged()
                    ServerOriginSetting.SetResult.Invalid -> message = "Enter https:// and the server's name, nothing after it."
                    ServerOriginSetting.SetResult.HasIdentity -> message = "This device is registered with its server; it can't switch."
                }
            }
        }) { Text("Use this server") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
