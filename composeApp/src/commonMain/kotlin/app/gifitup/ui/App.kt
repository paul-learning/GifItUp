package app.gifitup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.gifitup.AppGraph
import app.gifitup.shared.domain.greeting

/**
 * Root composable shared by Android and Web. Placeholder until the real navigation lands; [graph] is first used
 * by onboarding (#17).
 */
@Suppress("UnusedParameter") // Wired now (ADR-0007, #11) so the first feature only has to read it.
@Composable
fun App(graph: AppGraph) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "GifItUp", style = MaterialTheme.typography.displayMedium)
                Text(text = greeting(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
