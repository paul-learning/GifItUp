package app.gifitup.shared.data.storage

import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED

/**
 * [IdentityStore] over the app's [LocalStore] (ADR-0011). The record holds nothing secret beyond the snapshot, which is
 * already encrypted inside Rust. Each [save] replaces the record in one atomic write; the store keeps no older copy
 * (physical copies on disk are a platform matter, see ADR-0011).
 */
class RecordIdentityStore(
    private val store: LocalStore,
) : IdentityStore {
    override suspend fun load(): StoredIdentity? = store.get(KEY)?.let(::decode)

    /**
     * Writes the identity. The **first** write of an identity (there is no identity record yet) also writes an empty
     * contact record in the same atomic write (ADR-0014), replacing any leftover of an earlier identity. Later saves
     * never touch the contact record, so a record that went missing or damaged stays unavailable instead of being
     * healed into an empty book. An identity from before the contact book has no record; it needs a wipe and a new
     * registration (alpha, no friends yet).
     */
    override suspend fun save(identity: StoredIdentity) {
        val first = store.get(KEY) == null
        val contacts =
            if (first) {
                mapOf(
                    RecordContactStore.KEY to ContactRecordCodec.empty(identity.origin, identity.username),
                )
            } else {
                emptyMap()
            }
        store.putAll(mapOf(KEY to encode(identity)) + contacts)
    }

    /** The contact book belongs to the identity: it goes with it. */
    override suspend fun clear() = store.putAll(mapOf(KEY to null, RecordContactStore.KEY to null))

    internal companion object {
        const val KEY = "identity"
        private const val VERSION: Byte = 1
        private const val MAX_TEXT_BYTES = 0xFFFF

        /** `version || text(origin) || text(username) || present(1) [|| text(deviceId)] || snapshot`. */
        fun encode(identity: StoredIdentity): ByteArray {
            val out = ArrayList<Byte>(identity.snapshot.size + HEADER_ESTIMATE)
            out += VERSION
            out.addText(identity.origin)
            out.addText(identity.username)
            val deviceId = identity.deviceId
            out += if (deviceId == null) 0 else 1
            if (deviceId != null) out.addText(deviceId)
            return out.toByteArray() + identity.snapshot
        }

        /** Strict: an unknown version, a short field or an empty text is [DAMAGED], never a guess. */
        fun decode(bytes: ByteArray): StoredIdentity {
            val reader = Reader(bytes)
            if (reader.byte() != VERSION) damaged()
            val origin = reader.text()
            val username = reader.text()
            val deviceId =
                when (reader.byte()) {
                    0.toByte() -> null
                    1.toByte() -> reader.text()
                    else -> damaged()
                }
            val snapshot = reader.rest()
            if (snapshot.isEmpty()) damaged()
            return StoredIdentity(origin, username, snapshot, deviceId)
        }

        private const val HEADER_ESTIMATE = 128

        private fun MutableList<Byte>.addText(text: String) {
            val bytes = text.encodeToByteArray()
            require(bytes.isNotEmpty() && bytes.size <= MAX_TEXT_BYTES) { "text field out of range" }
            add((bytes.size shr Byte.SIZE_BITS).toByte())
            add(bytes.size.toByte())
            addAll(bytes.toList())
        }

        private class Reader(
            private val bytes: ByteArray,
        ) {
            private var at = 0

            fun byte(): Byte = if (at < bytes.size) bytes[at++] else damaged()

            fun text(): String {
                val length = (byte().toInt() and BYTE_MASK shl Byte.SIZE_BITS) or (byte().toInt() and BYTE_MASK)
                if (length == 0 || at + length > bytes.size) damaged()
                val text = runCatching { bytes.decodeToString(at, at + length, throwOnInvalidSequence = true) }.getOrElse { damaged() }
                at += length
                return text
            }

            fun rest(): ByteArray = bytes.copyOfRange(at, bytes.size)
        }

        private const val BYTE_MASK = 0xFF
    }
}

/**
 * Failed restores, counted outside the snapshot ([app.gifitup.shared.domain.auth.GuardedMlsFactory], #16 SEC-4). Each
 * write is durable when it returns, so a count written before a restore survives a trap and the reload after it.
 */
interface RestoreCounter {
    suspend fun failures(): Int

    suspend fun set(failures: Int)
}

/** [RestoreCounter] in the app's [LocalStore]. A damaged count reads as the limit: it never unblocks by itself. */
class StoredRestoreCounter(
    private val store: LocalStore,
) : RestoreCounter {
    override suspend fun failures(): Int {
        val bytes = store.get(KEY) ?: return 0
        return if (bytes.size == 1 && bytes[0] >= 0) bytes[0].toInt() else Byte.MAX_VALUE.toInt()
    }

    override suspend fun set(failures: Int) {
        require(failures >= 0) { "negative count" }
        store.putAll(mapOf(KEY to byteArrayOf(failures.coerceAtMost(Byte.MAX_VALUE.toInt()).toByte())))
    }

    internal companion object {
        const val KEY = "restore-failures"
    }
}

private fun damaged(): Nothing = throw LocalStateException(DAMAGED)
