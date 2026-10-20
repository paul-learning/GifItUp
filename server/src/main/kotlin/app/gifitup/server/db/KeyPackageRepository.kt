@file:Suppress("MagicNumber") // JDBC parameter indices.

package app.gifitup.server.db

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A KeyPackage the reader accepted, ready to store (ADR-0012). [ciphersuite] and [notAfter] come from its bytes. */
class NewKeyPackage(
    val bytes: ByteArray,
    val ciphersuite: Int,
    val notAfter: Instant,
) {
    init {
        require(bytes.size in 1..KeyPackageRepository.MAX_BYTES) { "key package size out of range" }
        require(ciphersuite in MIN_SUITE..MAX_SUITE) { "ciphersuite out of range" }
    }

    private companion object {
        const val MIN_SUITE = 1
        const val MAX_SUITE = 65_535
    }
}

/**
 * The KeyPackage directory's table (ADR-0012, V3). Methods take the caller's connection, so a service composes several
 * in one transaction. Prepared statements only; values are checked before they are bound, so PostgreSQL never has a bad
 * value to quote back in an error.
 *
 * Transactions run at READ COMMITTED (pinned in [Database.connect]); "never a false empty" holds for committed claims.
 *
 * Lock order (ADR-0012): an upload takes the device row ([lockDevice]) and then key_packages rows; a claim and the cleanup
 * take key_packages rows only, never the device row, and never wait for a row another transaction holds (`SKIP LOCKED`),
 * so no transaction takes a device lock after a key_packages lock and neither can deadlock with the other or with the
 * `ON DELETE CASCADE` of a device or account delete. An upload inserts its items in a fixed order (by bytes), so two
 * uploads that share KeyPackages wait on each other's unique-index entries in the same order and cannot deadlock.
 */
class KeyPackageRepository {
    /**
     * Locks the device's row for the rest of the transaction, so concurrent uploads for one device count and insert one
     * after the other (cap enforcement). `FOR NO KEY UPDATE` doesn't block foreign-key checks. False if the device is gone.
     */
    fun lockDevice(
        connection: Connection,
        deviceId: UUID,
    ): Boolean =
        connection.prepareStatement("SELECT 1 FROM devices WHERE id = ? FOR NO KEY UPDATE").use {
            it.setObject(1, deviceId)
            it.executeQuery().use { rows -> rows.next() }
        }

    /** The device's KeyPackages that have not expired at [now]. A separate statement after [lockDevice] (READ COMMITTED). */
    fun countUnexpired(
        connection: Connection,
        deviceId: UUID,
        now: Instant,
    ): Int = connection.count("SELECT count(*) FROM key_packages WHERE device_id = ? AND not_after > ?", deviceId, now)

    /** All of the device's rows, expired ones not yet cleaned up included (the total cap). */
    fun countAll(
        connection: Connection,
        deviceId: UUID,
    ): Int = connection.count("SELECT count(*) FROM key_packages WHERE device_id = ?", deviceId, null)

    /**
     * Stores [items] for the device and returns how many were new. A KeyPackage that is already stored (the same bytes)
     * is skipped, not an error: the caller treats a short count as a duplicate and rolls the batch back. One statement per
     * item, so the count is exact (a JDBC batch may report "no info").
     */
    fun insert(
        connection: Connection,
        deviceId: UUID,
        items: List<NewKeyPackage>,
        now: Instant,
    ): Int =
        connection
            .prepareStatement(
                "INSERT INTO key_packages (id, device_id, ciphersuite, key_package, not_after, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (key_package) DO NOTHING",
            ).use { statement ->
                items.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.bytes, b.bytes) }.sumOf { item ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, deviceId)
                    statement.setInt(3, item.ciphersuite)
                    statement.setBytes(4, item.bytes)
                    statement.setObject(5, item.notAfter.utc())
                    statement.setObject(6, now.utc())
                    statement.executeUpdate()
                }
            }

    /**
     * Takes one KeyPackage of the device and deletes its row: the best-ranked suite in [suites] (most wanted first), then
     * the oldest, among those that expire after [notAfterAfter]. `FOR UPDATE SKIP LOCKED` under `LIMIT 1` skips rows another
     * claim holds and takes the next, so two concurrent claims never get the same row and never see a false "empty" while
     * rows remain. Null if the device has none. [notAfterAfter] must not be before the cleanup's `now`, so the rows a claim
     * may take and the rows the cleanup deletes stay disjoint.
     */
    fun claimOne(
        connection: Connection,
        deviceId: UUID,
        suites: List<Int>,
        notAfterAfter: Instant,
    ): ByteArray? {
        require(suites.isNotEmpty() && suites.size <= MAX_SUITES && suites.distinct().size == suites.size) { "1..4 distinct suites" }
        require(suites.all { it in 1..65_535 }) { "suite out of range" }
        val ranked = connection.createArrayOf("integer", suites.toTypedArray())
        try {
            return deleteBest(connection, deviceId, ranked, notAfterAfter)
        } finally {
            ranked.free()
        }
    }

    private fun deleteBest(
        connection: Connection,
        deviceId: UUID,
        ranked: java.sql.Array,
        notAfterAfter: Instant,
    ): ByteArray? =
        connection
            .prepareStatement(
                "DELETE FROM key_packages WHERE id = (" +
                    "SELECT id FROM key_packages WHERE device_id = ? AND ciphersuite = ANY(?) AND not_after > ? " +
                    "ORDER BY array_position(?, ciphersuite), created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) " +
                    "RETURNING key_package",
            ).use {
                it.setObject(1, deviceId)
                it.setArray(2, ranked)
                it.setObject(3, notAfterAfter.utc())
                it.setArray(4, ranked)
                it.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
            }

    /** The account's device ids, oldest first, at most [limit] (the claim response bound, ADR-0012). */
    fun deviceIds(
        connection: Connection,
        accountId: UUID,
        limit: Int,
    ): List<UUID> {
        require(limit in 1..MAX_DEVICES) { "limit out of range" }
        return connection.prepareStatement("SELECT id FROM devices WHERE account_id = ? ORDER BY created_at, id LIMIT ?").use {
            it.setObject(1, accountId)
            it.setInt(2, limit)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
    }

    /**
     * Deletes up to [limit] rows that expired before [now]; returns how many. Called in bounded batches (ADR-0012). Rows
     * another transaction holds are skipped and left for the next pass (never waited for).
     */
    fun deleteExpired(
        connection: Connection,
        now: Instant,
        limit: Int,
    ): Int {
        require(limit > 0) { "limit must be positive" }
        return connection
            .prepareStatement(
                "DELETE FROM key_packages WHERE id IN (SELECT id FROM key_packages WHERE not_after < ? " +
                    "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)",
            ).use {
                it.setObject(1, now.utc())
                it.setInt(2, limit)
                it.executeUpdate()
            }
    }

    private fun Connection.count(
        sql: String,
        deviceId: UUID,
        after: Instant?,
    ): Int =
        prepareStatement(sql).use {
            it.setObject(1, deviceId)
            if (after != null) it.setObject(2, after.utc())
            it.executeQuery().use { rows: ResultSet ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    companion object {
        /** The reader's size limit (`KeyPackageReader.MAX_BYTES`) and the table's CHECK. */
        const val MAX_BYTES = 2048
        const val MAX_SUITES = 4
        const val MAX_DEVICES = 10
    }
}
