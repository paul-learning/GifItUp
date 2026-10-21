package app.gifitup.mls

import app.gifitup.shared.data.crypto.SecureRandomSource
import org.khronos.webgl.Uint8Array

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun randomBytes(size: Int): Uint8Array = js("crypto.getRandomValues(new Uint8Array(size))")

/**
 * The browser's CSPRNG (`crypto.getRandomValues`) for the snapshot key (ADR-0011): the web composition root hands it to
 * the use cases. Sizes stay far below the API's 65,536-byte limit (the key is 32 bytes).
 */
object WebSecureRandom : SecureRandomSource {
    override fun nextBytes(size: Int): ByteArray {
        require(size in 1..MAX_BYTES) { "size out of range" }
        return randomBytes(size).toByteArray()
    }

    private const val MAX_BYTES = 65_536
}
