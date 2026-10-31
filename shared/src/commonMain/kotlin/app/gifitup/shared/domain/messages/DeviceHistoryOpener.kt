package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.domain.auth.DeviceStorage

/**
 * The [HistoryOpener] of the app: the history key of the registered identity of [origin] (made once over an empty record
 * store, never replaced, ADR-0017), the platform cipher over it, and the encrypted store over the device's records. The
 * key goes into the cipher, which zeroes its copy; ours is zeroed right after, whatever happens.
 */
class DeviceHistoryOpener(
    private val storage: DeviceStorage,
    private val origin: String,
    private val random: SecureRandomSource,
    /** The platform cipher; the web loads its wasm module here, on first use. */
    private val ciphers: suspend () -> HistoryCipherFactory,
    private val limits: HistoryLimits,
) : HistoryOpener {
    override suspend fun open(): EncryptedMessageStore {
        val key = storage.openHistoryKey(origin, storage.records, random)
        val cipher =
            try {
                ciphers().create(key, origin)
            } finally {
                key.fill(0)
            }
        return EncryptedMessageStore(cipher, storage.records, limits)
    }
}
