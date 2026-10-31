package app.gifitup.android

import android.app.Application
import app.gifitup.AppGraph
import app.gifitup.ConversationPlatform
import app.gifitup.mls.UniffiHistoryCipherFactory
import app.gifitup.mls.UniffiMlsClientFactory
import app.gifitup.mls.storage.KeystoreKeySupplier
import app.gifitup.mls.storage.fileDeviceStorage
import app.gifitup.shared.data.device.AndroidGraphemeCounter
import app.gifitup.shared.data.device.AndroidSecurityPatch
import app.gifitup.shared.data.network.KtorMediaFetcher
import app.gifitup.shared.domain.messages.HistoryLimits
import io.ktor.client.engine.android.Android
import java.security.SecureRandom

/**
 * Builds the device storage and the app graph once per process, so an Activity that is recreated (rotation, process
 * stays alive) reuses the same storage and the same `DeviceLock` (ADR-0011, #108 SEC-22). Both are lazy: nothing
 * touches the Keystore or the disk before the first use.
 */
class GifItUpApp : Application() {
    val graph: AppGraph by lazy {
        val storage = fileDeviceStorage(filesDirForState(), KeystoreKeySupplier())
        val random = SecureRandom()
        AppGraph(
            mlsClientFactory = { UniffiMlsClientFactory },
            deviceStorage = storage,
            engine = { Android.create() },
            random = { size -> ByteArray(size).also(random::nextBytes) },
            clipboard = AndroidClipboard(this),
            glyphs = AndroidGlyphs,
            conversations = ConversationPlatform({ KtorMediaFetcher(Android.create()) }, AndroidSecurityPatch(), AndroidGraphemeCounter()),
            historyCiphers = { UniffiHistoryCipherFactory },
            historyLimits = HistoryLimits(),
        )
    }

    /** `noBackupFilesDir`: never part of a backup, even if the manifest's backup flags change. */
    private fun filesDirForState() = noBackupFilesDir.resolve("gifitup")
}
