package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlin.coroutines.cancellation.CancellationException

/**
 * The sync state of the groups of one restored client, and the one way to store it: the state goes into the snapshot
 * with the MLS state ([MlsClient.setAppState], then [MlsClient.exportState]) and both are saved in one piece, so a cursor
 * never moves without the MLS state that goes with it (persist before ack, ADR-0016). A failure of any step halts the
 * run: the live client has already moved on, so the caller drops it and restores from storage.
 */
internal class SyncStore(
    private val mls: MlsClient,
    private val saver: SnapshotSaver,
) {
    var state: SyncState = load()
        private set

    private fun load(): SyncState =
        try {
            SyncStateCodec.decode(mls.appState())
        } catch (e: SyncStateException) {
            throw IllegalStateException("damaged sync state", e)
        }

    fun recordOf(id: KeyBytes): GroupRecord = state.groups.getValue(id)

    fun find(id: KeyBytes): GroupRecord? = state.groups[id]

    fun put(
        id: KeyBytes,
        record: GroupRecord,
    ) {
        state = state.with(id, record)
    }

    fun drop(id: KeyBytes) {
        state = SyncState(state.groups - id)
    }

    suspend fun advance(
        id: KeyBytes,
        seq: Long,
    ) {
        val record = recordOf(id)
        put(id, record.copy(cursor = seq, failures = record.failures - seq, ownSeqs = record.ownSeqs.filter { it.seq > seq }))
        persist()
    }

    /** Stops the group in [status] (stored) and halts. A commit can't be skipped, so this is final until a rejoin. */
    suspend fun freeze(
        id: KeyBytes,
        status: GroupStatus,
    ): Nothing {
        put(id, recordOf(id).copy(status = status))
        persist()
        halt(SyncOutcome.Stopped(status))
    }

    suspend fun persist() {
        val snapshot = snapshotOrHalt()
        try {
            saver.save(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            halt(SyncOutcome.StorageFailed)
        } finally {
            snapshot.fill(0)
        }
    }

    private fun snapshotOrHalt(): ByteArray =
        try {
            mls.setAppState(SyncStateCodec.encode(state))
            mls.exportState()
        } catch (e: MlsException) {
            halt(failureOf(e.code))
        } catch (ignored: SyncStateException) {
            halt(SyncOutcome.StorageFailed)
        }

    private fun failureOf(code: MlsErrorCode): SyncOutcome =
        when (code) {
            MlsErrorCode.LimitExceeded -> SyncOutcome.RekeyNeeded
            MlsErrorCode.Poisoned -> SyncOutcome.Poisoned(null)
            else -> SyncOutcome.StorageFailed
        }
}
