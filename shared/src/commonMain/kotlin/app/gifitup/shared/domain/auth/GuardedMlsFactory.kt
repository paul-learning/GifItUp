package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.storage.RestoreCounter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one way the use cases restore a client (#16 SEC-4), so no restore path can loop. On the web a malformed
 * decrypted snapshot traps in Rust, the page reloads, and the app would restore the same snapshot again, forever
 * (ADR-0007). So every restore is counted before it starts, outside the snapshot, and the count is cleared as soon as it
 * comes back to Kotlin, with a client or with an [MlsException] (a clean failure, such as a snapshot that doesn't
 * decrypt, is reported as such and isn't a loop; #108 CR-1). Only a restore that never comes back (a trap) stays counted.
 * After [LIMIT] of those, [restore] refuses with [RestoreBlockedException] until the user chooses [tryAgain] or wipes
 * the device. Restores run one at a time, so overlapping ones can't undercount (#108 CR-3).
 *
 * Only the restore itself counts. The caller unwraps the key first (`SnapshotKeyStore.load`), and a failed unwrap is a
 * `KEY_UNAVAILABLE` error, which never counts (#16 SEC-4): it can be transient, and counting it would push the user
 * towards an irreversible reset.
 */
class GuardedMlsFactory(
    private val mls: MlsClientFactory,
    private val counter: RestoreCounter,
) {
    fun create(
        username: String,
        snapshotKey: ByteArray,
    ): MlsClient = mls.create(username, snapshotKey)

    /** Zeroes [snapshotKey] like [MlsClientFactory.restore] does, also when it refuses. */
    @Suppress("TooGenericExceptionCaught") // Any failure before the restore zeroes the key, then is rethrown unchanged.
    suspend fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient =
        single.withLock {
            try {
                val failures = counter.failures()
                if (failures >= LIMIT) throw RestoreBlockedException()
                counter.set(failures + 1)
            } catch (e: Throwable) {
                // Blocked, or the store failed before the restore: the key never reaches the client (#108 SEC-13).
                snapshotKey.fill(0)
                throw e
            }
            val client =
                try {
                    mls.restore(state, snapshotKey)
                } catch (e: MlsException) {
                    counter.set(0)
                    throw e
                }
            // A failed reset leaves the count up; the client must not leak with it.
            runCatching { counter.set(0) }.onFailure { client.close() }.getOrThrow()
            client
        }

    /** The user's "try again" after a block: the next restore gets [LIMIT] more attempts. */
    suspend fun tryAgain() = single.withLock { counter.set(0) }

    private val single = Mutex()

    internal companion object {
        /** Three attempts: one bad start can be bad luck, three in a row aren't. */
        const val LIMIT = 3
    }
}

/** [GuardedMlsFactory] refused a restore: the last [GuardedMlsFactory.LIMIT] never returned. */
class RestoreBlockedException : Exception() {
    override fun toString(): String = "RestoreBlockedException"
}
