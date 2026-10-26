package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.contacts.KeyBytes

/**
 * The binary form of [SyncState] (ADR-0016), version 1: `version ‖ group count (u16)` and per group `id (u8 length,
 * 16..64) ‖ cursor (i64) ‖ status (u8) ‖ last id ‖ previous id (each a flag byte, then 16 bytes) ‖ failures (u8
 * count, then seq i64 ‖ attempts u8) ‖ quarantined (u16 count, then i64) ‖ unverified (u8 count, then 32 bytes)`.
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
    const val KEY_BYTES = 32
    private const val MIN_GROUP_ID = 16
    private const val MAX_GROUP_ID = 64
    private const val MAX_ATTEMPTS = 255

    private fun invalid(): Nothing = throw SyncStateException()

    fun encode(state: SyncState): ByteArray {
        if (state.groups.size > MAX_GROUPS) invalid()
        val out = Writer()
        out.u8(VERSION)
        out.u16(state.groups.size)
        for ((id, record) in state.groups) {
            writeGroup(out, id, record)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): SyncState {
        if (bytes.isEmpty()) return SyncState.EMPTY
        val reader = Reader(bytes)
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
        out: Writer,
        id: KeyBytes,
        record: GroupRecord,
    ) {
        if (id.bytes.size !in MIN_GROUP_ID..MAX_GROUP_ID) invalid()
        out.u8(id.bytes.size)
        out.raw(id.bytes)
        out.i64(record.cursor)
        out.u8(record.status.ordinal)
        out.optional(record.lastMsgId)
        out.optional(record.prevMsgId)
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
    }

    private fun readGroup(reader: Reader): GroupRecord {
        val cursor = reader.i64()
        val status = GroupStatus.entries.getOrNull(reader.u8()) ?: invalid()
        if (cursor < 0) invalid()
        val last = reader.optional()
        val prev = reader.optional()
        val failures = reader.counted(MAX_FAILURES, reader.u8()) { reader.i64() to reader.u8() }.toMap()
        val quarantined = reader.counted(MAX_QUARANTINED, reader.u16()) { reader.i64() }
        val unverified = reader.counted(MAX_UNVERIFIED, reader.u8()) { KeyBytes(reader.bytes(KEY_BYTES)) }
        return GroupRecord(cursor, status, failures, quarantined, unverified, last, prev)
    }

    private class Writer {
        private val buffer = ArrayList<Byte>()

        fun u8(value: Int) {
            buffer += value.toByte()
        }

        fun u16(value: Int) {
            u8(value ushr Byte.SIZE_BITS)
            u8(value)
        }

        fun i64(value: Long) {
            for (shift in Long.SIZE_BITS - Byte.SIZE_BITS downTo 0 step Byte.SIZE_BITS) buffer += (value ushr shift).toByte()
        }

        fun raw(bytes: ByteArray) {
            bytes.forEach { buffer += it }
        }

        fun optional(bytes: ByteArray?) {
            if (bytes == null) {
                u8(0)
            } else {
                if (bytes.size != MSG_ID_BYTES) invalid()
                u8(1)
                raw(bytes)
            }
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

    private class Reader(
        private val data: ByteArray,
    ) {
        private var at = 0

        fun done() = at == data.size

        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > data.size - at) invalid()
            return data.copyOfRange(at, at + n).also { at += n }
        }

        fun u8(): Int = bytes(1)[0].toInt() and BYTE_MASK

        fun u16(): Int = (u8() shl Byte.SIZE_BITS) or u8()

        fun i64(): Long = bytes(Long.SIZE_BYTES).fold(0L) { acc, b -> (acc shl Byte.SIZE_BITS) or (b.toLong() and BYTE_MASK.toLong()) }

        fun optional(): ByteArray? =
            when (u8()) {
                0 -> null
                1 -> bytes(MSG_ID_BYTES)
                else -> invalid()
            }

        fun <T> counted(
            max: Int,
            count: Int,
            read: () -> T,
        ): List<T> {
            if (count > max) invalid()
            return List(count) { read() }
        }

        private companion object {
            const val BYTE_MASK = 0xFF
        }
    }
}
