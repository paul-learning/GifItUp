package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.crypto.MlsClient

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
    lookup { mls.forgetGroup(id.bytes) }
    put(id, recordOf(id).copy(status = GroupStatus.REMOVED))
    persist()
}
