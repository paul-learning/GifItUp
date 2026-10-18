package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.storage.RestoreCounter

/**
 * The one way the use cases restore a client (#16 SEC-4), so no restore path can loop. On the web a malformed
 * decrypted snapshot traps in Rust, the page reloads, and the app would restore the same snapshot again, forever
 * (ADR-0007). So every restore is counted before it starts, outside the snapshot, and the count is cleared as soon as it
 * returns. After [LIMIT] restores that never returned, [restore] refuses with [RestoreBlockedException] until the user
 * chooses [tryAgain] or wipes the device.
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
    suspend fun restore(
        state: ByteArray,
        snapshotKey: ByteArray,
    ): MlsClient {
        val failures = counter.failures()
        if (failures >= LIMIT) {
            snapshotKey.fill(0)
            throw RestoreBlockedException()
        }
        counter.set(failures + 1)
        val client = mls.restore(state, snapshotKey)
        counter.set(0)
        return client
    }

    /** The user's "try again" after a block: the next restore gets [LIMIT] more attempts. */
    suspend fun tryAgain() = counter.set(0)

    internal companion object {
        /** Three attempts: one bad start can be bad luck, three in a row aren't. */
        const val LIMIT = 3
    }
}

/** [GuardedMlsFactory] refused a restore: the last [GuardedMlsFactory.LIMIT] never returned. */
class RestoreBlockedException : Exception() {
    override fun toString(): String = "RestoreBlockedException"
}
