package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.auth.DeviceData
import app.gifitup.shared.domain.auth.DeviceStorage
import java.io.File

/**
 * The wipe's platform side on Android: destroy the wrapping key, then delete the history records and the state file
 * (ADR-0011, ADR-0017). The history goes **first**: if it cannot be deleted the identity file stays (unreadable once the
 * wrapping key is gone, so `KEY_UNAVAILABLE`) and the app offers the wipe again, instead of looking clean with sealed
 * records still on disk.
 */
internal class FileDeviceData(
    private val store: FileLocalStore,
    private val aead: WrappingAead,
    private val records: RecordStore,
) : DeviceData {
    override suspend fun destroyWrappingKey() = aead.destroy()

    override suspend fun deleteAll() {
        deleteHistory(records)
        store.deleteAll()
    }
}

/** A history that cannot be deleted fails the wipe with the storage error the wipe already reports. */
internal suspend fun deleteHistory(records: RecordStore) {
    try {
        records.deleteAll()
    } catch (_: RecordStoreException) {
        throw LocalStateException(LocalStateException.Problem.STORAGE)
    }
}

/**
 * The Android device storage (ADR-0011): [dir] must be app-private and outside backups (`Context.noBackupFilesDir`),
 * [keys] the Keystore. Build it once per process, in the `Application`, so every use case shares its one lock.
 */
fun fileDeviceStorage(
    dir: File,
    keys: WrappingKeySupplier,
): DeviceStorage = fileDeviceStorage(FileLocalStore(dir), keys, FileRecordStore(File(dir, HISTORY_DIR)))

/** The same over a given store: the JVM tests pass one without the directory sync on a Windows host (#136). */
internal fun fileDeviceStorage(
    store: FileLocalStore,
    keys: WrappingKeySupplier,
    records: RecordStore,
): DeviceStorage {
    val aead = JcaWrappingAead(keys)
    return DeviceStorage(
        store,
        FileDeviceData(store, aead, records),
        records,
        { origin, identities -> EnvelopeSnapshotKeyStore(aead, store, identities, origin) },
        { origin -> EnvelopeHistoryKeyStore(aead, store, origin) },
    )
}

/** The history records live next to the state file, in the same app-private directory outside backups (ADR-0017). */
private const val HISTORY_DIR = "history"
