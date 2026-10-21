package app.gifitup.mls.storage

import app.gifitup.shared.domain.auth.DeviceData
import app.gifitup.shared.domain.auth.DeviceStorage
import java.io.File

/** The wipe's platform side on Android: destroy the wrapping key, then delete the state file (ADR-0011). */
internal class FileDeviceData(
    private val store: FileLocalStore,
    private val aead: WrappingAead,
) : DeviceData {
    override suspend fun destroyWrappingKey() = aead.destroy()

    override suspend fun deleteAll() = store.deleteAll()
}

/**
 * The Android device storage (ADR-0011): [dir] must be app-private and outside backups (`Context.noBackupFilesDir`),
 * [keys] the Keystore. Build it once per process, in the `Application`, so every use case shares its one lock.
 */
fun fileDeviceStorage(
    dir: File,
    keys: WrappingKeySupplier,
): DeviceStorage = fileDeviceStorage(FileLocalStore(dir), keys)

/** The same over a given store: the JVM tests pass one without the directory sync on a Windows host (#136). */
internal fun fileDeviceStorage(
    store: FileLocalStore,
    keys: WrappingKeySupplier,
): DeviceStorage {
    val aead = JcaWrappingAead(keys)
    return DeviceStorage(store, FileDeviceData(store, aead)) { origin, identities ->
        EnvelopeSnapshotKeyStore(aead, store, identities, origin)
    }
}
