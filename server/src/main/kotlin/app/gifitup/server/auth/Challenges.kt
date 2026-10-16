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
 * Login and registration challenges (ADR-0009, #68 round 0 SEC-1).
 *
 * `challenge = random[16] ‖ issuedAt (8-byte big-endian Unix seconds) ‖ HMAC-SHA256(key, random ‖ issuedAt)`, 56 bytes.
 * Issuing stores nothing, so flooding the challenge endpoint costs the server no memory. [key] is generated at server
 * start and never leaves the process; a restart invalidates outstanding challenges, and clients just ask again.
 *
 * Single use: [consume] is called as soon as a request with a fresh challenge arrives, before any shared budget or
 * signature check (#73 CR-7, SEC-39), so one challenge can be tried exactly once. The used set is then bounded by the
 * challenge endpoint's rate limit times the TTL (about 100/s × 65 s ≈ 6.5k entries), far below its capacity.
 *
 * Validity and single use share one TTL and one time source that never goes backwards (#71 CR-3, SEC-29): a clock that
 * jumps forward (dropping a used entry) and then back can't make a used challenge valid again. If the used set is full
 * of unexpired entries, [consume] refuses (fails closed) instead of forgetting one. Thread-safe.
 */
class Challenges(
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val key: ByteArray = ByteArray(KEY_SIZE).also { random.nextBytes(it) },
    private val ttl: Duration = DEFAULT_TTL,
    private val usedCapacity: Int = DEFAULT_USED_CAPACITY,
) {
    init {
        require(key.size == KEY_SIZE) { "challenge key must be 32 bytes" }
    }

    private var latest: Instant = Instant.MIN
    private val used = LinkedHashMap<String, Instant>()

    @Synchronized
    fun issue(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val body = nonce + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(now().epochSecond).array()
        return body + mac(body)
    }

    /**
     * True if [challenge] was issued by this process (MAC, compared in constant time), is at most [ttl] old, isn't from
     * the future beyond [MAX_CLOCK_SKEW], and hasn't been consumed. Checked before any signature verification, so a
     * forged challenge costs one HMAC.
     */
    @Synchronized
    fun isValid(challenge: ByteArray): Boolean = isFresh(challenge, now()) && hexOf(challenge) !in used

    /** Marks a valid challenge as used. Call only after its signature verified. False if invalid, reused, or the set is full. */
    @Synchronized
    fun consume(challenge: ByteArray): Boolean {
        val now = now()
        used.values.removeIf { !it.isAfter(now) }
        val id = hexOf(challenge)
        if (!isFresh(challenge, now) || id in used || used.size >= usedCapacity) return false
        used[id] = now.plus(ttl).plus(MAX_CLOCK_SKEW)
        return true
    }

    private fun isFresh(
        challenge: ByteArray,
        now: Instant,
    ): Boolean {
        if (challenge.size != CHALLENGE_SIZE) return false
        val body = challenge.copyOfRange(0, BODY_SIZE)
        val macOk = MessageDigest.isEqual(mac(body), challenge.copyOfRange(BODY_SIZE, CHALLENGE_SIZE))
        // Plain seconds, no Instant: the bytes are attacker-controlled until the MAC is checked, and Instant throws
        // for out-of-range values. Subtraction can't overflow for a timestamp we issued; others fail the MAC anyway.
        val issuedAt = ByteBuffer.wrap(body, NONCE_SIZE, Long.SIZE_BYTES).long
        val nowSeconds = now.epochSecond
        return macOk && issuedAt - nowSeconds <= MAX_CLOCK_SKEW.seconds && nowSeconds - issuedAt <= ttl.seconds
    }

    /** The wall clock, but never earlier than any time this instance has already seen. */
    private fun now(): Instant = maxOf(latest, clock.instant()).also { latest = it }

    private fun mac(body: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(body)
        }

    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val CHALLENGE_SIZE = 56
        const val KEY_SIZE = 32
        val DEFAULT_TTL: Duration = Duration.ofSeconds(60)
        private const val NONCE_SIZE = 16
        private const val BODY_SIZE = NONCE_SIZE + Long.SIZE_BYTES
        private const val HMAC = "HmacSHA256"
        private const val DEFAULT_USED_CAPACITY = 100_000
        private val MAX_CLOCK_SKEW: Duration = Duration.ofSeconds(5)
    }
}
