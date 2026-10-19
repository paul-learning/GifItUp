package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.KEY_UNAVAILABLE
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where [JcaWrappingAead] gets its AES-256 key: the Android Keystore on Android (`KeystoreKeySupplier`), a software
 * key in the JVM tests. Calls block (a Keystore or StrongBox call can take a while), so callers keep them off the main
 * thread (#17).
 */
interface WrappingKeySupplier {
    /** The key, or null if there is none. */
    fun get(): SecretKey?

    /** Makes the key; only [JcaWrappingAead.create] calls it. */
    fun create(): SecretKey

    /** Deletes the key; a missing key is no error. */
    fun delete()
}

/**
 * [WrappingAead] over `javax.crypto` AES-256-GCM (ADR-0011). The IV comes from the provider (the Keystore requires
 * it), and its length is checked. Every failure is mapped to a [LocalStateException] with no message: a missing,
 * invalidated or unusable key and a tag that doesn't verify are `KEY_UNAVAILABLE`, anything else `STORAGE`.
 */
class JcaWrappingAead(
    private val keys: WrappingKeySupplier,
) : WrappingAead {
    override suspend fun exists(): Boolean = mapped { keys.get() != null }

    override suspend fun create() = mapped { keys.create() }.let { }

    override suspend fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        mapped {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            val body = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv == null || iv.size != EnvelopeSnapshotKeyStore.IV_SIZE) throw LocalStateException(STORAGE)
            iv + body
        }

    override suspend fun open(
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        mapped {
            if (sealed.size < EnvelopeSnapshotKeyStore.IV_SIZE + TAG_BYTES) throw LocalStateException(KEY_UNAVAILABLE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, EnvelopeSnapshotKeyStore.IV_SIZE))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, EnvelopeSnapshotKeyStore.IV_SIZE, sealed.size - EnvelopeSnapshotKeyStore.IV_SIZE)
        }

    override suspend fun destroy() = mapped { keys.delete() }

    private fun key(): SecretKey = keys.get() ?: throw LocalStateException(KEY_UNAVAILABLE)

    @Suppress("TooGenericExceptionCaught") // Platform code throws Provider and Keystore runtime exceptions too; all are mapped.
    private fun <T> mapped(block: () -> T): T =
        try {
            block()
        } catch (e: LocalStateException) {
            throw e
        } catch (ignored: AEADBadTagException) {
            throw LocalStateException(KEY_UNAVAILABLE)
        } catch (ignored: UnrecoverableKeyException) {
            throw LocalStateException(KEY_UNAVAILABLE)
        } catch (ignored: InvalidKeyException) {
            // Includes KeyPermanentlyInvalidatedException: the key is there but can't be used.
            throw LocalStateException(KEY_UNAVAILABLE)
        } catch (ignored: GeneralSecurityException) {
            throw LocalStateException(STORAGE)
        } catch (ignored: IOException) {
            throw LocalStateException(STORAGE)
        } catch (ignored: RuntimeException) {
            throw LocalStateException(STORAGE)
        }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
    }
}
