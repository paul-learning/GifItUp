package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.network.MessageKind

/** The stored form of a [PendingSend] (see [SyncStateCodec] for the whole layout). */
internal object PendingSendCodec {
    private const val MAX_DEVICE_IDS = 10
    private const val MAX_DEVICE_ID_CHARS = 36
    private const val MAX_BLOB = 16 * 1024
    private const val REFERENCE_BYTES = 32
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun write(
        out: ByteWriter,
        pending: PendingSend?,
    ) {
        if (pending == null) {
            out.u8(0)
        } else {
            out.u8(1)
            writeBody(out, pending)
        }
    }

    private fun writeBody(
        out: ByteWriter,
        pending: PendingSend,
    ) {
        if (pending.clientMsgId.size != SyncStateCodec.MSG_ID_BYTES) invalid()
        out.raw(pending.clientMsgId)
        out.u8(pending.kind.ordinal)
        val epoch = pending.epoch
        if (epoch == null) {
            out.u8(0)
        } else {
            out.u8(1)
            out.i64(epoch)
        }
        writeDevices(out, pending.added)
        writeDevices(out, pending.removed)
        val welcome = pending.welcome
        if (welcome == null) {
            out.u8(0)
        } else {
            if (welcome.size > MAX_BLOB) invalid()
            out.u8(1)
            out.blob(welcome)
        }
        if (pending.payload.size > MAX_BLOB) invalid()
        out.blob(pending.payload)
        val reference = pending.reference
        if (reference != null && (pending.kind != MessageKind.APPLICATION || reference.size != REFERENCE_BYTES)) invalid()
        out.optional(reference, REFERENCE_BYTES)
    }

    private fun writeDevices(
        out: ByteWriter,
        devices: List<String>,
    ) {
        if (devices.size > MAX_DEVICE_IDS || devices.any { it.length > MAX_DEVICE_ID_CHARS }) invalid()
        out.u8(devices.size)
        devices.forEach(out::text)
    }

    fun read(
        reader: ByteReader,
        version: Int,
    ): PendingSend? =
        when (reader.u8()) {
            0 -> null
            1 -> readBody(reader, version)
            else -> invalid()
        }

    private fun readBody(
        reader: ByteReader,
        version: Int,
    ): PendingSend {
        val id = reader.bytes(SyncStateCodec.MSG_ID_BYTES)
        val kind = MessageKind.entries.getOrNull(reader.u8()) ?: invalid()
        val epoch = if (reader.u8() == 1) reader.i64() else null
        val added = readDevices(reader)
        val removed = readDevices(reader)
        val welcome = if (reader.u8() == 1) reader.blob(MAX_BLOB) else null
        val payload = reader.blob(MAX_BLOB)
        val reference = if (version >= SyncStateCodec.REFERENCES_FROM) reader.optional(REFERENCE_BYTES) else null
        val pending = PendingSend(id, kind, epoch, added, removed, welcome, payload, reference)
        if (!wellFormed(pending) || (added + removed).toSet().size != added.size + removed.size) invalid()
        return pending
    }

    /** A commit has an epoch and no reference; an application message has neither epoch, devices nor welcome. */
    private fun wellFormed(pending: PendingSend): Boolean =
        pending.payload.isNotEmpty() &&
            when (pending.kind) {
                MessageKind.COMMIT -> {
                    pending.epoch != null && pending.epoch >= 0 && pending.reference == null
                }

                MessageKind.APPLICATION -> {
                    pending.epoch == null && pending.added.isEmpty() && pending.removed.isEmpty() && pending.welcome == null
                }
            }

    private fun readDevices(reader: ByteReader): List<String> =
        reader.counted(MAX_DEVICE_IDS, reader.u8()) { reader.text().also { if (!UUID.matches(it)) invalid() } }
}
