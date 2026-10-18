package app.gifitup.shared.data.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** An atomic in-memory [LocalStore]; [writes] records each [putAll] as one unit. */
class InMemoryLocalStore : LocalStore {
    val entries = mutableMapOf<String, ByteArray>()
    val writes = mutableListOf<Map<String, ByteArray?>>()

    override suspend fun get(key: String): ByteArray? = entries[key]?.copyOf()

    override suspend fun putAll(entries: Map<String, ByteArray?>) {
        writes += entries
        entries.forEach { (key, value) -> if (value == null) this.entries.remove(key) else this.entries[key] = value.copyOf() }
    }
}

class StoredRecordsTest {
    private val store = InMemoryLocalStore()
    private val identities = RecordIdentityStore(store)
    private val pending = StoredIdentity("https://e2e.invalid", "ann", byteArrayOf(9, 8, 7), deviceId = null)

    @Test
    fun identityRoundTripsPendingAndRegisteredAndClears() =
        runTest {
            assertNull(identities.load())
            identities.save(pending)
            assertEquals(null, identities.load()?.deviceId)
            assertContentEquals(pending.snapshot, identities.load()?.snapshot)
            identities.save(StoredIdentity(pending.origin, pending.username, byteArrayOf(1), "8bbdc3b8-7e61-428b-a97f-054194069109"))
            val loaded = identities.load()!!
            assertEquals(
                listOf("https://e2e.invalid", "ann", "8bbdc3b8-7e61-428b-a97f-054194069109"),
                listOf(loaded.origin, loaded.username, loaded.deviceId),
            )
            identities.clear()
            assertNull(identities.load())
        }

    /** "No old snapshots" in the logical store (#16 SEC-3): one record, replaced in one write; physical copies aren't ours. */
    @Test
    fun theLogicalStoreKeepsOnlyTheLatestRecord() =
        runTest {
            identities.save(pending)
            identities.save(StoredIdentity(pending.origin, pending.username, byteArrayOf(5), deviceId = null))
            assertEquals(setOf(RecordIdentityStore.KEY), store.entries.keys)
            assertContentEquals(byteArrayOf(5), identities.load()?.snapshot)
            assertEquals(1, store.writes.last().size)
        }

    @Test
    fun aDamagedRecordIsRefusedNeverGuessed() =
        runTest {
            val good = RecordIdentityStore.encode(pending)
            val damaged =
                listOf(
                    byteArrayOf(),
                    byteArrayOf(2) + good.copyOfRange(1, good.size), // unknown version
                    good.copyOf(5), // truncated text
                    good.copyOf(good.size - pending.snapshot.size), // no snapshot
                    byteArrayOf(1, 0, 0) + good.copyOfRange(3, good.size), // empty origin
                    good.copyOf().also { it[it.size - pending.snapshot.size - 1] = 7 }, // bad device-id marker
                    byteArrayOf(1, 0, 2, 0xC3.toByte(), 0x28) + good.copyOfRange(3 + 19, good.size), // invalid UTF-8
                )
            damaged.forEachIndexed { i, bytes ->
                store.entries[RecordIdentityStore.KEY] = bytes
                val error = assertFailsWith<LocalStateException>("case $i") { identities.load() }
                assertEquals(LocalStateException.Problem.DAMAGED, error.problem)
            }
        }

    @Test
    fun theRestoreCountSurvivesAndADamagedCountBlocks() =
        runTest {
            val counter = StoredRestoreCounter(store)
            assertEquals(0, counter.failures())
            counter.set(2)
            assertEquals(2, StoredRestoreCounter(store).failures())
            store.entries[StoredRestoreCounter.KEY] = byteArrayOf(1, 2)
            assertEquals(Byte.MAX_VALUE.toInt(), counter.failures())
            store.entries[StoredRestoreCounter.KEY] = byteArrayOf(-1)
            assertEquals(Byte.MAX_VALUE.toInt(), counter.failures())
        }
}
