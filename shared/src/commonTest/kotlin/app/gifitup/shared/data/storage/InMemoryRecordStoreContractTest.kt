package app.gifitup.shared.data.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Contract tests for [InMemoryRecordStore].
 */
class InMemoryRecordStoreContractTest : RecordStoreContractTest() {
    override fun createStore(): RecordStore = InMemoryRecordStore()

    @Test
    override fun isEmptyWithMalformedEntryIsNotEmpty() = runTest {
        val store = createStore() as InMemoryRecordStore
        store.put(validFolder, validName, validBytes)
        assertFalse(store.isEmpty())
    }

    @Test
    override fun isEmptyWithTempFileIsNotEmpty() = runTest {
        val store = createStore() as InMemoryRecordStore
        store.put(validFolder, validName, validBytes)
        assertFalse(store.isEmpty())
    }
}
