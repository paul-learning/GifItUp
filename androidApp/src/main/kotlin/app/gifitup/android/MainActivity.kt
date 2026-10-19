package app.gifitup.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.gifitup.mls.storage.KeystoreKeySupplier
import app.gifitup.shared.data.storage.LocalStateException
import java.util.concurrent.CountDownLatch
import javax.crypto.SecretKeyFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * SPIKE (#16 device check). Throwaway: NEVER MERGE. It replaces the real MainActivity with four buttons that exercise
 * the Android device storage on a real phone, and shows each result as text. Plain Android views and the Kotlin
 * standard library only, so it needs nothing the app doesn't already have.
 */
class MainActivity : Activity() {
    private lateinit var output: TextView
    private var log = "Tap the buttons in order. See the PR for what each result must be."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = (application as GifItUpApp).graph.deviceStorage!!
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.setPadding(PADDING, PADDING * 2, PADDING, PADDING)
        output = TextView(this).apply { text = log }

        fun button(
            label: String,
            action: suspend () -> String,
        ) {
            column.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { run(label, action) }
                },
            )
        }
        button("1. Save key") {
            storage.snapshotKeys(ORIGIN).save(KEY.copyOf())
            "saved. files: ${files()}"
        }
        button("2. Load key") {
            val key = storage.snapshotKeys(ORIGIN).load()
            val verdict =
                if (key == null) {
                    "NOTHING STORED"
                } else if (key.contentEquals(KEY)) {
                    "SAME KEY: OK"
                } else {
                    "DIFFERENT KEY: BAD"
                }
            "$verdict\nfiles: ${files()}"
        }
        button("3. Wipe") {
            storage.data.destroyWrappingKey()
            storage.data.deleteAll()
            "wiped. files: ${files()}"
        }
        button("4. Key hardware") { hardware() }
        column.addView(output)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    @Suppress("TooGenericExceptionCaught") // Throwaway harness: show any failure as text.
    private fun run(
        name: String,
        action: suspend () -> String,
    ) {
        Thread {
            val result =
                try {
                    blocking(action)
                } catch (e: LocalStateException) {
                    "FAILED: ${e.problem}"
                } catch (e: Exception) {
                    "FAILED: ${e::class.simpleName}"
                }
            runOnUiThread {
                log = "$name\n$result\n\n$log"
                output.text = log
            }
        }.start()
    }

    /** The harness's suspend calls never really suspend (blocking I/O), so they finish on the calling thread. */
    private fun <T> blocking(action: suspend () -> T): T {
        var outcome: Result<T>? = null
        val done = CountDownLatch(1)
        action.startCoroutine(
            Continuation(EmptyCoroutineContext) {
                outcome = it
                done.countDown()
            },
        )
        done.await()
        return outcome!!.getOrThrow()
    }

    private fun files(): String {
        val dir = noBackupFilesDir.resolve("gifitup")
        return dir
            .list()
            .orEmpty()
            .sorted()
            .joinToString { "$it (${dir.resolve(it).length()} bytes)" }
            .ifEmpty { "(no files)" }
    }

    private fun hardware(): String {
        val key = KeystoreKeySupplier().get() ?: return "no Keystore key (expected after the wipe)"
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val level =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "securityLevel=${info.securityLevel} (1 = TEE, 2 = StrongBox)"
            } else {
                "insideSecureHardware=${info.isInsideSecureHardware}"
            }
        return "Android ${Build.VERSION.SDK_INT}, $level"
    }

    private companion object {
        const val ORIGIN = "https://harness.invalid"
        const val PADDING = 32
        val KEY = ByteArray(32) { (it + 1).toByte() }
    }
}
