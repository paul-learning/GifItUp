package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.domain.contacts.KeyBytes

private const val MAX_ATTEMPTS = 3

/** Counts an attempt on [seq]; the third one skips the message for good (the cursor moves past it). */
internal suspend fun SyncStore.countFailure(
    id: KeyBytes,
    seq: Long,
) {
    val record = find(id) ?: halt(SyncOutcome.NotJoined)
    val attempts = (record.failures[seq] ?: 0) + 1
    if (attempts >= MAX_ATTEMPTS) {
        quarantine(id, seq)
        if (seq == record.cursor + 1) advance(id, seq) else persist()
    } else {
        val counted = (record.failures + (seq to attempts)).entries.toList().takeLast(SyncStateCodec.MAX_FAILURES)
        put(id, record.copy(failures = counted.associate { it.toPair() }))
        persist()
    }
}

/** Skips [seq] for good once the cursor reaches it. */
internal fun SyncStore.quarantine(
    id: KeyBytes,
    seq: Long,
) {
    val record = recordOf(id)
    put(
        id,
        record.copy(quarantined = (record.quarantined + seq).takeLast(SyncStateCodec.MAX_QUARANTINED), failures = record.failures - seq),
    )
}

/** The group is gone for us (the server no longer shows it, or a commit removed us): its MLS state is deleted. */
internal suspend fun SyncStore.markGone(
    mls: MlsClient,
    id: KeyBytes,
) {
    crate { mls.forgetGroup(id.bytes) }
    put(id, recordOf(id).copy(status = GroupStatus.REMOVED))
    persist()
}

/** Removes the group's record and whatever MLS state is left of it (a group already gone from the client is fine). */
internal suspend fun SyncStore.forget(
    mls: MlsClient,
    id: KeyBytes,
) {
    if (find(id) == null) halt(SyncOutcome.NotJoined)
    try {
        mls.forgetGroup(id.bytes)
    } catch (e: MlsException) {
        if (e.code != MlsErrorCode.UnknownGroup) crate { throw e }
    }
    drop(id)
    persist()
}

/**
 * The value of a Delivery Service answer about a group. A group the server no longer shows us (not a member, or
 * unknown: the same answer, ADR-0015) is "no longer available": the MLS state is deleted and the group stays
 * read-only. We can't tell removal from withholding.
 */
internal suspend fun <T> SyncStore.answerForGroup(
    mls: MlsClient,
    id: KeyBytes,
    result: ApiResult<T>,
): T {
    if (result is ApiResult.Failed && result.error == ApiError.NOT_FOUND) {
        markGone(mls, id)
        halt(SyncOutcome.Stopped(GroupStatus.REMOVED))
    }
    return answer(result)
}
