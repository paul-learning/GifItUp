package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.RecordStoreContractTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Contract tests for [IndexedDbRecordStore].
 */
class IndexedDbRecordStoreContractTest : RecordStoreContractTest() {
    override fun createStore(): IndexedDbRecordStore = IndexedDbRecordStore()

    @Test
    override fun isEmptyWithMalformedEntryIsNotEmpty() = runTest {
        val store = createStore()
        val db = IndexedDbRecordStore.settled(IndexedDbRecordStore.idbOpen(IndexedDbRecordStore.DATABASE_NAME))
        IndexedDbRecordStore.settled(
            IndexedDbRecordStore.idbPut(
                db!!,
                IndexedDbRecordStore.STORE_NAME,
                "malformed-key",
                byteArrayOf(1).toUint8Array(),
            ),
        )
        assertFalse(store.isEmpty())
    }

    @Test
    override fun isEmptyWithTempFileIsNotEmpty() = runTest {
        val store = createStore()
        store.put(validFolder, validName, validBytes)
        assertFalse(store.isEmpty())
    }
}
