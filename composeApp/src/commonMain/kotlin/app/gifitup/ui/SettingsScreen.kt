package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar("Server", onBack, modifier = Modifier.padding(bottom = 4.dp))
        CardSurface {
            Text("Only change this if the person who runs your server told you to. Default: $defaultOrigin")
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("https://…") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Text(Texts.settings(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.polite()) }
            PrimaryButton("Use this server", onClick = { holder.apply(text) })
        }
    }
}
