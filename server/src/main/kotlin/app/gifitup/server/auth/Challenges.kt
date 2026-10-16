package app.gifitup.server.auth

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless login and registration challenges (ADR-0009, #68 round 0 SEC-1):
 * `challenge = random[16] ‖ issuedAt (8-byte big-endian Unix seconds) ‖ HMAC-SHA256(key, random ‖ issuedAt)`, 56 bytes.
 *
 * Issuing stores nothing, so flooding the challenge endpoint costs the server no memory. [key] is generated at server
 * start and never leaves the process; a restart invalidates outstanding challenges, and clients just ask again.
 * Single use is enforced separately, by [UsedChallenges], and only after a valid signature.
 */
class ChallengeService(
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val key: ByteArray = ByteArray(KEY_SIZE).also { random.nextBytes(it) },
    private val ttl: Duration = DEFAULT_TTL,
) {
    init {
        require(key.size == KEY_SIZE) { "challenge key must be 32 bytes" }
    }

    fun issue(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val body = nonce + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(clock.instant().epochSecond).array()
        return body + mac(body)
    }

    /**
     * True if [challenge] was issued by this process (MAC, compared in constant time), is at most [ttl] old and not
     * from the future beyond [MAX_CLOCK_SKEW]. Checked before any signature verification, so forged challenges cost
     * one HMAC.
     */
    fun isValid(challenge: ByteArray): Boolean {
        if (challenge.size != CHALLENGE_SIZE) return false
        val body = challenge.copyOfRange(0, BODY_SIZE)
        val macOk = MessageDigest.isEqual(mac(body), challenge.copyOfRange(BODY_SIZE, CHALLENGE_SIZE))
        // Plain seconds, no Instant: the bytes are attacker-controlled until the MAC is checked, and Instant throws
        // for out-of-range values. Subtraction can't overflow for a timestamp we issued; others fail the MAC anyway.
        val issuedAt = ByteBuffer.wrap(body, NONCE_SIZE, Long.SIZE_BYTES).long
        val now = clock.instant().epochSecond
        return macOk && issuedAt - now <= MAX_CLOCK_SKEW.seconds && now - issuedAt <= ttl.seconds
    }

    private fun mac(body: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(body)
        }

    companion object {
        const val CHALLENGE_SIZE = 56
        const val KEY_SIZE = 32
        val DEFAULT_TTL: Duration = Duration.ofSeconds(60)
        private const val NONCE_SIZE = 16
        private const val BODY_SIZE = NONCE_SIZE + Long.SIZE_BYTES
        private const val HMAC = "HmacSHA256"
        private val MAX_CLOCK_SKEW: Duration = Duration.ofSeconds(5)
    }
}

/**
 * Challenges already used by a successful registration or login (SEC-1). Called only **after** the signature verified,
 * so the set grows only with successful requests. Entries expire with the challenge's TTL. If the set is full of
 * unexpired entries, [markUsed] refuses (fails closed) instead of evicting, so it can never forget a used challenge.
 * Thread-safe.
 */
class UsedChallenges(
    private val clock: Clock,
    private val ttl: Duration = ChallengeService.DEFAULT_TTL,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val expiries = LinkedHashMap<String, Instant>()

    /** Records [challenge] as used; false if it was used before or the set is full. */
    @Synchronized
    fun markUsed(challenge: ByteArray): Boolean {
        val now = clock.instant()
        expiries.values.removeIf { !it.isAfter(now) }
        val id = challenge.joinToString("") { "%02x".format(it) }
        if (id in expiries || expiries.size >= capacity) return false
        expiries[id] = now.plus(ttl).plus(EXPIRY_MARGIN)
        return true
    }

    private companion object {
        const val DEFAULT_CAPACITY = 100_000
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(10)
    }
}
