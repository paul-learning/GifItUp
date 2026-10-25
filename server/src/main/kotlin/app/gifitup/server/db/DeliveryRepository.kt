// JDBC parameter indices (setObject(3, …)) are positions in the SQL right next to them, not magic numbers; the table has
// one method per statement and a few take one value per column.
@file:Suppress("MagicNumber", "TooManyFunctions", "LongParameterList")

package app.gifitup.server.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.sql.Array as SqlArray

/** A group's counters (ADR-0015). [creator] is null once the creating device is gone. */
class GroupRow(
    val epoch: Long,
    val nextSeq: Long,
    val retainedBytes: Long,
    val creator: UUID?,
)

/** One member's cursors (ADR-0015). */
class GroupMemberRow(
    val joinedSeq: Long,
    val ackedSeq: Long,
    val lastSentSeq: Long?,
    val lastClientMsgId: ByteArray?,
)

/** A retained message as stored. [kind] is [DeliveryRepository.COMMIT] or [DeliveryRepository.APPLICATION]. */
class StoredMessage(
    val seq: Long,
    val sender: UUID?,
    val kind: Int,
    val epoch: Long?,
    val added: List<UUID>,
    val removed: List<UUID>,
    val payload: ByteArray,
)

/** A message to append. [payloadHash] is SHA-256 over the canonical envelope; it is only used to tell a retry from a clash. */
class NewMessage(
    val seq: Long,
    val sender: UUID,
    val kind: Int,
    val epoch: Long?,
    val added: List<UUID>,
    val removed: List<UUID>,
    val clientMsgId: ByteArray,
    val payloadHash: ByteArray,
    val payload: ByteArray,
)

/** What a retry lookup finds: the first message's seq, kind, commit epoch and envelope hash. */
class SentMessage(
    val seq: Long,
    val kind: Int,
    val epoch: Long?,
    val hash: ByteArray,
)

/** A pending Welcome for its target device. */
class WelcomeRow(
    val id: UUID,
    val groupId: ByteArray,
    val welcome: ByteArray,
)

/**
 * The Delivery Service's tables (ADR-0015, V4). Methods take the caller's connection, so the service composes them in
 * one transaction. Prepared statements only. A write to one group runs under [lockGroup] (`FOR UPDATE` on the group
 * row), which serializes posts, so seq order is commit order and nothing reads a gap.
 *
 * Lock order: the group row first, then member, message and welcome rows. Device deletion cascades from `devices` and
 * only ever takes device-side rows first, never a group row, so the two cannot wait for each other in a cycle.
 */
class DeliveryRepository {
    /** Inserts the group (epoch 0, first seq 1); false if the id is taken. */
    fun insertGroup(
        c: Connection,
        id: ByteArray,
        creator: UUID,
        now: Instant,
    ): Boolean =
        c
            .prepareStatement(
                "INSERT INTO groups (id, creator_device_id, epoch, next_seq, retained_bytes, created_at) VALUES (?, ?, 0, 1, 0, ?) " +
                    "ON CONFLICT DO NOTHING RETURNING id",
            ).use {
                it.setBytes(1, id)
                it.setObject(2, creator)
                it.setObject(3, now.utc())
                it.executeQuery().use { rows -> rows.next() }
            }

    /** The group's row, locked for the rest of the transaction; null if there is no such group. */
    fun lockGroup(
        c: Connection,
        id: ByteArray,
    ): GroupRow? =
        c.prepareStatement("SELECT epoch, next_seq, retained_bytes, creator_device_id FROM groups WHERE id = ? FOR UPDATE").use {
            it.setBytes(1, id)
            it.executeQuery().use { rows ->
                if (rows.next()) GroupRow(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getObject(4, UUID::class.java)) else null
            }
        }

    /** The group's row without a lock (reads). */
    fun group(
        c: Connection,
        id: ByteArray,
    ): GroupRow? =
        c.prepareStatement("SELECT epoch, next_seq, retained_bytes, creator_device_id FROM groups WHERE id = ?").use {
            it.setBytes(1, id)
            it.executeQuery().use { rows ->
                if (rows.next()) GroupRow(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getObject(4, UUID::class.java)) else null
            }
        }

    fun member(
        c: Connection,
        group: ByteArray,
        device: UUID,
    ): GroupMemberRow? =
        c
            .prepareStatement(
                "SELECT joined_seq, acked_seq, last_sent_seq, last_client_msg_id FROM group_members WHERE group_id = ? AND device_id = ?",
            ).use {
                it.setBytes(1, group)
                it.setObject(2, device)
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        GroupMemberRow(rows.getLong(1), rows.getLong(2), rows.getObject(3) as Long?, rows.getBytes(4))
                    } else {
                        null
                    }
                }
            }

    fun memberDevices(
        c: Connection,
        group: ByteArray,
    ): Set<UUID> =
        c.prepareStatement("SELECT device_id FROM group_members WHERE group_id = ?").use {
            it.setBytes(1, group)
            it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }

    /** The subset of [ids] that are existing devices. */
    fun existingDevices(
        c: Connection,
        ids: Collection<UUID>,
    ): Set<UUID> =
        c.prepareStatement("SELECT id FROM devices WHERE id = ANY (?)").use {
            it.setArray(1, c.uuidArray(ids))
            it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }

    fun insertMember(
        c: Connection,
        group: ByteArray,
        device: UUID,
        joinedSeq: Long,
        addedBy: UUID?,
        now: Instant,
    ) {
        c
            .prepareStatement(
                "INSERT INTO group_members (group_id, device_id, joined_seq, acked_seq, last_acked_at, added_by_device_id, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setBytes(1, group)
                it.setObject(2, device)
                it.setLong(3, joinedSeq)
                it.setLong(4, joinedSeq)
                it.setObject(5, now.utc())
                it.setObject(6, addedBy)
                it.setObject(7, now.utc())
                it.executeUpdate()
            }
    }

    fun deleteMembers(
        c: Connection,
        group: ByteArray,
        devices: Collection<UUID>,
    ): Int =
        c.prepareStatement("DELETE FROM group_members WHERE group_id = ? AND device_id = ANY (?)").use {
            it.setBytes(1, group)
            it.setArray(2, c.uuidArray(devices))
            it.executeUpdate()
        }

    /** Deletes the group when no member is left (its messages and welcomes cascade). */
    fun deleteGroupIfEmpty(
        c: Connection,
        group: ByteArray,
    ) {
        c
            .prepareStatement("DELETE FROM groups g WHERE g.id = ? AND NOT EXISTS (SELECT 1 FROM group_members m WHERE m.group_id = g.id)")
            .use {
                it.setBytes(1, group)
                it.executeUpdate()
            }
    }

    /** The message this device already sent with [clientMsgId], if it is still retained. */
    fun sent(
        c: Connection,
        group: ByteArray,
        sender: UUID,
        clientMsgId: ByteArray,
    ): SentMessage? =
        c
            .prepareStatement(
                "SELECT seq, kind, epoch, payload_hash FROM group_messages " +
                    "WHERE group_id = ? AND sender_device_id = ? AND client_msg_id = ?",
            ).use {
                it.setBytes(1, group)
                it.setObject(2, sender)
                it.setBytes(3, clientMsgId)
                it.executeQuery().use { rows ->
                    if (rows.next()) SentMessage(rows.getLong(1), rows.getInt(2), rows.getObject(3) as Long?, rows.getBytes(4)) else null
                }
            }

    fun appendMessage(
        c: Connection,
        group: ByteArray,
        message: NewMessage,
        now: Instant,
    ) {
        c
            .prepareStatement(
                "INSERT INTO group_messages (group_id, seq, sender_device_id, kind, epoch, added, removed, client_msg_id, " +
                    "payload_hash, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setBytes(1, group)
                it.setLong(2, message.seq)
                it.setObject(3, message.sender)
                it.setInt(4, message.kind)
                it.setObject(5, message.epoch)
                it.setArray(6, c.uuidArray(message.added))
                it.setArray(7, c.uuidArray(message.removed))
                it.setBytes(8, message.clientMsgId)
                it.setBytes(9, message.payloadHash)
                it.setBytes(10, message.payload)
                it.setObject(11, now.utc())
                it.executeUpdate()
            }
    }

    /** Moves the group on after an append: the next seq, the epoch (a commit) and the retained bytes. */
    fun advanceGroup(
        c: Connection,
        group: ByteArray,
        nextSeq: Long,
        epoch: Long,
        retainedBytes: Long,
    ) {
        c.prepareStatement("UPDATE groups SET next_seq = ?, epoch = ?, retained_bytes = ? WHERE id = ?").use {
            it.setLong(1, nextSeq)
            it.setLong(2, epoch)
            it.setLong(3, retainedBytes)
            it.setBytes(4, group)
            it.executeUpdate()
        }
    }

    fun recordSend(
        c: Connection,
        group: ByteArray,
        device: UUID,
        seq: Long,
        clientMsgId: ByteArray,
    ) {
        c.prepareStatement("UPDATE group_members SET last_sent_seq = ?, last_client_msg_id = ? WHERE group_id = ? AND device_id = ?").use {
            it.setLong(1, seq)
            it.setBytes(2, clientMsgId)
            it.setBytes(3, group)
            it.setObject(4, device)
            it.executeUpdate()
        }
    }

    /** Messages above [after], oldest first, at most [limit]. */
    fun messages(
        c: Connection,
        group: ByteArray,
        after: Long,
        limit: Int,
    ): List<StoredMessage> =
        c
            .prepareStatement(
                "SELECT seq, sender_device_id, kind, epoch, added, removed, payload FROM group_messages " +
                    "WHERE group_id = ? AND seq > ? ORDER BY seq LIMIT ?",
            ).use {
                it.setBytes(1, group)
                it.setLong(2, after)
                it.setInt(3, limit)
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                StoredMessage(
                                    rows.getLong(1),
                                    rows.getObject(2, UUID::class.java),
                                    rows.getInt(3),
                                    rows.getObject(4) as Long?,
                                    rows.uuids(5),
                                    rows.uuids(6),
                                    rows.getBytes(7),
                                ),
                            )
                        }
                    }
                }
            }

    /** The first retained seq, or null if the log is empty. */
    fun oldestSeq(
        c: Connection,
        group: ByteArray,
    ): Long? = c.scalar("SELECT min(seq) FROM group_messages WHERE group_id = ?") { setBytes(1, group) }

    /** The group's retained message count and payload bytes, summed live (the counter column can lag a cleanup). */
    fun retained(
        c: Connection,
        group: ByteArray,
    ): Pair<Long, Long> =
        c.prepareStatement("SELECT count(*), coalesce(sum(octet_length(payload)), 0) FROM group_messages WHERE group_id = ?").use {
            it.setBytes(1, group)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1) to rows.getLong(2)
            }
        }

    /** Retained payload bytes of everything [sender] posted, across groups. */
    fun bytesBySender(
        c: Connection,
        sender: UUID,
    ): Long =
        c.scalar("SELECT coalesce(sum(octet_length(payload)), 0) FROM group_messages WHERE sender_device_id = ?") {
            setObject(1, sender)
        } ?: 0L

    /** Moves the member's ack forward (never back). Only an advance counts as being alive: a repeated ack changes nothing. */
    fun ack(
        c: Connection,
        group: ByteArray,
        device: UUID,
        seq: Long,
        now: Instant,
    ) {
        c
            .prepareStatement(
                "UPDATE group_members SET acked_seq = ?, last_acked_at = ? WHERE group_id = ? AND device_id = ? AND acked_seq < ?",
            ).use {
                it.setLong(1, seq)
                it.setObject(2, now.utc())
                it.setBytes(3, group)
                it.setObject(4, device)
                it.setLong(5, seq)
                it.executeUpdate()
            }
    }

    /** Live groups this device created. */
    fun createdGroups(
        c: Connection,
        device: UUID,
    ): Long = c.scalar("SELECT count(*) FROM groups WHERE creator_device_id = ?") { setObject(1, device) } ?: 0L

    fun memberships(
        c: Connection,
        device: UUID,
    ): Long = c.scalar("SELECT count(*) FROM group_members WHERE device_id = ?") { setObject(1, device) } ?: 0L

    /**
     * How often [adder] added [target] since [since] (any group), counted from the commits it posted, so removing and
     * re-adding does not reset the count.
     */
    fun recentAdds(
        c: Connection,
        adder: UUID,
        target: UUID,
        since: Instant,
    ): Long =
        c.scalar("SELECT count(*) FROM group_messages WHERE sender_device_id = ? AND ? = ANY (added) AND created_at > ?") {
            setObject(1, adder)
            setObject(2, target)
            setObject(3, since.utc())
        } ?: 0L

    // Welcomes

    fun insertWelcome(
        c: Connection,
        id: UUID,
        group: ByteArray,
        target: UUID,
        adder: UUID,
        welcome: ByteArray,
        now: Instant,
    ) {
        c
            .prepareStatement(
                "INSERT INTO welcomes (id, group_id, target_device_id, adder_device_id, welcome, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use {
                it.setObject(1, id)
                it.setBytes(2, group)
                it.setObject(3, target)
                it.setObject(4, adder)
                it.setBytes(5, welcome)
                it.setObject(6, now.utc())
                it.executeUpdate()
            }
    }

    fun pendingFor(
        c: Connection,
        target: UUID,
    ): Long = c.scalar("SELECT count(*) FROM welcomes WHERE target_device_id = ?") { setObject(1, target) } ?: 0L

    fun pendingFrom(
        c: Connection,
        adder: UUID,
        target: UUID,
    ): Long =
        c.scalar("SELECT count(*) FROM welcomes WHERE adder_device_id = ? AND target_device_id = ?") {
            setObject(1, adder)
            setObject(2, target)
        } ?: 0L

    fun pendingBytesFrom(
        c: Connection,
        adder: UUID,
    ): Long =
        c.scalar("SELECT coalesce(sum(octet_length(welcome)), 0) FROM welcomes WHERE adder_device_id = ?") {
            setObject(1, adder)
        } ?: 0L

    fun pendingBytes(c: Connection): Long = c.scalar("SELECT coalesce(sum(octet_length(welcome)), 0) FROM welcomes") {} ?: 0L

    fun welcomes(
        c: Connection,
        target: UUID,
        limit: Int,
    ): List<WelcomeRow> =
        c.prepareStatement("SELECT id, group_id, welcome FROM welcomes WHERE target_device_id = ? ORDER BY created_at, id LIMIT ?").use {
            it.setObject(1, target)
            it.setInt(2, limit)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(WelcomeRow(rows.getObject(1, UUID::class.java), rows.getBytes(2), rows.getBytes(3))) }
            }
        }

    /** Deletes the welcome if it is [target]'s; false if there is no such welcome for this device. */
    fun deleteWelcome(
        c: Connection,
        id: UUID,
        target: UUID,
    ): Boolean =
        c.prepareStatement("DELETE FROM welcomes WHERE id = ? AND target_device_id = ?").use {
            it.setObject(1, id)
            it.setObject(2, target)
            it.executeUpdate() > 0
        }

    // Retention (hourly cleanup)

    /**
     * Deletes messages every active member has acknowledged. A member that has not acknowledged since [activeSince]
     * stops holding messages back. Returns the rows deleted.
     */
    fun deleteAcked(
        c: Connection,
        activeSince: Instant,
    ): Int =
        c
            .prepareStatement(
                "DELETE FROM group_messages gm USING (SELECT group_id, min(acked_seq) AS floor FROM group_members " +
                    "WHERE last_acked_at >= ? GROUP BY group_id) f WHERE gm.group_id = f.group_id AND gm.seq <= f.floor",
            ).use {
                it.setObject(1, activeSince.utc())
                it.executeUpdate()
            }

    fun deleteMessagesBefore(
        c: Connection,
        before: Instant,
    ): Int =
        c.prepareStatement("DELETE FROM group_messages WHERE created_at < ?").use {
            it.setObject(1, before.utc())
            it.executeUpdate()
        }

    fun deleteWelcomesBefore(
        c: Connection,
        before: Instant,
    ): Int =
        c.prepareStatement("DELETE FROM welcomes WHERE created_at < ?").use {
            it.setObject(1, before.utc())
            it.executeUpdate()
        }

    fun deleteEmptyGroups(c: Connection): Int =
        c.prepareStatement("DELETE FROM groups g WHERE NOT EXISTS (SELECT 1 FROM group_members m WHERE m.group_id = g.id)").use {
            it.executeUpdate()
        }

    /** Recomputes every group's retained bytes from its messages (they drift down as cleanup deletes). */
    fun recountRetainedBytes(c: Connection): Int =
        c
            .prepareStatement(
                "UPDATE groups g SET retained_bytes = coalesce((SELECT sum(octet_length(payload)) FROM group_messages m " +
                    "WHERE m.group_id = g.id), 0) WHERE g.retained_bytes <> coalesce((SELECT sum(octet_length(payload)) " +
                    "FROM group_messages m WHERE m.group_id = g.id), 0)",
            ).use { it.executeUpdate() }

    private fun Connection.uuidArray(ids: Collection<UUID>): SqlArray = createArrayOf("uuid", ids.toTypedArray())

    private fun ResultSet.uuids(column: Int): List<UUID> = (getArray(column).array as kotlin.Array<*>).map { it as UUID }

    private fun Connection.scalar(
        sql: String,
        bind: PreparedStatement.() -> Unit,
    ): Long? =
        prepareStatement(sql).use { statement ->
            statement.bind()
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1) as Long? else null }
        }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    companion object {
        const val COMMIT = 1
        const val APPLICATION = 2
    }
}
