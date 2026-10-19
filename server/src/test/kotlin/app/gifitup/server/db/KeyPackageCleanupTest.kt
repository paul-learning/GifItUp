package app.gifitup.server.db

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hourly KeyPackage cleanup (ADR-0012): expired rows only, in bounded batches with a pause between them. */
class KeyPackageCleanupTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private val repository = KeyPackageRepository()

    private val clock =
        object : java.time.Clock() {
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this

            override fun instant(): Instant = now
        }

    private fun bytes(n: Int) = ByteArray(40) { if (it < 4) (n shr (8 * it)).toByte() else it.toByte() }

    @Test
    fun deletesOnlyExpiredRowsInBoundedBatchesAndPausesBetweenThem() {
        val url = TestPostgres.migratedDatabase()
        Database.connect(DatabaseConfig(url, APP_ROLE, TestPostgres.appPasswordFile())).use { db ->
            val device =
                db.transaction { c ->
                    val account = UUID.randomUUID().also { AccountRepository().insert(c, it, "ann", now, Joined.ViaAdmin) }
                    UUID.randomUUID().also { DeviceRepository().insert(c, it, account, ByteArray(32) { i -> i.toByte() }, now) }
                }
            db.transaction { c ->
                val expired = List(25) { NewKeyPackage(bytes(it), 1, now.minusSeconds(1L + it)) }
                val live = List(5) { NewKeyPackage(bytes(100 + it), 1, now.plus(Duration.ofDays(3))) }
                repository.insert(c, device, expired + live, now)
            }
            var pauses = 0
            val cleanup = KeyPackageCleanup(db, clock, repository, batchSize = 10) { pauses++ }

            assertEquals(25, cleanup.runOnce())
            assertEquals(2, pauses, "batches of 10, 10 and 5: a pause after each full batch, none after the last")
            assertEquals(5, db.transaction { c -> repository.countAll(c, device) }, "the live rows stay")
            assertEquals(0, cleanup.runOnce(), "nothing left to do")
            assertEquals(2, pauses)
        }
    }

    @Test
    fun anyFailureIsLoggedByClassAndTheNextPassTriesAgain() {
        listOf<() -> Throwable>({ java.sql.SQLException("secret detail") }, { IllegalStateException("secret detail") }).forEach { failure ->
            var calls = 0
            val failing =
                object : TransactionRunner {
                    override fun <T> transaction(block: (java.sql.Connection) -> T): T {
                        calls++
                        throw failure()
                    }
                }
            val cleanup = KeyPackageCleanup(failing, clock, repository, batchSize = 10) { error("no pause after a failure") }
            assertEquals(0, cleanup.runOnce())
            assertEquals(0, cleanup.runOnce(), "an exception does not end the cleanup")
            assertEquals(2, calls)
        }
    }
}
