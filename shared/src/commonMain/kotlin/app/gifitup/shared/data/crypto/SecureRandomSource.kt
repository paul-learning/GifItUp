package app.gifitup.shared.data.crypto

/**
 * The platform CSPRNG (`SecureRandom` on the JVM and Android, `crypto.getRandomValues` on the web), injected by the
 * composition root. `commonMain` has none, and `kotlin.random.Random` is predictable, so it must never stand in for this
 * (#79 SEC-5).
 */
fun interface SecureRandomSource {
    fun nextBytes(size: Int): ByteArray
}
