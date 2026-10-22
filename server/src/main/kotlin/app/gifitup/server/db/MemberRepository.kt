@file:Suppress("MagicNumber") // JDBC parameter and column positions, as in InviteRepository.

package app.gifitup.server.db

import java.sql.Connection
import java.util.UUID

/** An account next to the caller in the invite tree; [invitedByCaller] is false for the caller's inviter. */
class MemberRow(
    val accountId: UUID,
    val username: String,
    val invitedByCaller: Boolean,
    val devices: List<MemberDeviceRow>,
)

class MemberDeviceRow(
    val deviceId: UUID,
    val signatureKey: ByteArray,
)

/** Reads the invite tree edges around one account (ADR-0014). Read-only; nothing here is stored. */
class MemberRepository {
    /**
     * The caller's inviter (if the caller has one and it still exists) and the accounts the caller invited: the inviter
     * first (`invited_by` is NULL for a root inviter, so the flag is coalesced: NULL would sort last and read as false), then by username in byte order, ties by id, so the cut at [limit] is the same on every request. At most
     * [limit] rows. Each member carries its first [MAX_DEVICES] devices, oldest first. Two statements, no per-member
     * query.
     */
    fun neighbours(
        connection: Connection,
        callerId: UUID,
        limit: Int,
    ): List<MemberRow> {
        require(limit in 1..MAX_LIMIT) { "limit out of range" }
        val accounts = accounts(connection, callerId, limit)
        if (accounts.isEmpty()) return emptyList()
        val devices = devices(connection, accounts.map { it.first })
        return accounts.map { (id, username, byCaller) -> MemberRow(id, username, byCaller, devices[id].orEmpty()) }
    }

    private fun accounts(
        connection: Connection,
        callerId: UUID,
        limit: Int,
    ): List<Triple<UUID, String, Boolean>> =
        connection
            .prepareStatement(
                "SELECT a.id, a.username, COALESCE(a.invited_by = ?, FALSE) AS by_caller FROM accounts a " +
                    "WHERE a.invited_by = ? OR a.id = (SELECT invited_by FROM accounts WHERE id = ?) " +
                    "ORDER BY by_caller, a.username COLLATE \"C\", a.id LIMIT ?",
            ).use {
                it.setObject(1, callerId)
                it.setObject(2, callerId)
                it.setObject(3, callerId)
                it.setInt(4, limit)
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Triple(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getBoolean(3)),
                            )
                        }
                    }
                }
            }

    private fun devices(
        connection: Connection,
        accountIds: List<UUID>,
    ): Map<UUID, List<MemberDeviceRow>> {
        val ids = connection.createArrayOf("uuid", accountIds.toTypedArray())
        try {
            return connection
                .prepareStatement(
                    "SELECT account_id, id, signature_key FROM (" +
                        "SELECT account_id, id, signature_key, " +
                        "row_number() OVER (PARTITION BY account_id ORDER BY created_at, id) AS n " +
                        "FROM devices WHERE account_id = ANY(?)) d WHERE n <= ? ORDER BY account_id, n",
                ).use {
                    it.setArray(1, ids)
                    it.setInt(2, MAX_DEVICES)
                    it.executeQuery().use { rows ->
                        buildMap<UUID, MutableList<MemberDeviceRow>> {
                            while (rows.next()) {
                                getOrPut(rows.getObject(1, UUID::class.java)) { mutableListOf() }
                                    .add(MemberDeviceRow(rows.getObject(2, UUID::class.java), rows.getBytes(3)))
                            }
                        }
                    }
                }
        } finally {
            ids.free()
        }
    }

    companion object {
        const val MAX_DEVICES = 10
        const val MAX_LIMIT = 1000
    }
}
