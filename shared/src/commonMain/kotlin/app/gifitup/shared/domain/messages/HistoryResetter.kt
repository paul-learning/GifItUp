package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.domain.auth.DeviceStorage
import kotlin.coroutines.cancellation.CancellationException

/** How a "reset history" ended. */
enum class ResetResult {
    /** The stored history and its key are gone; the next use opens a new, empty history. */
    DONE,

    /** Not possible here: no registered identity of this origin, no resetter, or the history was closed (a wipe). */
    UNAVAILABLE,

    /** The deletion failed part-way; nothing was lost beyond what was deleted, and a retry can finish it. */
    FAILED,
}

/** Does the work of "reset history" for a [LazyHistory]; the app's is [DeviceHistoryResetter]. */
interface HistoryResetter {
    suspend fun reset(history: LazyHistory): ResetResult

    /**
     * Whether a reset can help at all: a registered identity whose snapshot key still opens. If the snapshot key fails too,
     * the wrapping key is lost or unavailable and the whole identity is affected: that is the login or wipe path, and a
     * reset would only delete readable history while the new key fails the same way (a transient Keystore fault would
     * become a permanent deletion).
     */
    suspend fun available(): Boolean
}

/**
 * "Reset history" (ADR-0017): the history of this device is deleted, the account is not touched.
 *
 * - Only for a registered identity of the configured [origin].
 * - Under the **device lock**, so no chat call (a sync, a send, its reconcile) runs while the records go, and a running one
 *   finishes first: this is what keeps a reset from racing a send in flight. Lock order: device lock, then the history's
 *   gate; the deletion runs under the gate and takes neither again.
 * - [LazyHistory.resetWith] closes the open store first (its cache and key in memory go), then the records are deleted,
 *   then the wrapped key entry, in that order (a crash in between leaves a key without records, harmless: the next open
 *   uses it, or the reset is offered again; never a key that opens leftover records). The next use makes a new key over
 *   the empty store.
 * - What the engine kept is untouched on purpose: a pending message and the last accepted send stay in the snapshot, and
 *   settling them in a history that no longer has their records is a no-op (tested).
 */
class DeviceHistoryResetter(
    private val storage: DeviceStorage,
    private val origin: String,
) : HistoryResetter {
    override suspend fun available(): Boolean =
        try {
            registered() && probeSnapshotKey()
        } catch (e: CancellationException) {
            throw e
        } catch (_: LocalStateException) {
            false
        }

    @Suppress("TooGenericExceptionCaught") // Anything the reset throws is a failed reset; the history stays as it was.
    override suspend fun reset(history: LazyHistory): ResetResult =
        try {
            storage.lock.holding {
                when {
                    !registered() || !probeSnapshotKey() -> ResetResult.UNAVAILABLE
                    history.resetWith(requireBrokenKey = true) { storage.clearHistoryLeftovers(origin) } -> ResetResult.DONE
                    else -> ResetResult.UNAVAILABLE
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HistoryStoreException) {
            if (e.kind == HistoryStoreException.Kind.CLOSED) ResetResult.UNAVAILABLE else ResetResult.FAILED
        } catch (_: Throwable) {
            ResetResult.FAILED
        }

    private suspend fun registered(): Boolean {
        val identity = storage.identities.load()
        return identity?.deviceId != null && identity.origin == origin
    }

    /** The snapshot key opens (the wrapping key works); the key itself is zeroed at once. */
    private suspend fun probeSnapshotKey(): Boolean {
        val key = storage.snapshotKeys(origin).load() ?: return false
        key.fill(0)
        return true
    }
}
