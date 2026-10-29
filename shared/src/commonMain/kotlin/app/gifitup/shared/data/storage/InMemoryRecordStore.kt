package app.gifitup.shared.data.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A [RecordStore] in memory: for tests and for anything that must run without a platform store. Keeps the name rule. */
class InMemoryRecordStore : RecordStore {
    private val mutex = Mutex()
    private val data = LinkedHashMap<String, LinkedHashMap<String, ByteArray>>()

    /** Makes the next write or delete fail, once: lets a test see how the callers behave on a store failure. */
    var failNextWrite = false

    override suspend fun put(
        folder: String,
        name: String,
        bytes: ByteArray,
    ) = mutex.withLock {
        check(folder, name)
        failIfAsked()
        data.getOrPut(folder) { LinkedHashMap() }[name] = bytes.copyOf()
    }

    override suspend fun get(
        folder: String,
        name: String,
    ): ByteArray? =
        mutex.withLock {
            check(folder, name)
            data[folder]?.get(name)?.copyOf()
        }

    override suspend fun list(folder: String): List<String> =
        mutex.withLock {
            check(folder)
            data[folder]?.keys?.toList().orEmpty()
        }

    override suspend fun delete(
        folder: String,
        name: String,
    ) = mutex.withLock {
        check(folder, name)
        failIfAsked()
        data[folder]?.remove(name)
        if (data[folder]?.isEmpty() == true) data.remove(folder)
        Unit
    }

    override suspend fun deleteFolder(folder: String) =
        mutex.withLock {
            check(folder)
            failIfAsked()
            data.remove(folder)
            Unit
        }

    override suspend fun folders(): List<String> = mutex.withLock { data.keys.toList() }

    override suspend fun deleteAll() =
        mutex.withLock {
            data.clear()
        }

    /** The raw bytes of every record, for a test that tampers with them. */
    suspend fun snapshot(): Map<String, Map<String, ByteArray>> =
        mutex.withLock {
            data.mapValues { (_, v) ->
                v.mapValues { it.value.copyOf() }
            }
        }

    private fun check(
        folder: String,
        name: String? = null,
    ) {
        if (!isRecordName(folder) || (name != null && !isRecordName(name))) throw RecordStoreException()
    }

    private fun failIfAsked() {
        if (failNextWrite) {
            failNextWrite = false
            throw RecordStoreException()
        }
    }
}
