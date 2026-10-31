package app.gifitup.mls.groups

import app.gifitup.mls.UniffiHistoryCipherFactory
import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.groups.GroupChat
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.SendObserver
import app.gifitup.shared.domain.groups.SyncOutcome
import app.gifitup.shared.domain.groups.WelcomeOutcome
import app.gifitup.shared.domain.messages.DeliveryState
import app.gifitup.shared.domain.messages.EncryptedMessageStore
import app.gifitup.shared.domain.messages.SendGif
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `Session` has an internal constructor (only a login makes one); the fake chat here never reads it, so this test builds one. */
private fun testSession(): Session {
    val endpoint = checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test"))
    val constructor = Session::class.java.declaredConstructors.single { it.parameterCount == 4 }
    return constructor.newInstance("d", "t", 1L, endpoint) as Session
}

/**
 * The send side over the real MLS client and the real history (ADR-0017, #177 PR 2d-2): the SENDING record comes first,
 * a state follows the answer of its own reference, and the crash windows between seal, store, persist, post and answer
 * never leave a wrong state.
 */
class GroupSendHistoryTest {
    private val ds = InMemoryDelivery()
    private val records = InMemoryRecordStore()
    private val gif =
        assertNotNull(
            GifReference.of("klipy", "cat", "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/cat-1.webp", 320, 240),
        )
    private val session = testSession()
    private val sink = IncomingSink { }

    private fun newStore() =
        EncryptedMessageStore(UniffiHistoryCipherFactory.create(ByteArray(32) { 5 }, "https://chat.example.test"), records)

    /** The engine of a party as the [GroupChat] the use case sends through. */
    private class Chat(
        private val party: Party,
    ) : GroupChat {
        override suspend fun send(
            session: Session,
            groupId: ByteArray,
            text: ByteArray,
            sink: IncomingSink,
            observer: SendObserver,
        ) = AuthResult.Ok(party.engine.send(groupId, text, observer))

        override suspend fun resume(
            session: Session,
            groupId: ByteArray,
            sink: IncomingSink,
            observer: SendObserver,
        ) = AuthResult.Ok(party.engine.resume(groupId, observer))
    }

    /** A third party creates the group; Alice and Bob join through their engines. */
    private suspend fun pair(): Triple<Party, ByteArray, Party> {
        val alice = Party("alice", ds)
        val bob = Party("bob", ds)
        val groupId = Party("carol", ds).createGroupWith(alice, bob)
        listOf(alice, bob).forEach { party ->
            party.engine
                .processWelcomes()
                .results
                .forEach { assertIs<WelcomeOutcome.Joined>(it) }
        }
        return Triple(alice, groupId, bob)
    }

    private fun sender(
        alice: Party,
        store: EncryptedMessageStore,
    ) = SendGif(Chat(alice), store) { 1_000L }

    private suspend fun states(
        store: EncryptedMessageStore,
        group: ByteArray,
    ) = store.messages(KeyBytes(group)).map { it.state }

    @Test
    fun aHistoryThatCannotStoreTheMessageSendsNothing() =
        runTest {
            val (alice, group, _) = pair()
            val store = newStore()
            var posted = false
            ds.onPost = { device, _ -> if (device == alice.deviceId) posted = true }
            records.failNextWrite = true
            val result = sender(alice, store)(session, group, gif, "hi", sink)
            assertEquals(SyncOutcome.StorageFailed, assertIs<AuthResult.Ok<SyncOutcome>>(result).value)
            assertEquals(false, posted)
            assertEquals(emptyList(), states(store, group))
            assertNull(alice.engine.recordOf(group)?.pending)
            alice.restart()
            assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(sender(alice, store)(session, group, gif, "again", sink)).value)
            assertEquals(listOf(DeliveryState.SENT), states(store, group))
        }

    @Test
    fun aMessageStoredButNeverPersistedOrPostedIsFailedAndTheNextSendWorks() =
        runTest {
            val (alice, group, _) = pair()
            val store = newStore()
            alice.failSave = true
            val failed = sender(alice, store)(session, group, gif, "lost", sink)
            assertEquals(SyncOutcome.StorageFailed, assertIs<AuthResult.Ok<SyncOutcome>>(failed).value)
            assertEquals(listOf(DeliveryState.SENDING), states(store, group), "stored first, nothing else happened")
            alice.failSave = false
            alice.restart()
            val send = sender(alice, newStore())
            // Resume with nothing pending: the record that is neither pending nor the last accepted one is FAILED.
            send.resume(session, group, sink)
            val afterResume = newStore()
            assertEquals(listOf(DeliveryState.FAILED), states(afterResume, group))
            val sent = assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(send(session, group, gif, "next", sink)).value)
            assertEquals(listOf(DeliveryState.FAILED, DeliveryState.SENT), states(newStore(), group))
            assertEquals(sent.seq, newStore().messages(KeyBytes(group)).last().seq)
        }

    @Test
    fun aDeliveredMessageWhoseSentStateWasNotWrittenBecomesSentNotFailed() =
        runTest {
            val (alice, group, _) = pair()
            val store = newStore()
            // The server accepts the post; the next history write (SENT) fails, as if the app died right there.
            ds.onPost = { device, _ -> if (device == alice.deviceId) records.failNextWrite = true }
            val sent =
                assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(sender(alice, store)(session, group, gif, "x", sink)).value)
            assertEquals(listOf(DeliveryState.SENDING), states(store, group), "the answer could not be written")
            alice.restart()
            val restored = newStore()
            sender(alice, restored).resume(session, group, sink)
            val message = restored.messages(KeyBytes(group)).single()
            assertEquals(DeliveryState.SENT, message.state)
            assertEquals(sent.seq, message.seq)
        }

    @Test
    fun anUnansweredMessageStaysSendingAndResumeSettlesExactlyIt() =
        runTest {
            val (alice, group, _) = pair()
            val store = newStore()
            val send = sender(alice, store)
            ds.failing = setOf("post")
            assertIs<SyncOutcome.InFlight>(assertIs<AuthResult.Ok<SyncOutcome>>(send(session, group, gif, "one", sink)).value)
            assertEquals(listOf(DeliveryState.SENDING), states(store, group))
            alice.restart()
            ds.failing = emptySet()
            val restored = newStore()
            val resumed = sender(alice, restored).resume(session, group, sink)
            assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(resumed).value)
            assertEquals(listOf(DeliveryState.SENT), states(restored, group))
            assertTrue(restored.messages(KeyBytes(group)).single().seq != null)
        }

    @Test
    fun aDeliveredMessageIsNeverFailedBecauseALaterSendReplacedTheLastAcceptedOne() =
        runTest {
            val (alice, group, _) = pair()
            val store = newStore()
            val send = sender(alice, store)
            // N is accepted; its SENT write fails.
            ds.onPost = { device, _ -> if (device == alice.deviceId) records.failNextWrite = true }
            assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(send(session, group, gif, "n", sink)).value)
            ds.onPost = null
            assertEquals(listOf(DeliveryState.SENDING), states(store, group))
            // The history cannot write when N+1 starts: nothing new is sealed or posted.
            records.failNextWrite = true
            val blocked = send(session, group, gif, "n+1", sink)
            assertEquals(SyncOutcome.StorageFailed, assertIs<AuthResult.Ok<SyncOutcome>>(blocked).value)
            assertEquals(1, store.messages(KeyBytes(group)).size, "N+1 was not sealed")
            // The history works again: N is settled first, N+1 follows; both are SENT.
            alice.restart()
            val restored = newStore()
            assertIs<SyncOutcome.Sent>(
                assertIs<AuthResult.Ok<SyncOutcome>>(sender(alice, restored)(session, group, gif, "n+1", sink)).value,
            )
            assertEquals(listOf(DeliveryState.SENT, DeliveryState.SENT), states(restored, group))
        }

    @Test
    fun aResetWithAPendingSendAndALastAcceptedSendInTheSnapshotDoesNotBreakSendOrResume() =
        runTest {
            val (alice, group, _) = pair()
            val store = newStore()
            val send = sender(alice, store)
            // One message accepted (lastSent in the snapshot), one unanswered (pending in the snapshot).
            assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(send(session, group, gif, "first", sink)).value)
            ds.failing = setOf("post")
            assertIs<SyncOutcome.InFlight>(assertIs<AuthResult.Ok<SyncOutcome>>(send(session, group, gif, "second", sink)).value)
            // "Reset history": the records are gone; the engine's snapshot is untouched.
            store.close()
            records.deleteAll()
            ds.failing = emptySet()
            val fresh = newStore()
            val resumed = sender(alice, fresh).resume(session, group, sink)
            assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(resumed).value)
            assertEquals(emptyList(), states(fresh, group), "no record to settle, and nothing broke")
            val next = sender(alice, fresh)(session, group, gif, "third", sink)
            assertIs<SyncOutcome.Sent>(assertIs<AuthResult.Ok<SyncOutcome>>(next).value)
            assertEquals(listOf(DeliveryState.SENT), states(fresh, group))
        }
}
