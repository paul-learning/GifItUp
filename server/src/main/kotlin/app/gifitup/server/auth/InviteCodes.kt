package app.gifitup.server.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Invite codes (ADR-0010): 16 random bytes (128 bit), written as 22 characters of canonical base64url. High entropy,
 * so a plain SHA-256 is what the database stores, and online guessing at the global register budget is hopeless.
 */
object InviteCodes {
    const val CODE_BYTES = 16

    /** A fresh code. The caller hands it out exactly once and never logs it. */
    fun generate(random: SecureRandom): String = Base64Url.encode(ByteArray(CODE_BYTES).also { random.nextBytes(it) })

    /** True for exactly the canonical 22-character form (strict decode, so non-canonical trailing bits are refused). */
    fun isWellFormed(code: String): Boolean = Base64Url.decode(code, CODE_BYTES) != null

    /** SHA-256 of the decoded code, or null if [code] isn't well-formed. */
    fun hash(code: String): ByteArray? = Base64Url.decode(code, CODE_BYTES)?.let { MessageDigest.getInstance("SHA-256").digest(it) }

    /**
     * The shareable link: the code goes in the **fragment**, which browsers never send to a server, so it stays out of
     * Caddy's and the server's request logs and out of Referer headers (SEC-M0-10). It protects against HTTP logs only:
     * the server itself creates every code and sees it again at registration (#13 SEC-44).
     */
    fun link(
        origin: CanonicalOrigin,
        code: String,
    ): String = "${origin.text}/invite#$code"
}
