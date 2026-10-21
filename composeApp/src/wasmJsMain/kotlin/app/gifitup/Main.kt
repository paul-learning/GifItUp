package app.gifitup

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.gifitup.mls.WebSecureRandom
import app.gifitup.mls.loadWasmMlsClientFactory
import app.gifitup.mls.storage.claimWebDeviceStorage
import app.gifitup.ui.App
import app.gifitup.ui.OpenInAnotherTab
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Takes an invite link out of the address bar (`<origin>/invite#<code>`) and replaces the address with `/` at once,
 * before anything renders, so the code is not left in the address bar or the history (#17 plan, SEC-3). The code is
 * only in the fragment, which browsers never send to a server or put into a Referer. Null when the page is not an
 * invite link.
 */
private fun takeInviteLink(): String? =
    js(
        """(() => {
            try {
                if (location.pathname !== '/invite' || !location.hash) return null;
                const link = location.origin + '/invite' + location.hash;
                history.replaceState(null, '', '/');
                return link;
            } catch (e) {
                return null;
            }
        })()""",
    )

/**
 * Web composition root (ADR-0004). Storage is claimed before anything renders: a tab that doesn't get the device lock
 * opens no storage and only says that the app is open in another tab (ADR-0011).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val invite = takeInviteLink()
    MainScope().launch {
        val storage = claimWebDeviceStorage()
        // Built once, outside composition: a recomposition must never make a second graph with a second lock.
        val graph =
            storage?.let {
                AppGraph(
                    mlsClientFactory = { loadWasmMlsClientFactory() },
                    deviceStorage = it,
                    engine = { Js.create() },
                    random = WebSecureRandom,
                )
            }
        ComposeViewport {
            if (graph == null) OpenInAnotherTab() else App(graph, invite)
        }
    }
}
