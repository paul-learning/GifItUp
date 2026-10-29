package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.data.storage.StoredIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The history key's rule (ADR-0017, #177 PR 2c): made once, only over an empty record store, never replaced. */
class OpenHistoryKeyTest {
    private val records = InMemoryRecordStore()
    private val keys = InMemoryHistoryKeyStore()
    private val identities = InMemoryIdentityStore(StoredIdentity(ORIGIN, "ann", byteArrayOf(1), "device-1"))
    private val random = CountingRandom()
    private val lock = DeviceLock()

    private fun open(
        records: RecordStore = this.records,
        identities: InMemoryIdentityStore = this.identities,
    ) = OpenHistoryKey(keys, identities, ORIGIN, records, random, lock)

    private suspend fun folderWithARecord() = records.put(NAME_A, NAME_B, byteArrayOf(1))

    @Test
    fun aNewIdentityGetsAKeyOnceAndTheSameOneAfterwards() =
        runTest {
            val first = open()()
            assertEquals(32, first.size)
            val second = open()()
            assertContentEquals(first, second)
            assertEquals(1, keys.saves)
        }

    @Test
    fun aStoredKeyIsReturnedEvenWhenTheHistoryIsGone() =
        runTest {
            val first = open()()
            folderWithARecord()
            records.deleteAll()
            assertContentEquals(first, open()())
            assertEquals(1, keys.saves)
        }

    @Test
    fun historyWithoutAKeyIsKeyUnavailableAndNoKeyIsMade() =
        runTest {
            folderWithARecord()
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { open()() }.problem)
            assertNull(keys.key)
            assertEquals(0, random.sizes.size)
        }

    @Test
    fun aKeyThatDoesNotOpenOrIsDamagedIsNeverReplaced() =
        runTest {
            for (problem in listOf(KEY_UNAVAILABLE, DAMAGED, STORAGE)) {
                val k = InMemoryHistoryKeyStore(key = ByteArray(32), problem = problem)
                val failure = assertFailsWith<LocalStateException> { OpenHistoryKey(k, identities, ORIGIN, records, random, lock)() }
                assertEquals(problem, failure.problem)
                assertEquals(0, k.saves)
            }
            assertEquals(0, random.sizes.size)
        }

    @Test
    fun aRecordStoreThatCannotSayIfItIsEmptyIsAStorageFailureAndMakesNoKey() =
        runTest {
            records.failNextRead = true
            assertEquals(STORAGE, assertFailsWith<LocalStateException> { open()() }.problem)
            assertNull(keys.key)
            assertNotNull(open()(), "the retry works")
        }

    @Test
    fun aStoreThatListsNothingButHoldsSomethingIsNotEmpty() =
        runTest {
            val odd =
                object : RecordStore by records {
                    override suspend fun folders(): List<String> = emptyList()

                    override suspend fun isEmpty(): Boolean = false
                }
            assertEquals(KEY_UNAVAILABLE, assertFailsWith<LocalStateException> { open(odd)() }.problem)
            val broken =
                object : RecordStore by records {
                    override suspend fun isEmpty(): Boolean = throw RecordStoreException()
                }
            assertEquals(STORAGE, assertFailsWith<LocalStateException> { open(broken)() }.problem)
            assertNull(keys.key)
        }

    @Test
    fun aFailedSaveLeavesNoKeyAndRethrows() =
        runTest {
            val failing =
                object : app.gifitup.shared.data.storage.HistoryKeyStore {
                    override suspend fun load(): ByteArray? = null

                    override suspend fun save(key: ByteArray): Unit = throw LocalStateException(KEY_UNAVAILABLE)

                    override suspend fun clear() = Unit
                }
            val failure = assertFailsWith<LocalStateException> { OpenHistoryKey(failing, identities, ORIGIN, records, random, lock)() }
            assertEquals(KEY_UNAVAILABLE, failure.problem)
        }

    @Test
    fun onlyARegisteredIdentityOfThisOriginGetsAKey() =
        runTest {
            assertFailsWith<IllegalStateException> { open(identities = InMemoryIdentityStore(null))() }
            assertFailsWith<IllegalStateException> {
                open(identities = InMemoryIdentityStore(StoredIdentity(ORIGIN, "ann", byteArrayOf(1), null)))()
            }
            assertFailsWith<IllegalStateException> {
                open(identities = InMemoryIdentityStore(StoredIdentity("https://other.example", "ann", byteArrayOf(1), "d")))()
            }
            assertNull(keys.key)
        }

    @Test
    fun concurrentCallsMakeOneKey() =
        runTest {
            val all = List(5) { async { open()() } }.awaitAll()
            assertEquals(1, keys.saves)
            all.forEach { assertContentEquals(all.first(), it) }
        }

    @Test
    fun theCallHoldsTheStoragesLock() =
        runTest {
            val call =
                lock.holding {
                    val call = async { open()() }
                    testScheduler.runCurrent()
                    assertEquals(false, call.isCompleted, "blocked while the lock is held")
                    assertEquals(0, keys.saves)
                    assertEquals(0, random.sizes.size)
                    call
                }
            assertEquals(32, call.await().size)
        }

    private companion object {
        const val ORIGIN = "https://gifitup.example"
        val NAME_A = "a".repeat(64)
        val NAME_B = "b".repeat(64)
    }
}
