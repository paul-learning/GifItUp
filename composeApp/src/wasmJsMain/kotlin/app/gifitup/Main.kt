package app.gifitup

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.gifitup.mls.loadWasmMlsClientFactory
import app.gifitup.ui.App

/** Web composition root (ADR-0004). */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val graph = AppGraph(mlsClientFactory = { loadWasmMlsClientFactory() })
    ComposeViewport { App(graph) }
}
