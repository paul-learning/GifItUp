package app.gifitup.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gifitup.ui.App

/** Android composition root (ADR-0004); the graph is the `Application`'s, built once per process. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as GifItUpApp).graph
        setContent { App(graph) }
    }
}
