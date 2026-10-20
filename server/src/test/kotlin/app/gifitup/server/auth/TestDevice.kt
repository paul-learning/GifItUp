package app.gifitup.server.auth

import java.security.KeyPairGenerator
import java.security.Signature

/** A test device: an Ed25519 key pair, as the crate holds it, signing with the JDK. */
internal class TestDevice {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicKey: ByteArray =
        pair.public.encoded
            .takeLast(32)
            .toByteArray()

    fun sign(content: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(content)
            sign()
        }
}
