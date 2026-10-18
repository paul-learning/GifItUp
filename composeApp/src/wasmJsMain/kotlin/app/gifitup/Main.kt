package app.gifitup

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.gifitup.mls.loadWasmMlsClientFactory
import app.gifitup.mls.storage.claimWebDeviceStorage
import app.gifitup.ui.App
import app.gifitup.ui.OpenInAnotherTab
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Web composition root (ADR-0004). Storage is claimed before anything renders: a tab that doesn't get the device lock
 * opens no storage and only says that the app is open in another tab (ADR-0011).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        val storage = claimWebDeviceStorage()
        ComposeViewport {
            if (storage == null) {
                OpenInAnotherTab()
            } else {
                App(AppGraph(mlsClientFactory = { loadWasmMlsClientFactory() }, deviceStorage = storage))
            }
        }
    }
}
