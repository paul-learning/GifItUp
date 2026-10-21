package app.gifitup.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gifitup.ui.App
import app.gifitup.ui.ScreenProtection

/** Android composition root (ADR-0004); the graph is the `Application`'s, built once per process. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as GifItUpApp).graph
        // Bound to this window and used only inside the composition, never kept by a ViewModel.
        setContent { App(graph, screen = ScreenProtection(::protectWindow)) }
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
