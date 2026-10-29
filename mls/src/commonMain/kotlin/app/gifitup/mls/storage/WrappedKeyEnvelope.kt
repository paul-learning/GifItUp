package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE

/**
 * What the wrapped snapshot key and the wrapped history key share (ADR-0011, ADR-0017): the associated data
 * `label || 0x00 || version || origin`, and the write-path check that a sealed key has the right size and opens to the
 * same key. Only the label differs between the two keys, and it is the only thing that tells their blobs apart.
 */
internal fun wrappedKeyAad(
    label: String,
    version: Byte,
    origin: String,
): ByteArray = label.encodeToByteArray() + byteArrayOf(0, version) + origin.encodeToByteArray()

/** Throws `STORAGE` unless [sealed] has the expected size and opens (under [aad]) to exactly [key]; zeroes what it opened. */
internal suspend fun verifyWrappedKey(
    aead: WrappingAead,
    sealed: ByteArray,
    key: ByteArray,
    aad: ByteArray,
) {
    if (sealed.size != EnvelopeSnapshotKeyStore.WRAPPED_SIZE - 1) throw LocalStateException(STORAGE)
    val opened = aead.open(sealed, aad)
    try {
        var difference = opened.size xor key.size
        for (i in 0 until minOf(opened.size, key.size)) difference = difference or (opened[i].toInt() xor key[i].toInt())
        if (difference != 0) throw LocalStateException(STORAGE)
    } finally {
        opened.fill(0)
    }
}
