package app.gifitup.shared.domain.groups

import app.gifitup.shared.domain.contacts.KeyBytes

/**
 * The binary form of [SyncState] (ADR-0016), version 1: `version ‖ group count (u16)` and per group `id (u8 length,
 * 16..64) ‖ cursor (i64) ‖ status (u8) ‖ last id (flag byte, then 16 bytes) ‖ failures (u8 count, then seq i64 ‖
 * attempts u8) ‖ quarantined (u16 count, then i64) ‖ unverified (u8 count, then 32 bytes) ‖ pending send (flag byte, then
 * id 16 ‖ kind u8 ‖ epoch flag + i64 ‖ added and removed device ids (u8 count, then u8 length + text) ‖ welcome
 * (flag, u32 length, bytes) ‖ payload (u32 length, bytes)) ‖ own seqs (u16 count, then i64)`. The whole state is at most
 * [MAX_BYTES].
 * Decoding is strict: unknown versions, counts over their caps, unknown statuses, duplicates and trailing bytes are
 * all [SyncStateException]. The result is at most about 40 KiB for 50 groups, far below the 256 KiB cap of the crate.
 */
internal object SyncStateCodec {
    const val VERSION = 1
    const val MAX_GROUPS = 50
    const val MAX_FAILURES = 8
    const val MAX_QUARANTINED = 100
    const val MAX_UNVERIFIED = 50
    const val MSG_ID_BYTES = 16
    const val MAX_OWN_SEQS = 100
    const val MAX_BYTES = 256 * 1024
    const val KEY_BYTES = 32
    private const val MIN_GROUP_ID = 16
    private const val MAX_GROUP_ID = 64
    private const val MAX_ATTEMPTS = 255

    fun encode(state: SyncState): ByteArray {
        if (state.groups.size > MAX_GROUPS) invalid()
        val out = ByteWriter()
        out.u8(VERSION)
        out.u16(state.groups.size)
        for ((id, record) in state.groups) {
            writeGroup(out, id, record)
        }
        if (out.size > MAX_BYTES) invalid()
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): SyncState {
        if (bytes.isEmpty()) return SyncState.EMPTY
        val reader = ByteReader(bytes)
        if (reader.u8() != VERSION) invalid()
        val count = reader.u16()
        if (count > MAX_GROUPS) invalid()
        val groups = LinkedHashMap<KeyBytes, GroupRecord>()
        repeat(count) {
            val id = KeyBytes(reader.bytes(reader.u8().also { n -> if (n !in MIN_GROUP_ID..MAX_GROUP_ID) invalid() }))
            if (groups.put(id, readGroup(reader)) != null) invalid()
        }
        if (!reader.done()) invalid()
        return SyncState(groups)
    }

    private fun writeGroup(
        out: ByteWriter,
        id: KeyBytes,
        record: GroupRecord,
    ) {
        if (id.bytes.size !in MIN_GROUP_ID..MAX_GROUP_ID) invalid()
        out.u8(id.bytes.size)
        out.raw(id.bytes)
        out.i64(record.cursor)
        out.u8(record.status.ordinal)
        out.optional(record.lastMsgId, MSG_ID_BYTES)
        if (record.failures.size > MAX_FAILURES || record.quarantined.size > MAX_QUARANTINED || record.unverified.size > MAX_UNVERIFIED) {
            invalid()
        }
        out.u8(record.failures.size)
        for ((seq, attempts) in record.failures) {
            out.i64(seq)
            out.u8(attempts.coerceIn(1, MAX_ATTEMPTS))
        }
        out.u16(record.quarantined.size)
        record.quarantined.forEach { out.i64(it) }
        out.u8(record.unverified.size)
        record.unverified.forEach {
            if (it.bytes.size != KEY_BYTES) invalid()
            out.raw(it.bytes)
        }
        PendingSendCodec.write(out, record.pending)
        if (record.ownSeqs.size > MAX_OWN_SEQS) invalid()
        out.u16(record.ownSeqs.size)
        record.ownSeqs.forEach { out.i64(it) }
    }

    /** No repeated failure, quarantined seq, unverified key or own seq. */
    private fun allDistinct(
        failures: Pair<Int, Int>,
        quarantined: List<Long>,
        unverified: List<KeyBytes>,
        ownSeqs: List<Long>,
    ) = failures.first == failures.second &&
        quarantined.toSet().size == quarantined.size &&
        unverified.toSet().size == unverified.size &&
        ownSeqs.toSet().size == ownSeqs.size

    private fun readGroup(reader: ByteReader): GroupRecord {
        val cursor = reader.i64()
        val status = GroupStatus.entries.getOrNull(reader.u8()) ?: invalid()
        if (cursor < 0) invalid()
        val last = reader.optional(MSG_ID_BYTES)
        val failureList = reader.counted(MAX_FAILURES, reader.u8()) { reader.i64() to reader.u8() }
        val quarantined = reader.counted(MAX_QUARANTINED, reader.u16()) { reader.i64() }
        val unverified = reader.counted(MAX_UNVERIFIED, reader.u8()) { KeyBytes(reader.bytes(KEY_BYTES)) }
        val pending = PendingSendCodec.read(reader)
        val ownSeqs = reader.counted(MAX_OWN_SEQS, reader.u16()) { reader.i64() }
        val failures = failureList.toMap()
        if (!allDistinct(failureList.size to failures.size, quarantined, unverified, ownSeqs)) invalid()
        return GroupRecord(cursor, status, failures, quarantined, unverified, last, pending, ownSeqs)
    }
}
