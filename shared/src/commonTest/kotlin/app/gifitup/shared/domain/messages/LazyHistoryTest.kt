package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.crypto.FakeHistoryCipher
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The history's life (ADR-0017): one store at a time, sticky and cooling-down failures, and a close that really ends it. */
class LazyHistoryTest {
    private val records = InMemoryRecordStore()
    private val ciphers = mutableListOf<FakeHistoryCipher>()
    private var opens = 0
    private var failure: Throwable? = null
    private var clock = 0L
    private val gif =
        assertNotNull(
            GifReference.of("klipy", "cat", "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/cat-1.webp", 320, 240),
        )
    private val group = KeyBytes(ByteArray(16) { 1 })

    private val history =
        LazyHistory({
            opens++
            failure?.let { throw it }
            val cipher = FakeHistoryCipher().also { ciphers += it }
            EncryptedMessageStore(cipher, records)
        }, { clock })

    private fun message(n: Int) =
        ChatMessage(
            group,
            KeyBytes(ByteArray(32) { n.toByte() }),
            "bob",
            null,
            MessageBody.Gif(gif, null),
            null,
            100L + n,
        )

    private suspend fun kindOf(block: suspend () -> Unit): HistoryStoreException.Kind =
        assertFailsWith<HistoryStoreException> { block() }.kind

    @Test
    fun theStoreIsOpenedOnFirstUseAndOnlyOnce() =
        runTest {
            assertEquals(0, opens)
            assertTrue(history.insert(message(1)))
            assertEquals(listOf(message(1).reference), history.messages(group).map { it.reference })
            assertEquals(1, opens)
        }

    @Test
    fun twoFirstCallsAtOnceMakeOneStore() =
        runTest {
            List(5) { n -> async { history.insert(message(n + 1)) } }.awaitAll()
            assertEquals(1, opens)
            assertEquals(5, history.messages(group).size)
        }

    @Test
    fun aMissingOrBrokenKeyIsStickyUntilRetry() =
        runTest {
            failure = LocalStateException(LocalStateException.Problem.KEY_UNAVAILABLE)
            assertEquals(HistoryStoreException.Kind.KEY, kindOf { history.messages(group) })
            failure = null
            assertEquals(HistoryStoreException.Kind.KEY, kindOf { history.messages(group) }, "not asked again on every refresh")
            assertEquals(1, opens)
            history.retry()
            assertEquals(emptyList(), history.messages(group))
            assertEquals(2, opens)
            failure = LocalStateException(LocalStateException.Problem.DAMAGED)
            history.close()
        }

    @Test
    fun aDeadCipherIsStickyToo() =
        runTest {
            failure = MlsException(MlsErrorCode.Poisoned)
            assertEquals(HistoryStoreException.Kind.CIPHER, kindOf { history.messages(group) })
            failure = null
            assertEquals(HistoryStoreException.Kind.CIPHER, kindOf { history.messages(group) })
            assertEquals(1, opens)
        }

    @Test
    fun aStorageFailureCoolsDownAndIsThenTriedAgain() =
        runTest {
            failure = RecordStoreException()
            assertEquals(HistoryStoreException.Kind.STORAGE, kindOf { history.messages(group) })
            failure = null
            clock += 4_999
            assertEquals(
                HistoryStoreException.Kind.STORAGE,
                kindOf { history.messages(group) },
                "still cooling down: the disk is not touched",
            )
            assertEquals(1, opens)
            clock += 1
            assertEquals(emptyList(), history.messages(group))
            assertEquals(2, opens)
        }

    @Test
    fun aRetryEndsTheCoolDown() =
        runTest {
            failure = LocalStateException(LocalStateException.Problem.STORAGE)
            assertEquals(HistoryStoreException.Kind.STORAGE, kindOf { history.messages(group) })
            failure = null
            history.retry()
            assertEquals(emptyList(), history.messages(group))
        }

    @Test
    fun anUnregisteredIdentityIsNotRetried() =
        runTest {
            failure = IllegalStateException("not registered")
            assertEquals(HistoryStoreException.Kind.CIPHER, kindOf { history.messages(group) })
            failure = null
            assertEquals(HistoryStoreException.Kind.CIPHER, kindOf { history.messages(group) })
        }

    @Test
    fun closeIsForGoodAndTheKeyInMemoryGoes() =
        runTest {
            history.insert(message(1))
            val cipher = ciphers.single()
            history.close()
            assertTrue(cipher.closed, "the cipher, and with it the history key, is closed")
            assertEquals(HistoryStoreException.Kind.CLOSED, kindOf { history.messages(group) })
            assertEquals(HistoryStoreException.Kind.CLOSED, kindOf { history.insert(message(2)) })
            assertEquals(HistoryStoreException.Kind.CLOSED, kindOf { history.markSeen(group, message(1).reference) })
            assertEquals(1, opens, "never opened again")
            history.close()
            assertEquals(1, records.snapshot().values.sumOf { it.size }, "closing deletes nothing; the wipe does")
        }

    @Test
    fun aHistoryThatWasNeverOpenedCanBeClosed() =
        runTest {
            history.close()
            assertEquals(HistoryStoreException.Kind.CLOSED, kindOf { history.messages(group) })
            assertEquals(0, opens)
        }

    @Test
    fun closeWaitsForAnOpenInProgressAndNothingRunsAfterIt() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val slow =
                LazyHistory({
                    release.await()
                    EncryptedMessageStore(FakeHistoryCipher().also { ciphers += it }, records)
                }, { clock })
            val opening = async { slow.messages(group) }
            runCurrent()
            val closing = async { slow.close() }
            runCurrent()
            assertFalse(closing.isCompleted, "close waits for the open that is running")
            val late = async { runCatching { slow.messages(group) } }
            release.complete(Unit)
            opening.await()
            closing.await()
            assertEquals(HistoryStoreException.Kind.CLOSED, (late.await().exceptionOrNull() as HistoryStoreException).kind)
            assertTrue(ciphers.single().closed, "the store that was just opened is closed, key and cache with it")
        }

    @Test
    fun resetClosesTheOpenStoreRunsTheDeletionAndOpensFreshOnTheNextCall() =
        runTest {
            history.insert(message(1))
            val first = ciphers.single()
            var deleted = 0
            history.reset {
                assertTrue(first.closed, "the old key is gone before anything is deleted")
                records.deleteAll()
                deleted++
            }
            assertEquals(1, deleted)
            assertEquals(emptyList(), history.messages(group))
            assertEquals(2, opens)
            assertEquals(2, ciphers.size)
        }

    @Test
    fun resetClearsAStickyFailure() =
        runTest {
            failure = LocalStateException(LocalStateException.Problem.KEY_UNAVAILABLE)
            assertEquals(HistoryStoreException.Kind.KEY, kindOf { history.messages(group) })
            failure = null
            history.reset { }
            assertEquals(emptyList(), history.messages(group))
        }

    @Test
    fun aFailedDeletionIsReportedAndTheHistoryStaysUsableOnlyByOpeningAgain() =
        runTest {
            history.insert(message(1))
            assertFailsWith<IllegalStateException> { history.reset { error("disk") } }
            // Nothing is open any more; the next call opens again over whatever is left.
            assertEquals(1, history.messages(group).size)
            assertEquals(2, opens)
        }

    @Test
    fun aClosedHistoryCannotBeReset() =
        runTest {
            history.close()
            var ran = false
            assertEquals(HistoryStoreException.Kind.CLOSED, kindOf { history.reset { ran = true } })
            assertFalse(ran)
        }

    @Test
    fun aCloseThatFailsLeavesTheStoreForTheRetryAndTheKeyIsNeverLeftBehind() =
        runTest {
            history.insert(message(1))
            val cipher = ciphers.single()
            cipher.failCloseOnce = true
            assertFailsWith<IllegalStateException> { history.close() }
            assertFalse(cipher.closed)
            history.close()
            assertTrue(cipher.closed, "the retry closes the same cipher")
            assertEquals(HistoryStoreException.Kind.CLOSED, kindOf { history.messages(group) })
        }

    @Test
    fun anUnknownThrowableFromTheOpenerIsStickyCipherUntilRetry() =
        runTest {
            failure = Error("module could not be loaded")
            assertEquals(HistoryStoreException.Kind.CIPHER, kindOf { history.messages(group) })
            failure = null
            clock += 1_000_000
            assertEquals(HistoryStoreException.Kind.CIPHER, kindOf { history.messages(group) }, "not retried by time")
            history.retry()
            assertEquals(emptyList(), history.messages(group))
        }

    @Test
    fun usablePassesCancellationOn() =
        runTest {
            failure = kotlin.coroutines.cancellation.CancellationException("screen left")
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { history.usable() }
            failure = null
            assertTrue(history.usable())
        }
}
