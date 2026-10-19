package app.gifitup.android

import android.app.Application
import app.gifitup.AppGraph
import app.gifitup.mls.UniffiMlsClientFactory
import app.gifitup.mls.storage.KeystoreKeySupplier
import app.gifitup.mls.storage.fileDeviceStorage

/**
 * Builds the device storage and the app graph once per process, so an Activity that is recreated (rotation, process
 * stays alive) reuses the same storage and the same `DeviceLock` (ADR-0011, #108 SEC-22). Both are lazy: nothing
 * touches the Keystore or the disk before the first use.
 */
class GifItUpApp : Application() {
    val graph: AppGraph by lazy {
        val storage = fileDeviceStorage(filesDirForState(), KeystoreKeySupplier())
        AppGraph(mlsClientFactory = { UniffiMlsClientFactory }, deviceStorage = storage)
    }

    /** `noBackupFilesDir`: never part of a backup, even if the manifest's backup flags change. */
    private fun filesDirForState() = noBackupFilesDir.resolve("gifitup")
}
