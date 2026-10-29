package app.gifitup.shared.data.crypto

/**
 * A [HistoryCipher] that is only as strong as a unit test needs: names are a plain hash of the ids, a sealed record is
 * `marker ‖ name ‖ plaintext xor key`, and a record opens only under the same key and the same name (the checks of the real
 * one: [MlsErrorCode.HistoryAuth] for another key, name or changed byte, [MlsErrorCode.HistoryInvalid] for a bad size).
 * [maxPlaintext] lets a test make `seal` refuse a record, and [poisoned] makes every call fail like a dead module.
 */
class FakeHistoryCipher(
    private val key: Int = 7,
    private val maxPlaintext: Int = 4092,
) : HistoryCipher {
    var poisoned = false

    private fun check() {
        if (poisoned) throw MlsException(MlsErrorCode.Poisoned)
    }

    private fun name(
        kind: Int,
        vararg parts: ByteArray,
    ): ByteArray {
        var acc = kind * 1_000_003 + key
        val out = ByteArray(NAME)
        var i = 0
        for (part in parts) {
            acc = acc * 31 + part.size
            for (b in part) {
                acc = acc * 31 + b
                out[i % NAME] = (out[i % NAME] + acc).toByte()
                i++
            }
        }
        return out
    }

    override fun groupName(groupId: ByteArray): ByteArray = check().let { name(1, groupId) }

    override fun messageName(
        groupId: ByteArray,
        reference: ByteArray,
    ): ByteArray = check().let { name(2, groupId, reference) }

    override fun readMarkName(groupId: ByteArray): ByteArray = check().let { name(3, groupId) }

    override fun seal(
        name: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        check()
        if (name.size != NAME || plaintext.size > maxPlaintext) throw MlsException(MlsErrorCode.HistoryInvalid)
        return byteArrayOf(MARKER) + name + ByteArray(plaintext.size) { (plaintext[it].toInt() xor key).toByte() } +
            byteArrayOf(checksum(plaintext))
    }

    override fun open(
        name: ByteArray,
        sealed: ByteArray,
    ): ByteArray {
        check()
        if (name.size != NAME || sealed.size < 1 + NAME + 1 || sealed[0] != MARKER) throw MlsException(MlsErrorCode.HistoryInvalid)
        if (!sealed.copyOfRange(1, 1 + NAME).contentEquals(name)) throw MlsException(MlsErrorCode.HistoryAuth)
        val body = sealed.copyOfRange(1 + NAME, sealed.size - 1)
        val plaintext = ByteArray(body.size) { (body[it].toInt() xor key).toByte() }
        if (checksum(plaintext) != sealed.last()) throw MlsException(MlsErrorCode.HistoryAuth)
        return plaintext
    }

    override fun close() = Unit

    private fun checksum(plaintext: ByteArray): Byte = (plaintext.fold(key) { acc, b -> acc * 31 + b }).toByte()

    private companion object {
        const val NAME = 32
        const val MARKER: Byte = 0x46
    }
}
