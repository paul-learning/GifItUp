package app.gifitup.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gifitup.AppGraph
import app.gifitup.mls.UniffiMlsClientFactory
import app.gifitup.ui.App

/** Android composition root (ADR-0004). */
class MainActivity : ComponentActivity() {
    private val graph = AppGraph(mlsClientFactory = { UniffiMlsClientFactory })

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(graph) }
    }
}
