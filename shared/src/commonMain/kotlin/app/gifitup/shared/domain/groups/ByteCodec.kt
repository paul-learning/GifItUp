package app.gifitup.shared.domain.groups

/** Any damage in the stored sync state is refused whole (fail closed); nothing is repaired or guessed. */
internal fun invalid(): Nothing = throw SyncStateException()

private const val BYTE_MASK = 0xFF

/** Big-endian writer for the sync state; sizes are checked by the callers that know their caps. */
internal class ByteWriter {
    private val buffer = ArrayList<Byte>()

    val size get() = buffer.size

    fun u8(value: Int) {
        buffer += value.toByte()
    }

    fun u16(value: Int) {
        u8(value ushr Byte.SIZE_BITS)
        u8(value)
    }

    fun u32(value: Int) {
        u16(value ushr Short.SIZE_BITS)
        u16(value)
    }

    fun i64(value: Long) {
        for (shift in Long.SIZE_BITS - Byte.SIZE_BITS downTo 0 step Byte.SIZE_BITS) buffer += (value ushr shift).toByte()
    }

    fun raw(bytes: ByteArray) {
        bytes.forEach { buffer += it }
    }

    /** A flag byte, then exactly [size] bytes if present. */
    fun optional(
        bytes: ByteArray?,
        size: Int,
    ) {
        if (bytes == null) {
            u8(0)
        } else {
            if (bytes.size != size) invalid()
            u8(1)
            raw(bytes)
        }
    }

    /** `u32` length, then the bytes. */
    fun blob(bytes: ByteArray) {
        u32(bytes.size)
        raw(bytes)
    }

    /** `u8` length in bytes, then UTF-8 text. */
    fun text(value: String) {
        val bytes = value.encodeToByteArray()
        if (bytes.size > BYTE_MASK) invalid()
        u8(bytes.size)
        raw(bytes)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

/** Strict big-endian reader: every length is checked against what is left before anything is read. */
internal class ByteReader(
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

    fun u32(): Int = (u16() shl Short.SIZE_BITS) or u16()

    fun i64(): Long = bytes(Long.SIZE_BYTES).fold(0L) { acc, b -> (acc shl Byte.SIZE_BITS) or (b.toLong() and BYTE_MASK.toLong()) }

    fun optional(size: Int): ByteArray? =
        when (u8()) {
            0 -> null
            1 -> bytes(size)
            else -> invalid()
        }

    fun blob(max: Int): ByteArray {
        val length = u32()
        if (length < 0 || length > max) invalid()
        return bytes(length)
    }

    fun text(): String = bytes(u8()).decodeToString(throwOnInvalidSequence = true)

    fun <T> counted(
        max: Int,
        count: Int,
        read: () -> T,
    ): List<T> {
        if (count > max) invalid()
        return List(count) { read() }
    }
}
