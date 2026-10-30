package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.data.storage.RecordStore

/** The in-memory store (tests and anything without a platform store) keeps the same contract as the platform stores. */
class InMemoryRecordStoreContractTest : RecordStoreContractTest() {
    override fun createStore(): RecordStore = InMemoryRecordStore()
}
