package app.gifitup.mls.storage

import app.gifitup.mls.toByteArray
import app.gifitup.mls.toUint8Array
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import kotlin.js.Promise

/** A new AES-256-GCM key that WebCrypto never lets out: `extractable = false` (ADR-0011). */
internal fun generateWrappingKey(): Promise<JsAny?> =
    js(
        """Promise.resolve().then(() =>
            crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']))""",
    )

/** Only a non-extractable AES-GCM 256 key for exactly encrypt and decrypt counts as the wrapping key. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
internal fun isWrappingKey(key: JsAny?): Boolean =
    js(
        """key instanceof CryptoKey && key.type === 'secret' && key.extractable === false
            && key.algorithm.name === 'AES-GCM' && key.algorithm.length === 256
            && key.usages.length === 2 && key.usages.includes('encrypt') && key.usages.includes('decrypt')""",
    )

/** `iv(12) || ciphertext || tag(16)`, with a fresh IV from `crypto.getRandomValues`. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun aesGcmSeal(
    key: JsAny,
    plaintext: Uint8Array,
    aad: Uint8Array,
): Promise<JsAny?> =
    js(
        """Promise.resolve().then(() => {
            const iv = crypto.getRandomValues(new Uint8Array(12));
            return crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv, additionalData: aad, tagLength: 128 }, key, plaintext)
                .then((sealed) => {
                    const out = new Uint8Array(12 + sealed.byteLength);
                    out.set(iv);
                    out.set(new Uint8Array(sealed), 12);
                    return out;
                });
        })""",
    )

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun aesGcmOpen(
    key: JsAny,
    iv: Uint8Array,
    sealed: Uint8Array,
    aad: Uint8Array,
): Promise<JsAny?> =
    js(
        """Promise.resolve().then(() =>
            crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv, additionalData: aad, tagLength: 128 }, key, sealed))
            .then((plain) => new Uint8Array(plain))""",
    )

/**
 * The web [WrappingAead]: a WebCrypto AES-256-GCM key with `extractable = false`, kept as a `CryptoKey` in the
 * database's `keys` object store (ADR-0011). Script in our origin can still use it, and the browser profile holds
 * its raw bytes; this is the accepted web risk in the threat model, not protection against them.
 */
internal class WebCryptoWrappingAead(
    private val db: WebDatabase,
) : WrappingAead {
    override suspend fun exists(): Boolean = isWrappingKey(db.get(WebDatabase.KEYS, KEY))

    override suspend fun create() {
        val key = settled(generateWrappingKey())
        if (!isWrappingKey(key)) throw LocalStateException(STORAGE)
        db.putAll(WebDatabase.KEYS, mapOf(KEY to key))
    }

    override suspend fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val key = key()
        val input = plaintext.toUint8Array()
        try {
            val sealed = settled(aesGcmSeal(key, input, aad.toUint8Array()))!!.unsafeCast<Uint8Array>()
            if (sealed.length != plaintext.size + EnvelopeSnapshotKeyStore.IV_SIZE + TAG_SIZE) throw LocalStateException(STORAGE)
            return sealed.toByteArray()
        } finally {
            input.zero()
        }
    }

    override suspend fun open(
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (sealed.size < EnvelopeSnapshotKeyStore.IV_SIZE + TAG_SIZE) throw LocalStateException(KEY_UNAVAILABLE)
        val key = key()
        val iv = sealed.copyOfRange(0, EnvelopeSnapshotKeyStore.IV_SIZE).toUint8Array()
        val body = sealed.copyOfRange(EnvelopeSnapshotKeyStore.IV_SIZE, sealed.size).toUint8Array()
        // A tag that doesn't verify is OperationError: a different or invalidated key, or changed bytes (ADR-0011).
        val plain =
            settled(aesGcmOpen(key, iv, body, aad.toUint8Array())) {
                LocalStateException(if (it == "OperationError") KEY_UNAVAILABLE else STORAGE)
            }!!.unsafeCast<Uint8Array>()
        try {
            return plain.toByteArray()
        } finally {
            plain.zero()
        }
    }

    override suspend fun destroy() = db.putAll(WebDatabase.KEYS, mapOf(KEY to null))

    private suspend fun key(): JsAny = db.get(WebDatabase.KEYS, KEY)?.takeIf(::isWrappingKey) ?: throw LocalStateException(KEY_UNAVAILABLE)

    private fun Uint8Array.zero() {
        for (i in 0 until length) this[i] = 0
    }

    internal companion object {
        const val KEY = "wrapping-key"
        private const val TAG_SIZE = 16
    }
}
