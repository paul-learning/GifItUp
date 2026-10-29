package app.gifitup.mls

import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import uniffi.gifitup_crypto.NativeHistoryCipher

/** [HistoryCipherFactory] over the uniffi bindings (Android and JVM; ADR-0017). */
object UniffiHistoryCipherFactory : HistoryCipherFactory {
    /** Rust copies the key; our copy is zeroed whatever happens. */
    override fun create(
        historyKey: ByteArray,
        origin: String,
    ): HistoryCipher =
        try {
            translated { UniffiHistoryCipher(NativeHistoryCipher(historyKey, origin)) }
        } finally {
            historyKey.fill(0)
        }
}

/** [HistoryCipher] over one uniffi `NativeHistoryCipher`; it owns the native object and frees it in [close]. */
internal class UniffiHistoryCipher(
    private val native: NativeHistoryCipher,
) : HistoryCipher {
    @Volatile
    private var dead = false

    override fun groupName(groupId: ByteArray): ByteArray = call { native.groupName(groupId) }

    override fun messageName(
        groupId: ByteArray,
        reference: ByteArray,
    ): ByteArray = call { native.messageName(groupId, reference) }

    override fun readMarkName(groupId: ByteArray): ByteArray = call { native.readMarkName(groupId) }

    override fun seal(
        name: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = call { native.seal(name, plaintext) }

    override fun open(
        name: ByteArray,
        sealed: ByteArray,
    ): ByteArray = call { native.open(name, sealed) }

    override fun close() {
        dead = true
        native.close()
    }

    private inline fun <T> call(block: () -> T): T {
        if (dead) throw MlsException(MlsErrorCode.Poisoned)
        return try {
            translated(block)
        } catch (e: MlsException) {
            if (e.code == MlsErrorCode.Poisoned) dead = true
            throw e
        }
    }
}
