package app.gifitup.mls.storage

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The wrapping key in the Android Keystore (ADR-0011): AES-256-GCM, encrypt and decrypt, randomized encryption, no user
 * authentication (the app must open without a prompt; threat model). StrongBox when the device has it (API 28+), else
 * the TEE. The key never leaves the Keystore.
 */
class KeystoreKeySupplier(
    private val alias: String = ALIAS,
) : WrappingKeySupplier {
    override fun get(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    override fun create(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(strongBox = true)
            } catch (ignored: ProviderException) {
                // No StrongBox on this device (StrongBoxUnavailableException), or it failed to make the key: the TEE below.
            }
        }
        return generate(strongBox = false)
    }

    override fun delete() = keyStore().deleteEntry(alias)

    private fun generate(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setIsStrongBoxBacked(true)
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec.build()) }
            .generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "gifitup-snapshot-wrapping-key"
        const val KEY_BITS = 256
    }
}
