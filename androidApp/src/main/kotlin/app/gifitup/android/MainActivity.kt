package app.gifitup.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gifitup.shared.domain.invite.LaunchLink
import app.gifitup.ui.App
import app.gifitup.ui.InviteInbox
import app.gifitup.ui.ScreenProtection

/** Android composition root (ADR-0004); the graph is the `Application`'s, built once per process. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as GifItUpApp).graph
        // The launch intent is untrusted input (any app can start this exported activity with any extras). Read the one
        // thing the app wants from it, then replace it, so the NavHost never sees its extras or data: Navigation would
        // honour a foreign `deepLinkIds` extra and start on a screen of the sender's choice (#131 SEC-21). Every
        // creation does this, because a rotation or a restore hands the activity its original intent again.
        val link =
            LaunchLink.candidate(
                isView = intent.action == Intent.ACTION_VIEW,
                data = intent.dataString,
                freshLaunch = savedInstanceState == null,
            )
        setIntent(Intent(Intent.ACTION_MAIN))
        val inbox = InviteInbox(link)
        // Bound to this window and used only inside the composition, never kept by a ViewModel.
        setContent { App(graph, inbox, ScreenProtection(::protectWindow)) }
    }

    /** `FLAG_SECURE`: no screenshots, no screen recording, a blank recents thumbnail (the invite screen, #17 SEC-8). */
    private fun protectWindow(on: Boolean) {
        if (on) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
