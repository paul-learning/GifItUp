package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.gifitup.shared.presentation.diagnostics.DiagnosticsStateHolder
import app.gifitup.shared.presentation.invite.SensitiveClipboard
import kotlinx.coroutines.launch

/**
 * About and diagnostics (#154 follow-up): the version, and the log the user can read, save, share or clear. It renders the
 * holder's state and decides nothing (ADR-0013). The whole report is on screen before it can leave the device, and the screen is
 * kept out of screenshots like the invite screen.
 */
@Composable
internal fun DiagnosticsScreen(
    holder: DiagnosticsStateHolder,
    export: LogExport,
    clipboard: SensitiveClipboard,
    screen: ScreenProtection,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(holder) { holder.refresh() }
    DisposableEffect(holder) {
        screen.protect(true)
        onDispose {
            screen.protect(false)
            holder.leave()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppTopBar(DiagnosticsTexts.TITLE, onBack, modifier = Modifier.padding(bottom = 4.dp))
        CardSurface {
            Text(DiagnosticsTexts.version(state.version), style = MaterialTheme.typography.titleMedium)
            Text(DiagnosticsTexts.EXPLAIN, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.report?.let { report ->
            CardSurface {
                Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text(DiagnosticsTexts.NOTICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (export.canShare) PrimaryButton(DiagnosticsTexts.SHARE, { export.share(report) }, Modifier.weight(1f))
                PrimaryButton(DiagnosticsTexts.SAVE, { export.save(report) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(DiagnosticsTexts.COPY, { scope.launch { copied = clipboard.copy(report) } }, Modifier.weight(1f))
                SecondaryButton(DiagnosticsTexts.CLEAR, holder::clear, Modifier.weight(1f))
            }
            copied?.let {
                Text(if (it) DiagnosticsTexts.COPIED else DiagnosticsTexts.COPY_FAILED, modifier = Modifier.polite())
            }
        }
    }
}

/** The fixed texts of the diagnostics screen. */
internal object DiagnosticsTexts {
    const val TITLE = "About and diagnostics"
    const val SHARE = "Share"
    const val SAVE = "Save to file"
    const val COPY = "Copy"
    const val CLEAR = "Clear log"
    const val COPIED = "Copied."
    const val COPY_FAILED = "This device didn't allow copying."
    const val EXPLAIN =
        "If something goes wrong, save or share this log and send it to the person who runs the app. It lists what the app did " +
            "and when, to the minute. It never contains your messages, GIFs, names, keys or the server's address."
    const val NOTICE =
        "This is everything that leaves the device if you share it. It shows when app events happened, not what was said. " +
            "An email or a messenger you pick isn't end-to-end encrypted by this app."

    fun version(version: String) = "GifItUp $version"
}
