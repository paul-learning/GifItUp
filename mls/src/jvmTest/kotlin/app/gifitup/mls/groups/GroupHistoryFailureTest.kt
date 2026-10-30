package app.gifitup.mls.groups

import app.gifitup.mls.UniffiHistoryCipherFactory
import app.gifitup.shared.data.content.ContentCodec
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import app.gifitup.shared.domain.messages.EncryptedMessageStore
import app.gifitup.shared.domain.messages.MessageBody
import app.gifitup.shared.domain.messages.MessageIntake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A history that can fail, under the real MLS client and the real history cipher (ADR-0017, #177 PR 2d): a message the
 * history could not store is never lost, because the engine that staged it is dead and nothing is exported from it.
 */
class GroupHistoryFailureTest {
    private val ds = InMemoryDelivery()
    private val records = InMemoryRecordStore()
    private val gif =
        assertNotNull(
            GifReference.of("klipy", "cat", "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/cat-1.webp", 320, 240),
        )

    private fun newStore() =
        EncryptedMessageStore(UniffiHistoryCipherFactory.create(ByteArray(32) { 5 }, "https://chat.example.test"), records)

    private fun content(caption: String) = assertNotNull(ContentCodec.encodeGif(gif, caption))

    /** Alice and Bob share two groups; Bob's sink is the real history. */
    private suspend fun twoGroups(): Triple<Party, Party, Pair<ByteArray, ByteArray>> {
        val alice = Party("alice", ds)
        val bob = Party("bob", ds)
        val first = alice.createGroupWith(bob)
        val second = alice.createGroupWith(bob)
        bob.engine
            .processWelcomes()
            .results
            .forEach { assertIs<WelcomeOutcome.Joined>(it) }
        return Triple(alice, bob, first to second)
    }

    private fun Party.useHistory(store: EncryptedMessageStore) {
        sink = MessageIntake(store) { 1_000L }
    }

    private suspend fun EncryptedMessageStore.captions(group: ByteArray) =
        messages(KeyBytes(group)).map { assertIs<MessageBody.Gif>(it.body).caption }

    @Test
    fun aMessageTheHistoryCouldNotStoreIsNotLostEvenIfAnotherGroupSyncsOnTheSameEngine() =
        runTest {
            val (alice, bob, groups) = twoGroups()
            val (first, second) = groups
            bob.useHistory(newStore())
            alice.sayBytes(first, content("one"))
            alice.sayBytes(second, content("two"))
            val savesBefore = bob.saves
            records.failNextWrite = true
            assertEquals(SyncOutcome.StorageFailed, bob.engine.sync(first))
            // The engine staged "one" and is now ahead of the stored snapshot: it must not export for another group.
            assertEquals(SyncOutcome.StorageFailed, bob.engine.sync(second))
            assertEquals(SyncOutcome.StorageFailed, bob.engine.send(second, content("mine")))
            assertEquals(savesBefore, bob.saves, "nothing was saved from the engine that was ahead")
            // A restart: the client comes from the last snapshot, a new store reads the same records.
            bob.restart()
            val store = newStore()
            bob.useHistory(store)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(first))
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(second))
            assertEquals(listOf("one"), store.captions(first))
            assertEquals(listOf("two"), store.captions(second))
            // And once more, after another restart: still stored once.
            bob.restart()
            bob.useHistory(newStore())
            assertEquals(SyncOutcome.CaughtUp(0), bob.engine.sync(first))
            assertEquals(listOf("one"), newStore().captions(first))
        }

    @Test
    fun aHistoryFailureIsNotCountedAndNeverQuarantinesTheMessage() =
        runTest {
            val (alice, bob, groups) = twoGroups()
            val (first, _) = groups
            bob.useHistory(newStore())
            alice.sayBytes(first, content("one"))
            val cursor = bob.engine.recordOf(first)?.cursor
            repeat(4) {
                bob.restart()
                bob.useHistory(newStore())
                records.failNextWrite = true
                assertEquals(SyncOutcome.StorageFailed, bob.engine.sync(first))
                assertEquals(cursor, bob.engine.recordOf(first)?.cursor)
                assertTrue(
                    bob.engine
                        .recordOf(first)
                        ?.failures
                        .orEmpty()
                        .isEmpty(),
                    "never counted as a failed message",
                )
                assertTrue(
                    bob.engine
                        .recordOf(first)
                        ?.quarantined
                        .orEmpty()
                        .isEmpty(),
                    "never skipped",
                )
            }
            bob.restart()
            val store = newStore()
            bob.useHistory(store)
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(first))
            assertEquals(listOf("one"), store.captions(first))
        }

    @Test
    fun aCancelledDeliveryKillsTheEngineToo() =
        runTest {
            val (alice, bob, groups) = twoGroups()
            val (first, second) = groups
            alice.sayBytes(first, content("one"))
            alice.sayBytes(second, content("two"))
            bob.sink = IncomingSink { throw CancellationException("screen left") }
            val savesBefore = bob.saves
            assertFailsWith<CancellationException> { bob.engine.sync(first) }
            assertEquals(SyncOutcome.StorageFailed, bob.engine.sync(second))
            assertEquals(savesBefore, bob.saves)
            bob.sink = IncomingSink { bob.received += it }
            bob.restart()
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(first))
            assertEquals(1, bob.received.size)
        }

    @Test
    fun aSnapshotThatCouldNotBeSavedAlsoKillsTheEngine() =
        runTest {
            val (alice, bob, groups) = twoGroups()
            val (first, second) = groups
            alice.sayBytes(first, content("one"))
            alice.sayBytes(second, content("two"))
            bob.failSave = true
            assertEquals(SyncOutcome.StorageFailed, bob.engine.sync(first))
            bob.failSave = false
            val savesBefore = bob.saves
            assertEquals(SyncOutcome.StorageFailed, bob.engine.sync(second))
            assertEquals(savesBefore, bob.saves)
            bob.restart()
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(first))
            assertEquals(SyncOutcome.CaughtUp(1), bob.engine.sync(second))
        }

    @Test
    fun theLongestMessageIsStoredAndDoesNotStallTheGroup() =
        runTest {
            val (alice, bob, groups) = twoGroups()
            val (first, _) = groups
            val store = newStore()
            bob.useHistory(store)
            val longest = "😀".repeat(MAX_CAPTION)
            alice.sayBytes(first, content(longest))
            alice.sayBytes(first, content("after"))
            assertEquals(SyncOutcome.CaughtUp(2), bob.engine.sync(first))
            assertEquals(listOf(longest, "after"), store.captions(first))
        }

    private companion object {
        const val MAX_CAPTION = 120
    }
}
