package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.contacts.KeyBytes

/**
 * Where a group stands (ADR-0016). Everything except [ACTIVE] stops sending and syncing for that group; old messages
 * stay readable. Only a rejoin (leave on the server, forget locally, be added again) leads back to [ACTIVE].
 * The codec stores the ordinal: new statuses go at the end.
 */
enum class GroupStatus {
    ACTIVE,

    /** The retained log no longer reaches back to our cursor, or messages are missing in the middle. */
    FROZEN_GAP,

    /** A commit broke a rule of the crate (identity continuity, proposal types, limits) or could not be applied. */
    FROZEN_COMMIT_REFUSED,

    /** A commit does not match its envelope (counts or epoch). The server may be lying; the warning names nobody. */
    FROZEN_ENVELOPE_MISMATCH,

    /** A commit adds a key for a contact that is not the pinned one (ADR-0014). */
    FROZEN_CONTACT_MISMATCH,

    /** The freshness check showed this client was restored from older state: read-only. */
    READ_ONLY_ROLLBACK,

    /** A commit removed this device; the MLS state is gone. */
    REMOVED,
}

/**
 * The sync state of one group. [cursor] is the highest seq applied; [failures] counts failed attempts per seq for
 * application messages; [quarantined] are seqs skipped for good; [unverified] are signature keys of members that
 * share a name with another leaf and are not pinned (shown as "new, unverified device", SEC-18).
 * [lastMsgId] and [prevMsgId] are the client message ids of the last two sends (freshness, ADR-0015).
 */
@Suppress("LongParameterList") // One property per stored field; the codec lists them in the same order.
class GroupRecord(
    val cursor: Long,
    val status: GroupStatus,
    val failures: Map<Long, Int>,
    val quarantined: List<Long>,
    val unverified: List<KeyBytes>,
    val lastMsgId: ByteArray?,
    val prevMsgId: ByteArray?,
) {
    fun copy(
        cursor: Long = this.cursor,
        status: GroupStatus = this.status,
        failures: Map<Long, Int> = this.failures,
        quarantined: List<Long> = this.quarantined,
        unverified: List<KeyBytes> = this.unverified,
    ) = GroupRecord(cursor, status, failures, quarantined, unverified, lastMsgId, prevMsgId)

    companion object {
        fun joinedAt(
            cursor: Long,
            unverified: List<KeyBytes>,
        ) = GroupRecord(cursor, GroupStatus.ACTIVE, emptyMap(), emptyList(), unverified, null, null)
    }
}

/** All groups of this device, keyed by MLS group id. It travels as the opaque app state inside the snapshot. */
class SyncState(
    val groups: Map<KeyBytes, GroupRecord>,
) {
    fun with(
        groupId: KeyBytes,
        record: GroupRecord,
    ) = SyncState(groups + (groupId to record))

    companion object {
        val EMPTY = SyncState(emptyMap())
    }
}

/** The app state is damaged or from an unknown version; nothing is guessed (fail closed). */
class SyncStateException : Exception()
