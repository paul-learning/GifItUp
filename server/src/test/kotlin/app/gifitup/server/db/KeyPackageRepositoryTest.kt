package app.gifitup.server.db

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The KeyPackage directory's table and queries (ADR-0012, V3), as the app role, on the production PostgreSQL image. */
class KeyPackageRepositoryTest {
    private val accounts = AccountRepository()
    private val devices = DeviceRepository()
    private val repository = KeyPackageRepository()
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private val farFuture = now.plus(Duration.ofDays(60))

    /** Distinct for every n: the first four bytes are n. */
    private fun bytes(n: Int) = ByteArray(40) { if (it < 4) (n shr (8 * it)).toByte() else it.toByte() }

    private fun kp(
        n: Int,
        suite: Int = 1,
        notAfter: Instant = farFuture,
    ) = NewKeyPackage(bytes(n), suite, notAfter)

    private fun withApp(block: (Connection) -> Unit) = TestPostgres.appAt(TestPostgres.migratedDatabase()).use(block)

    private fun deviceOf(
        db: Connection,
        username: String,
        keyByte: Int = username.hashCode(),
    ): Pair<UUID, UUID> {
        val account = UUID.randomUUID().also { accounts.insert(db, it, username, now, Joined.ViaAdmin) }
        val device = UUID.randomUUID().also { devices.insert(db, it, account, ByteArray(32) { i -> (keyByte + i).toByte() }, now) }
        return account to device
    }

    private fun rows(
        db: Connection,
        sql: String = "SELECT count(*) FROM key_packages",
    ) = db.createStatement().use { s ->
        s.executeQuery(sql).use {
            it.next()
            it.getInt(1)
        }
    }

    @Test
    fun insertCountsNewOnesAndSkipsAKeyPackageThatIsAlreadyStored() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            val (_, other) = deviceOf(db, "bob")
            assertEquals(2, repository.insert(db, device, listOf(kp(1), kp(2)), now))
            assertEquals(1, repository.insert(db, device, listOf(kp(2), kp(3)), now), "the stored one is skipped")
            assertEquals(0, repository.insert(db, other, listOf(kp(1)), now), "the same bytes are one KeyPackage, whoever holds them")
            assertEquals(3, repository.countAll(db, device))
            assertEquals(0, repository.countAll(db, other))
        }

    @Test
    fun claimTakesTheBestSuiteThenTheOldestAndDeletesTheRow() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            repository.insert(db, device, listOf(kp(1, suite = 2)), now)
            repository.insert(db, device, listOf(kp(2, suite = 1)), now.plusSeconds(10))
            repository.insert(db, device, listOf(kp(3, suite = 1)), now.plusSeconds(20))
            val floor = now.plus(Duration.ofDays(1))

            assertContentEquals(bytes(2), repository.claimOne(db, device, listOf(1, 2), floor), "suite 1 is wanted first, oldest first")
            assertContentEquals(bytes(1), repository.claimOne(db, device, listOf(2, 1), floor), "now suite 2 is wanted first")
            assertContentEquals(bytes(3), repository.claimOne(db, device, listOf(2, 1), floor), "then what is left")
            assertNull(repository.claimOne(db, device, listOf(1, 2), floor), "an empty pool is null")
            assertEquals(0, rows(db), "a claim deletes the row: nothing at rest says it was ever claimed")
        }

    @Test
    fun claimOnlyReturnsWantedSuitesAndOnlyThoseThatDoNotExpireSoon() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            val floor = now.plus(Duration.ofDays(1))
            repository.insert(db, device, listOf(kp(1, suite = 3), kp(2, notAfter = now.plus(Duration.ofHours(12)))), now)
            assertNull(repository.claimOne(db, device, listOf(1, 2), floor), "suite 3 not wanted, the suite-1 one expires within a day")
            repository.insert(db, device, listOf(kp(3, notAfter = now.plus(Duration.ofDays(3)))), now)
            assertContentEquals(bytes(3), repository.claimOne(db, device, listOf(1), floor))
            assertEquals(2, rows(db), "the two that were skipped are still there")
        }

    @Test
    fun claimOnlyTouchesTheGivenDevice() =
        withApp { db ->
            val (_, ann) = deviceOf(db, "ann")
            val (_, bob) = deviceOf(db, "bob")
            repository.insert(db, bob, listOf(kp(1)), now)
            assertNull(repository.claimOne(db, ann, listOf(1), now))
            assertNotNull(repository.claimOne(db, bob, listOf(1), now))
        }

    @Test
    fun invalidArgumentsNeverReachTheDatabase() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            listOf(emptyList(), listOf(1, 1), listOf(0), listOf(65_536), listOf(1, 2, 3, 4, 5)).forEach { suites ->
                assertFailsWith<IllegalArgumentException>(suites.toString()) { repository.claimOne(db, device, suites, now) }
            }
            assertFailsWith<IllegalArgumentException> { NewKeyPackage(ByteArray(0), 1, now) }
            assertFailsWith<IllegalArgumentException> { NewKeyPackage(ByteArray(2049), 1, now) }
            assertFailsWith<IllegalArgumentException> { NewKeyPackage(ByteArray(10), 0, now) }
            assertFailsWith<IllegalArgumentException> { NewKeyPackage(ByteArray(10), 65_536, now) }
            assertFailsWith<IllegalArgumentException> { repository.deviceIds(db, UUID.randomUUID(), 0) }
            assertFailsWith<IllegalArgumentException> { repository.deleteExpired(db, now, 0) }
        }

    @Test
    fun theTwoCountsDifferByTheExpiredRows() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            repository.insert(db, device, listOf(kp(1), kp(2, notAfter = now.minusSeconds(1)), kp(3, notAfter = now)), now)
            assertEquals(3, repository.countAll(db, device))
            assertEquals(1, repository.countUnexpired(db, device, now), "expired at now means expired")
        }

    @Test
    fun deviceIdsAreOldestFirstAndBounded() =
        withApp { db ->
            val account = UUID.randomUUID().also { accounts.insert(db, it, "ann", now, Joined.ViaAdmin) }
            val ids = List(3) { UUID.randomUUID() }
            ids.forEachIndexed {
                i,
                id,
                ->
                devices.insert(db, id, account, ByteArray(32) { (i * 7 + it).toByte() }, now.plusSeconds(i.toLong()))
            }
            assertEquals(ids, repository.deviceIds(db, account, 10))
            assertEquals(ids.take(2), repository.deviceIds(db, account, 2))
            assertEquals(emptyList(), repository.deviceIds(db, UUID.randomUUID(), 10))
        }

    @Test
    fun deleteExpiredIsBoundedAndKeepsLiveRows() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            repository.insert(db, device, List(7) { kp(it, notAfter = now.minusSeconds(it + 1L)) } + List(3) { kp(100 + it) }, now)
            assertEquals(5, repository.deleteExpired(db, now, 5))
            assertEquals(2, repository.deleteExpired(db, now, 5))
            assertEquals(0, repository.deleteExpired(db, now, 5))
            assertEquals(3, repository.countAll(db, device))
        }

    @Test
    fun rowsGoWhenTheirDeviceOrAccountGoes() =
        withApp { db ->
            val (annAccount, ann) = deviceOf(db, "ann")
            val (_, bob) = deviceOf(db, "bob")
            repository.insert(db, ann, listOf(kp(1)), now)
            repository.insert(db, bob, listOf(kp(2)), now)
            db.createStatement().use { it.execute("DELETE FROM accounts WHERE id = '$annAccount'") }
            assertEquals(1, rows(db), "deleting the account removes its device and the device's KeyPackages")
            db.createStatement().use { it.execute("DELETE FROM devices WHERE id = '$bob'") }
            assertEquals(0, rows(db))
        }

    @Test
    fun lockDeviceIsFalseForAnUnknownDevice() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            assertTrue(repository.lockDevice(db, device))
            assertFalse(repository.lockDevice(db, UUID.randomUUID()))
        }

    @Test
    fun theDatabaseRefusesWhatTheCodeNeverSends() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")

            fun insert(
                suite: Int,
                bytes: ByteArray,
            ) = db.prepareStatement("INSERT INTO key_packages VALUES (?, ?, ?, ?, ?, ?)").use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, device)
                it.setInt(3, suite)
                it.setBytes(4, bytes)
                it.setObject(5, farFuture.atOffset(java.time.ZoneOffset.UTC))
                it.setObject(6, now.atOffset(java.time.ZoneOffset.UTC))
                it.executeUpdate()
            }
            val marker = ByteArray(2049) { 0xab.toByte() }
            listOf(0 to marker.copyOf(40), 65_536 to marker.copyOf(40), 1 to ByteArray(0), 1 to marker).forEach { (suite, kp) ->
                val e = assertFailsWith<SQLException>("suite $suite, ${kp.size} bytes") { insert(suite, kp) }
                assertEquals("23514", e.sqlState, "check_violation")
                // The driver is configured with logServerErrorDetail=false, so the failing row is not in the message.
                assertFalse(e.message.orEmpty().contains("abab", ignoreCase = true), "no key bytes in the message")
                assertFalse(e.message.orEmpty().contains("\\x"), "no bytea literal in the message")
            }
            insert(1, bytes(1))
            insert(65_535, bytes(2))
            assertEquals("23505", assertFailsWith<SQLException> { insert(1, bytes(1)) }.sqlState, "unique_violation")
        }

    @Test
    fun aClaimThatRollsBackLeavesTheRowToBeClaimedAgain() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            repository.insert(db, device, listOf(kp(1)), now)
            db.autoCommit = false
            assertContentEquals(bytes(1), repository.claimOne(db, device, listOf(1), now))
            db.rollback()
            db.autoCommit = true
            assertContentEquals(bytes(1), repository.claimOne(db, device, listOf(1), now), "a claim that did not commit took nothing")
        }

    @Test
    fun aKeyPackageExpiringExactlyAtTheFloorIsNotClaimed() =
        withApp { db ->
            val (_, device) = deviceOf(db, "ann")
            val floor = now.plus(Duration.ofDays(1))
            repository.insert(db, device, listOf(kp(1, notAfter = floor), kp(2, notAfter = floor.plusSeconds(1))), now)
            assertContentEquals(bytes(2), repository.claimOne(db, device, listOf(1), floor), "strictly after the floor")
            assertNull(repository.claimOne(db, device, listOf(1), floor))
        }

    /** The cleanup never waits for a row another transaction holds, so it cannot deadlock with a cascade or a claim. */
    @Test
    fun theCleanupSkipsRowsAnotherTransactionHoldsAndTakesThemNextTime() {
        val url = TestPostgres.migratedDatabase()
        val (setup, holder, cleaner) = List(3) { TestPostgres.appAt(url) }
        try {
            val (_, device) = deviceOf(setup, "ann")
            repository.insert(setup, device, List(3) { kp(it, notAfter = now.minusSeconds(10L + it)) }, now)
            cleaner.createStatement().use { it.execute("SET lock_timeout = '3s'") } // a wait would fail the test, not hang it
            holder.autoCommit = false
            holder.createStatement().use {
                it.executeQuery("SELECT id FROM key_packages ORDER BY id LIMIT 1 FOR UPDATE").use { r ->
                    r.next()
                }
            }
            assertEquals(2, repository.deleteExpired(cleaner, now, 10), "the held row is skipped, not waited for")
            holder.rollback()
            assertEquals(1, repository.deleteExpired(cleaner, now, 10), "next time it is taken")
        } finally {
            listOf(setup, holder, cleaner).forEach(Connection::close)
        }
    }

    /** Uploads that share KeyPackages in opposite order, on two devices: inserting in a fixed order means no deadlock. */
    @Test
    fun uploadsSharingKeyPackagesInOppositeOrderNeverDeadlock() {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val (first, second) =
                db.transaction { c ->
                    deviceOf(c, "ann").second to deviceOf(c, "bob").second
                }
            repeat(60) { round ->
                val a = kp(round * 2)
                val b = kp(round * 2 + 1)
                val counts = ConcurrentLinkedQueue<Int>()
                race(2) { index ->
                    val device = if (index == 0) first else second
                    val items = if (index == 0) listOf(a, b) else listOf(b, a)
                    counts.add(db.transaction { c -> repository.insert(c, device, items, now) })
                }
                assertEquals(2, counts.sum(), "round $round: each of the two KeyPackages was stored once, by one of the devices")
            }
        }
    }

    /** One claim of the last KeyPackage under eight racing claimers: exactly one gets it (single use, RFC 9420 §16.8). */
    @Test
    fun aRaceForTheLastKeyPackageHasExactlyOneWinner() =
        withDatabase { db, device ->
            db.transaction { c -> repository.insert(c, device, listOf(kp(1)), now) }
            val results = ConcurrentLinkedQueue<List<Byte>>()
            race(8) { results.add(db.transaction { c -> repository.claimOne(c, device, listOf(1), now) }.orEmptyList()) }
            assertEquals(1, results.count { it.isNotEmpty() })
            assertEquals(0, db.transaction { c -> repository.countAll(c, device) })
        }

    /** Two claimers and three rows: both get one, never the same, never a false "empty" (SKIP LOCKED under LIMIT 1). */
    @Test
    fun twoRacingClaimersBothGetAKeyPackageWhenThreeRemain() =
        withDatabase { db, device ->
            repeat(25) { round ->
                db.transaction { c -> repository.insert(c, device, List(3) { kp(round * 10 + it) }, now) }
                val results = ConcurrentLinkedQueue<List<Byte>>()
                race(2) { results.add(db.transaction { c -> repository.claimOne(c, device, listOf(1), now) }.orEmptyList()) }
                assertEquals(2, results.count { it.isNotEmpty() }, "round $round: no false empty")
                assertEquals(2, results.distinct().size, "round $round: never the same row twice")
                db.transaction { c -> repository.claimOne(c, device, listOf(1), now) }
            }
        }

    /** The cap logic of an upload (lock, count, insert) under racing uploads: the lock makes them one after the other. */
    @Test
    fun racingUploadsNeverOvershootTheCap() =
        withDatabase { db, device ->
            val cap = 200
            val accepted = ConcurrentLinkedQueue<Int>()
            race(8) { thread ->
                db.transaction { c ->
                    assertTrue(repository.lockDevice(c, device))
                    val held = repository.countUnexpired(c, device, now)
                    Thread.sleep(30) // the window in which an unlocked count goes stale
                    if (held + 50 <= cap) {
                        repository.insert(c, device, List(50) { kp(thread * 100 + it) }, now)
                        accepted.add(thread)
                    }
                }
            }
            assertEquals(4, accepted.size, "4 batches of 50 fit in 200; the rest are refused")
            assertEquals(cap, db.transaction { c -> repository.countAll(c, device) })
        }

    private fun ByteArray?.orEmptyList(): List<Byte> = this?.toList() ?: emptyList()

    private fun withDatabase(block: (Database, UUID) -> Unit) {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val device =
                db.transaction { c ->
                    val account = UUID.randomUUID().also { accounts.insert(c, it, "ann", now, Joined.ViaAdmin) }
                    UUID.randomUUID().also { devices.insert(c, it, account, ByteArray(32) { i -> i.toByte() }, now) }
                }
            block(db, device)
        }
    }

    /** Starts [threads] threads together; fails if any of them failed. */
    private fun race(
        threads: Int,
        body: (Int) -> Unit,
    ) {
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val running =
            List(threads) { index ->
                Thread {
                    start.await()
                    runCatching { body(index) }.onFailure { failures.add(it) }
                }.apply { start() }
            }
        start.countDown()
        running.forEach { it.join() }
        assertTrue(failures.isEmpty(), failures.joinToString { it.toString() })
    }
}
