package app.gifitup.server.db

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Repositories against the production PostgreSQL image, as the app role (ADR-0008). */
class RepositoriesTest {
    private val accounts = AccountRepository()
    private val devices = DeviceRepository()
    private val sessions = SessionRepository()
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    private fun withApp(block: (Connection) -> Unit) = TestPostgres.appAt(TestPostgres.migratedDatabase()).use(block)

    @Test
    fun takenUsernameIsAResultNotAnException() =
        withApp { db ->
            assertTrue(accounts.insert(db, UUID.randomUUID(), "alice", now, Joined.ViaAdmin))
            assertFalse(accounts.insert(db, UUID.randomUUID(), "alice", now, Joined.ViaAdmin))
        }

    @Test
    fun invalidValuesNeverReachTheDatabase() =
        withApp { db ->
            assertFailsWith<IllegalArgumentException> { accounts.insert(db, UUID.randomUUID(), "Alice", now, Joined.ViaAdmin) }
            assertFailsWith<IllegalArgumentException> { devices.insert(db, UUID.randomUUID(), UUID.randomUUID(), ByteArray(31), now) }
            assertFailsWith<IllegalArgumentException> { sessions.insert(db, ByteArray(31), UUID.randomUUID(), now, now.plusSeconds(1)) }
        }

    @Test
    fun devicesAreStoredFoundListedAndKeysAreUnique() =
        withApp { db ->
            val account = UUID.randomUUID().also { accounts.insert(db, it, "bob", now, Joined.ViaAdmin) }
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            assertTrue(devices.insert(db, first, account, KEY_A, now))
            assertTrue(devices.insert(db, second, account, KEY_B, now.plusSeconds(1)))
            assertFalse(devices.insert(db, UUID.randomUUID(), account, KEY_A, now), "a key belongs to one device")

            assertEquals(DeviceRecord(first, account, KEY_A, now, now), devices.find(db, first))
            assertNull(devices.find(db, UUID.randomUUID()))
            assertEquals(listOf(first, second), devices.listForAccount(db, account).map { it.id })
        }

    @Test
    fun lastSeenIsUpdatedAtMostHourly() =
        withApp { db ->
            val account = UUID.randomUUID().also { accounts.insert(db, it, "carol", now, Joined.ViaAdmin) }
            val device = UUID.randomUUID().also { devices.insert(db, it, account, KEY_A, now) }

            devices.touch(db, device, now.plus(Duration.ofMinutes(59)))
            assertEquals(now, devices.find(db, device)?.lastSeenAt)
            devices.touch(db, device, now.plus(Duration.ofMinutes(61)))
            assertEquals(now.plus(Duration.ofMinutes(61)), devices.find(db, device)?.lastSeenAt)
        }

    @Test
    fun sessionsAreFoundUntilTheyExpireOrAreDeleted() =
        withApp { db ->
            val (account, device) = deviceOf(db, "dave")
            sessions.insert(db, hash(1), device, now, now.plus(Duration.ofHours(24)))

            assertEquals(SessionRecord(device, account, now.plus(Duration.ofHours(24))), sessions.findValid(db, hash(1), now))
            assertNull(sessions.findValid(db, hash(1), now.plus(Duration.ofHours(24))), "expired: checked in the lookup")
            assertNull(sessions.findValid(db, hash(2), now))
            assertNull(sessions.findValid(db, ByteArray(5), now))
            assertTrue(sessions.delete(db, hash(1)))
            assertFalse(sessions.delete(db, hash(1)))
            assertNull(sessions.findValid(db, hash(1), now))
        }

    @Test
    fun theSixthSessionEvictsTheOldest() =
        withApp { db ->
            val (_, device) = deviceOf(db, "erin")
            (1..6).forEach { i -> sessions.insert(db, hash(i), device, now.plusSeconds(i.toLong()), now.plus(Duration.ofHours(24))) }

            assertNull(sessions.findValid(db, hash(1), now.plusSeconds(10)), "the oldest is gone")
            (2..6).forEach { i -> assertNotNull(sessions.findValid(db, hash(i), now.plusSeconds(10)), "session $i") }
        }

    /** #71 CR-2: a login whose clock is behind the others must not trim its own new session. */
    @Test
    fun theNewSessionSurvivesEvenWithAnOlderTimestamp() =
        withApp { db ->
            val (_, device) = deviceOf(db, "gina")
            (1..5).forEach { i -> sessions.insert(db, hash(i), device, now.plusSeconds(100L + i), now.plus(Duration.ofHours(24))) }
            sessions.insert(db, hash(9), device, now, now.plus(Duration.ofHours(24)))

            assertNotNull(sessions.findValid(db, hash(9), now.plusSeconds(200)), "the new session")
            val rows =
                db.createStatement().executeQuery("SELECT count(*) FROM sessions").use {
                    it.next()
                    it.getInt(1)
                }
            assertEquals(SessionRepository.MAX_SESSIONS_PER_DEVICE, rows)
        }

    @Test
    fun expiredSessionsAreCleanedUpOnTheNextLogin() =
        withApp { db ->
            val (_, device) = deviceOf(db, "frank")
            sessions.insert(db, hash(1), device, now, now.plusSeconds(60))
            sessions.insert(db, hash(2), device, now.plusSeconds(120), now.plus(Duration.ofHours(24)))

            val rows =
                db.createStatement().executeQuery("SELECT count(*) FROM sessions").use {
                    it.next()
                    it.getInt(1)
                }
            assertEquals(1, rows)
        }

    private fun deviceOf(
        db: Connection,
        username: String,
    ): Pair<UUID, UUID> {
        val account = UUID.randomUUID().also { accounts.insert(db, it, username, now, Joined.ViaAdmin) }
        val device = UUID.randomUUID().also { devices.insert(db, it, account, KEY_A, now) }
        return account to device
    }

    private fun hash(i: Int) = ByteArray(32) { i.toByte() }

    private companion object {
        val KEY_A = ByteArray(32) { 1 }
        val KEY_B = ByteArray(32) { 2 }
    }
}
