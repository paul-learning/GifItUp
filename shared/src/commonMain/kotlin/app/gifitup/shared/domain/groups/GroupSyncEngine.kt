package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
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
class GroupSyncEngine(
    private val mls: MlsClient,
    delivery: DeliveryPort,
    pinsSource: ContactPinsSource,
    saver: SnapshotSaver,
    sink: IncomingSink,
) {
    private val mutex = Mutex()
    private val store = SyncStore(mls, saver)
    private val messages = MessageReceiver(mls, delivery, pinsSource, sink, store)
    private val welcomes = WelcomeReceiver(mls, delivery, pinsSource, store)

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
