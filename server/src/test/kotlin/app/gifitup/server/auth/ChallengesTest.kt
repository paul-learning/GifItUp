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

/** A clock the test moves by hand. */
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
    private val service = ChallengeService(clock)

    @Test
    fun issuedChallengesAre56BytesUniqueAndValid() {
        val a = service.issue()
        val b = service.issue()
        assertEquals(ChallengeService.CHALLENGE_SIZE, a.size)
        assertFalse(a.contentEquals(b))
        assertTrue(service.isValid(a))
        assertTrue(service.isValid(b))
    }

    @Test
    fun everyTamperedByteIsInvalid() {
        val challenge = service.issue()
        challenge.indices.forEach { i ->
            val tampered = challenge.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse(service.isValid(tampered), "byte $i")
        }
    }

    @Test
    fun anotherServersChallengeIsInvalid() {
        assertFalse(service.isValid(ChallengeService(clock).issue()), "different key, e.g. before a restart")
    }

    @Test
    fun challengesExpireAfterTheTtl() {
        val challenge = service.issue()
        clock.advance(ChallengeService.DEFAULT_TTL)
        assertTrue(service.isValid(challenge), "exactly at the TTL")
        clock.advance(Duration.ofSeconds(1))
        assertFalse(service.isValid(challenge))
    }

    @Test
    fun challengesFromTheFutureAreInvalid() {
        val future = ChallengeService(TestClock(clock.now.plusSeconds(6)), key = KEY).issue()
        val nearFuture = ChallengeService(TestClock(clock.now.plusSeconds(5)), key = KEY).issue()
        val checker = ChallengeService(clock, key = KEY)
        assertFalse(checker.isValid(future))
        assertTrue(checker.isValid(nearFuture), "small clock skew is tolerated")
    }

    @Test
    fun wrongSizesAreInvalid() {
        val challenge = service.issue()
        assertFalse(service.isValid(challenge.copyOf(55)))
        assertFalse(service.isValid(challenge + 0))
        assertFalse(service.isValid(ByteArray(0)))
    }

    @Test
    fun aChallengeCanBeUsedOnce() {
        val used = UsedChallenges(clock)
        val challenge = service.issue()
        assertTrue(used.markUsed(challenge))
        assertFalse(used.markUsed(challenge))
        assertTrue(used.markUsed(service.issue()))
    }

    @Test
    fun usedSetForgetsOnlyExpiredEntries() {
        val used = UsedChallenges(clock)
        val challenge = service.issue()
        used.markUsed(challenge)
        clock.advance(ChallengeService.DEFAULT_TTL)
        assertFalse(used.markUsed(challenge), "still remembered while the challenge could be valid")
        clock.advance(Duration.ofSeconds(11))
        assertFalse(service.isValid(challenge), "by the time it is forgotten, the challenge itself has expired")
        assertTrue(used.markUsed(challenge))
    }

    @Test
    fun fullUsedSetRefusesInsteadOfForgetting() {
        val used = UsedChallenges(clock, capacity = 2)
        val first = service.issue()
        assertTrue(used.markUsed(first))
        assertTrue(used.markUsed(service.issue()))
        assertFalse(used.markUsed(service.issue()), "full: refuse")
        assertFalse(used.markUsed(first), "and the first is still remembered")
    }

    private companion object {
        val KEY = ByteArray(32) { 9 }
    }
}
