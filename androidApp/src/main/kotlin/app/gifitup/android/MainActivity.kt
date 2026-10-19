package app.gifitup.android

import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gifitup.mls.storage.KeystoreKeySupplier
import app.gifitup.shared.data.storage.LocalStateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.SecretKeyFactory

/**
 * SPIKE (#16 device check). Throwaway: NEVER MERGE. It replaces the real MainActivity with four buttons that exercise
 * the Android device storage on a real phone, and shows each result as text. Not part of any PR to main.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GifItUpApp
        setContent { MaterialTheme { Harness(app) } }
    }
}

private const val ORIGIN = "https://harness.invalid"
private val KEY = ByteArray(32) { (it + 1).toByte() }

@Composable
private fun Harness(app: GifItUpApp) {
    var log by remember { mutableStateOf("Tap the buttons in order. See the PR for what each result must be.") }
    val scope = rememberCoroutineScope()
    val storage = remember { app.graph.deviceStorage!! }

    fun run(name: String, block: suspend () -> String) {
        scope.launch {
            val result =
                try {
                    withContext(Dispatchers.IO) { block() }
                } catch (e: LocalStateException) {
                    "FAILED: ${e.problem}"
                } catch (e: Exception) {
                    "FAILED: ${e::class.simpleName}"
                }
            log = "$name\n$result\n\n$log"
        }
    }

    fun files(): String {
        val dir = app.noBackupFilesDir.resolve("gifitup")
        return dir.list().orEmpty().sorted().joinToString { "$it (${dir.resolve(it).length()} bytes)" }.ifEmpty { "(no files)" }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Button(onClick = {
            run("1. Save key") {
                storage.snapshotKeys(ORIGIN).save(KEY.copyOf())
                "saved. files: ${files()}"
            }
        }) { Text("1. Save key") }
        Button(onClick = {
            run("2. Load key") {
                val key = storage.snapshotKeys(ORIGIN).load()
                val verdict = if (key == null) "NOTHING STORED" else if (key.contentEquals(KEY)) "SAME KEY: OK" else "DIFFERENT KEY: BAD"
                "$verdict\nfiles: ${files()}"
            }
        }) { Text("2. Load key") }
        Button(onClick = {
            run("3. Wipe") {
                storage.data.destroyWrappingKey()
                storage.data.deleteAll()
                "wiped. files: ${files()}"
            }
        }) { Text("3. Wipe") }
        Button(onClick = {
            run("4. Key hardware") {
                val key = KeystoreKeySupplier().get() ?: return@run "no Keystore key (expected after the wipe)"
                val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
                val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "securityLevel=${info.securityLevel}" else "insideSecureHardware=${info.isInsideSecureHardware}"
                "Android ${Build.VERSION.SDK_INT}, $level (1=TEE, 2=StrongBox on API 31+)"
            }
        }) { Text("4. Key hardware") }
        Text(log, Modifier.padding(top = 16.dp))
    }
}
