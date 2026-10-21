package app.gifitup.mls

import app.gifitup.shared.data.crypto.SecureRandomSource
import org.khronos.webgl.Uint8Array

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun randomBytes(size: Int): Uint8Array = js("crypto.getRandomValues(new Uint8Array(size))")

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun zero(array: Uint8Array): JsAny? = js("array.fill(0)")

/**
 * The browser's CSPRNG (`crypto.getRandomValues`) for the snapshot key (ADR-0011): the web composition root hands it to
 * the use cases. Sizes stay far below the API's 65,536-byte limit (the key is 32 bytes).
 */
object WebSecureRandom : SecureRandomSource {
    override fun nextBytes(size: Int): ByteArray {
        require(size in 1..MAX_BYTES) { "size out of range" }
        val js = randomBytes(size)
        val bytes = js.toByteArray()
        zero(js) // the JS copy of a key must not wait for the garbage collector (#131 SEC-18)
        return bytes
    }

    private const val MAX_BYTES = 65_536
}
