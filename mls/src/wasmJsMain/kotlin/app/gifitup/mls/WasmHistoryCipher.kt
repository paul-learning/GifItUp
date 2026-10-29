package app.gifitup.mls

import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException

/** Loads the wasm module once and returns the browser [HistoryCipherFactory] (ADR-0017); the module is the MLS one. */
suspend fun loadWasmHistoryCipherFactory(): HistoryCipherFactory {
    loadWasmModule()
    return WasmHistoryCipherFactory
}

private object WasmHistoryCipherFactory : HistoryCipherFactory {
    override fun create(
        historyKey: ByteArray,
        origin: String,
    ): HistoryCipher = withSnapshotKey(historyKey) { key -> WasmHistoryCipher(guarded { NativeHistoryCipher(key, origin) }) }
}

/** [HistoryCipher] over one wasm-bindgen `NativeHistoryCipher`; frees it in [close]. */
private class WasmHistoryCipher(
    private val native: NativeHistoryCipher,
) : HistoryCipher {
    private var closed = false

    override fun groupName(groupId: ByteArray): ByteArray = call { guarded { native.groupName(groupId.toUint8Array()) }.toByteArray() }

    override fun messageName(
        groupId: ByteArray,
        reference: ByteArray,
    ): ByteArray = call { guarded { native.messageName(groupId.toUint8Array(), reference.toUint8Array()) }.toByteArray() }

    override fun readMarkName(groupId: ByteArray): ByteArray =
        call { guarded { native.readMarkName(groupId.toUint8Array()) }.toByteArray() }

    override fun seal(
        name: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = call { guarded { native.seal(name.toUint8Array(), plaintext.toUint8Array()) }.toByteArray() }

    override fun open(
        name: ByteArray,
        sealed: ByteArray,
    ): ByteArray = call { guarded { native.open(name.toUint8Array(), sealed.toUint8Array()) }.toByteArray() }

    override fun close() {
        if (closed) return
        closed = true
        try {
            freeGuarded { native.free() }
        } catch (_: MlsException) {
            // The module is now marked dead; there is nothing left to free.
        }
    }

    private fun <T> call(block: () -> T): T {
        if (closed) throw MlsException(MlsErrorCode.Poisoned)
        return kotlinErrorsAsInternal(block)
    }
}
