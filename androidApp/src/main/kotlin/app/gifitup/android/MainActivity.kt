package app.gifitup.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import app.gifitup.shared.domain.invite.LaunchLink
import app.gifitup.ui.App
import app.gifitup.ui.InviteInbox
import app.gifitup.ui.ScreenProtection

/** Android composition root (ADR-0004); the graph is the `Application`'s, built once per process. */
class MainActivity : ComponentActivity() {
    // The diagnostics report's way out (#154 follow-up). The launcher is registered here, before the activity starts, as the
    // result API requires; the export is used only inside the composition.
    private val saveLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export.write(it) }
    private val export: AndroidLogExport = AndroidLogExport(this) { name -> saveLauncher.launch(name) }

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
        setContent { App(graph, inbox, screenProtection, export) }
    }

    /**
     * A foreign app can deliver an intent to a running instance too (`FLAG_ACTIVITY_SINGLE_TOP`, or an `intent:` link in
     * a web page): its data and extras are ignored the same way as at creation, and the listeners (Navigation among
     * them) get a bare intent, so no `deepLinkIds` extra can move the app to another screen (#145 SEC-1). A link
     * that arrives like this is not offered to the onboarding either; the code can be pasted.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(Intent(Intent.ACTION_MAIN))
        setIntent(Intent(Intent.ACTION_MAIN))
    }

    /**
     * `FLAG_SECURE`: no screenshots, no screen recording, a blank recents thumbnail. Counted: the flag is on while any
     * `protect(true)` is open (the invite screen, the diagnostics, the chats' setting), so one screen leaving never lifts another's.
     */
    private val screenProtection =
        object : ScreenProtection {
            private var open = 0

            override fun protect(on: Boolean) {
                open = (open + if (on) 1 else -1).coerceAtLeast(0)
                if (open > 0) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
}
