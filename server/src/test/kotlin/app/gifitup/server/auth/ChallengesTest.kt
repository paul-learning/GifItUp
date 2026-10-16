package app.gifitup.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A clock the test moves by hand, forwards or backwards. */
internal class TestClock(
    var now: Instant = Instant.parse("2026-10-16T12:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

class ChallengesTest {
    private val clock = TestClock()
    private val challenges = Challenges(clock)

    @Test
    fun issuedChallengesAre56BytesUniqueAndValid() {
        val a = challenges.issue()
        val b = challenges.issue()
        assertEquals(Challenges.CHALLENGE_SIZE, a.size)
        assertFalse(a.contentEquals(b))
        assertTrue(challenges.isValid(a))
        assertTrue(challenges.isValid(b))
    }

    @Test
    fun everyTamperedByteIsInvalid() {
        val challenge = challenges.issue()
        challenge.indices.forEach { i ->
            val tampered = challenge.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse(challenges.isValid(tampered), "byte $i")
            assertFalse(challenges.consume(tampered), "byte $i")
        }
    }

    @Test
    fun anotherServersChallengeIsInvalid() {
        assertFalse(challenges.isValid(Challenges(clock).issue()), "different key, e.g. before a restart")
    }

    @Test
    fun challengesExpireAfterTheTtl() {
        val challenge = challenges.issue()
        clock.advance(Challenges.DEFAULT_TTL)
        assertTrue(challenges.isValid(challenge), "exactly at the TTL")
        clock.advance(Duration.ofSeconds(1))
        assertFalse(challenges.isValid(challenge))
        assertFalse(challenges.consume(challenge))
    }

    @Test
    fun challengesFromTheFutureAreInvalid() {
        val future = Challenges(TestClock(clock.now.plusSeconds(6)), key = KEY).issue()
        val nearFuture = Challenges(TestClock(clock.now.plusSeconds(5)), key = KEY).issue()
        val checker = Challenges(clock, key = KEY)
        assertFalse(checker.isValid(future))
        assertTrue(checker.isValid(nearFuture), "small clock skew is tolerated")
    }

    @Test
    fun wrongSizesAreInvalid() {
        val challenge = challenges.issue()
        assertFalse(challenges.isValid(challenge.copyOf(55)))
        assertFalse(challenges.isValid(challenge + 0))
        assertFalse(challenges.isValid(ByteArray(0)))
    }

    @Test
    fun aChallengeCanBeConsumedOnce() {
        val challenge = challenges.issue()
        assertTrue(challenges.consume(challenge))
        assertFalse(challenges.isValid(challenge), "used")
        assertFalse(challenges.consume(challenge))
        assertTrue(challenges.consume(challenges.issue()))
    }

    /** #71 SEC-29: forward past the TTL (the used entry is pruned), then back: still refused. */
    @Test
    fun aClockJumpBackCannotReviveAUsedChallenge() {
        val challenge = challenges.issue()
        assertTrue(challenges.consume(challenge))
        clock.advance(Duration.ofSeconds(120))
        challenges.consume(challenges.issue()) // prunes expired entries, including the first
        clock.advance(Duration.ofSeconds(-120))

        assertFalse(challenges.isValid(challenge))
        assertFalse(challenges.consume(challenge))
    }

    @Test
    fun aFullUsedSetRefusesInsteadOfForgetting() {
        val small = Challenges(clock, usedCapacity = 2)
        val first = small.issue()
        assertTrue(small.consume(first))
        assertTrue(small.consume(small.issue()))
        assertFalse(small.consume(small.issue()), "full: refuse")
        assertFalse(small.consume(first), "and the first is still remembered")
    }

    private companion object {
        val KEY = ByteArray(32) { 9 }
    }
}
