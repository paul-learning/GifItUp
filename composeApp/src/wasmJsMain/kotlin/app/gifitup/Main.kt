package app.gifitup

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.gifitup.ui.App

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { App() }
}
