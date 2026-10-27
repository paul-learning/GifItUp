package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Receive side of the group lifecycle (ADR-0016): pulls the log of a group in strict seq order, applies it through the
 * [MlsClient] and keeps the cursor with the MLS state in one snapshot (persist before ack). One instance works on one
 * restored [MlsClient] and is used under the device lock; calls are serialised here too.
 *
 * Rules that matter:
 * - A message is applied only at `cursor + 1`; any jump is a gap and freezes the group, never a quarantine.
 * - Everything that needs the network or the contact book is loaded **before** a commit is staged; after staging the
 *   decision is local, so no snapshot is ever taken with a staged item.
 * - A commit can't be skipped: a refused one, an envelope mismatch or a contact mismatch freezes the group (and is
 *   persisted). An application message that fails is counted and skipped after three tries; a used-up generation (the
 *   same ciphertext delivered again) at once.
 * - The sender label of a log entry is the server's word and decides nothing. A message of ours that comes back fails
 *   to decrypt once and is then skipped; the send side (PR C2) will skip the seqs it
 *   knows it posted.
 * - A group the server no longer shows us is "no longer available": its MLS state is deleted, the group stays read-only.
 */
@Suppress("TooManyFunctions") // The engine's public API: one method per operation, each a one-line delegation.
class GroupSyncEngine(
    private val mls: MlsClient,
    delivery: DeliveryPort,
    pinsSource: ContactPinsSource,
    saver: SnapshotSaver,
    sink: IncomingSink,
    random: SecureRandomSource,
) {
    private val mutex = Mutex()
    private val store = SyncStore(mls, saver)
    private val messages = MessageReceiver(mls, delivery, pinsSource, sink, store)
    private val welcomes = WelcomeReceiver(mls, delivery, pinsSource, store)
    private val poster = Poster(mls, delivery, store)
    private val sender = SendSide(mls, store, poster, random)
    private val commits = CommitSide(mls, store, poster, messages, random)

    /** The groups this engine knows and where each stands. */
    fun statuses(): Map<KeyBytes, GroupStatus> = store.state.groups.mapValues { it.value.status }

    fun recordOf(groupId: ByteArray): GroupRecord? = store.find(KeyBytes(groupId))

    suspend fun sync(groupId: ByteArray): SyncOutcome = mutex.withLock { halting { messages.sync(KeyBytes(groupId)) } }

    suspend fun processWelcomes(): WelcomesReport =
        mutex.withLock {
            val results = mutableListOf<WelcomeOutcome>()
            val outcome = halting { welcomes.process(results) }
            WelcomesReport(results, outcome.takeIf { it !is SyncOutcome.CaughtUp })
        }

    /**
     * Seals [plaintext] (at most 896 bytes), stores it and posts it. [SyncOutcome.Sent] when the server accepted it;
     * [SyncOutcome.InFlight] when the answer is unknown (the message stays pending, call [resume]); a stale state
     * ([GroupStatus.READ_ONLY_ROLLBACK]) sends nothing.
     */
    suspend fun send(
        groupId: ByteArray,
        plaintext: ByteArray,
    ): SyncOutcome = mutex.withLock { halting { sender.send(KeyBytes(groupId), plaintext) } }

    /** Posts the pending message or commit of a group again, unchanged. */
    suspend fun resume(groupId: ByteArray): SyncOutcome =
        mutex.withLock {
            halting {
                val id = KeyBytes(groupId)
                if (store.find(id)?.pending?.kind == MessageKind.COMMIT) commits.resume(id) else sender.resume(id)
            }
        }

    /**
     * Creates a group with [members] (1 to 10 claimed KeyPackages with their device ids) and posts its first commit.
     * The group exists on this device from the moment the commit is stored; if the outcome is [SyncOutcome.InFlight],
     * call [resume] with the returned group id.
     */
    suspend fun createGroup(members: List<NewMember>): GroupCreation =
        mutex.withLock {
            var created: ByteArray? = null
            val outcome = halting { commits.create(members).also { created = it.groupId }.outcome }
            GroupCreation(created, outcome)
        }

    /** Adds 1 to 10 members with one commit; reads and applies the log first, and retries after an epoch conflict. */
    suspend fun addMembers(
        groupId: ByteArray,
        members: List<NewMember>,
    ): SyncOutcome = mutex.withLock { halting { commits.add(KeyBytes(groupId), members) } }

    /** Removes 1 to 10 members (by device id and signature key) with one commit; a ghost leaf can be removed too. */
    suspend fun removeMembers(
        groupId: ByteArray,
        targets: List<RemoveTarget>,
    ): SyncOutcome = mutex.withLock { halting { commits.remove(KeyBytes(groupId), targets) } }

    /**
     * Counts a failed attempt on [seq] of a group; used by the caller after a `Poisoned` outcome, on a freshly restored
     * client (never on the dead one). Three failed attempts quarantine the message.
     */
    suspend fun recordFailure(
        groupId: ByteArray,
        seq: Long,
    ): SyncOutcome =
        mutex.withLock {
            halting {
                val id = KeyBytes(groupId)
                // Only the message the cursor stands in front of can have been the one that killed the client.
                if (store.find(id)?.cursor == seq - 1) store.countFailure(id, seq)
                SyncOutcome.Retry(seq)
            }
        }

    /**
     * Deletes everything of a group on this device: its MLS state (if any is left) and its sync record. The first step
     * of a rejoin, the way to free one of the 50 group slots, and the local half of leaving a group (the server half,
     * `DeliveryPort.leave`, comes with the send side).
     */
    suspend fun forget(groupId: ByteArray): SyncOutcome =
        mutex.withLock {
            halting {
                store.forget(mls, KeyBytes(groupId))
                SyncOutcome.CaughtUp(0)
            }
        }

    private suspend fun halting(block: suspend () -> SyncOutcome): SyncOutcome =
        try {
            block()
        } catch (stop: Halt) {
            stop.outcome
        }
}
