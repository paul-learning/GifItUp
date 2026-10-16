// JDBC parameter indices (setObject(3, …)) are positions in the SQL right next to them, not magic numbers.
@file:Suppress("MagicNumber")

package app.gifitup.server.db

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A new invite: only the hash of its code; [createdBy] null = an admin invite. */
data class NewInvite(
    val id: UUID,
    val codeHash: ByteArray,
    val createdBy: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(codeHash.size == InviteRepository.CODE_HASH_SIZE) { "code hash must be 32 bytes" }
        require(expiresAt.isAfter(createdAt)) { "an invite must expire after it is created" }
    }

    override fun equals(other: Any?): Boolean = other is NewInvite && id == other.id

    override fun hashCode(): Int = id.hashCode()
}

/** A redeemed invite: which one, and who created it (`null` for an admin invite). */
data class RedeemedInvite(
    val inviteId: UUID,
    val inviter: UUID?,
)

/**
 * Invite storage (ADR-0010). Same rules as `Repositories.kt`: prepared statements, typed binds of validated values,
 * and the caller's connection so a use case runs several statements in one transaction. Only the SHA-256 of a code is
 * ever bound; the code itself never reaches the database.
 */
class InviteRepository {
    fun insert(
        connection: Connection,
        invite: NewInvite,
    ) {
        connection.prepareStatement("INSERT INTO invites (id, code_hash, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?)").use {
            it.setObject(1, invite.id)
            it.setBytes(2, invite.codeHash)
            it.setObject(3, invite.createdBy)
            it.setObject(4, invite.createdAt.utc())
            it.setObject(5, invite.expiresAt.utc())
            it.executeUpdate()
        }
    }

    /**
     * Uses up the invite with [codeHash] if it is unused, not revoked and not expired at [now], in one statement. Under
     * READ COMMITTED a concurrent redemption or revocation makes Postgres re-check the WHERE clause, so exactly one
     * wins. The row stays locked until the caller's transaction ends; a rollback leaves the invite unused.
     */
    fun redeem(
        connection: Connection,
        codeHash: ByteArray,
        now: Instant,
    ): RedeemedInvite? {
        if (codeHash.size != CODE_HASH_SIZE) return null
        return connection
            .prepareStatement(
                "UPDATE invites SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND revoked_at IS NULL " +
                    "AND expires_at > ? RETURNING id, created_by",
            ).use {
                it.setObject(1, now.utc())
                it.setBytes(2, codeHash)
                it.setObject(3, now.utc())
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        RedeemedInvite(rows.getObject("id", UUID::class.java), rows.getObject("created_by", UUID::class.java))
                    } else {
                        null
                    }
                }
            }
    }

    /**
     * Records which account a redeemed invite created (shown to the inviter for [USED_RETENTION]; the tree lives on
     * accounts). Returns the number of rows updated: 1, or 0 if there is no such redeemed invite.
     */
    fun markUsedBy(
        connection: Connection,
        inviteId: UUID,
        accountId: UUID,
    ): Int =
        connection.prepareStatement("UPDATE invites SET used_by = ? WHERE id = ? AND used_at IS NOT NULL").use {
            it.setObject(1, accountId)
            it.setObject(2, inviteId)
            it.executeUpdate()
        }

    /**
     * Retention (#13 SEC-45, owner decision 2026-10-16): deletes revoked and expired-unused invites one day after they
     * ended (the 24 h creation cap still counts them until then), and used invites [USED_RETENTION] after use. Returns
     * how many rows were deleted.
     */
    fun deleteFinished(
        connection: Connection,
        now: Instant,
    ): Int =
        connection
            .prepareStatement(
                "DELETE FROM invites WHERE (used_at IS NOT NULL AND used_at < ?) " +
                    "OR (used_at IS NULL AND revoked_at IS NOT NULL AND revoked_at < ?) " +
                    "OR (used_at IS NULL AND revoked_at IS NULL AND expires_at < ?)",
            ).use {
                it.setObject(1, now.minus(USED_RETENTION).utc())
                it.setObject(2, now.minus(ENDED_RETENTION).utc())
                it.setObject(3, now.minus(ENDED_RETENTION).utc())
                it.executeUpdate()
            }

    companion object {
        const val CODE_HASH_SIZE = 32
        val USED_RETENTION: Duration = Duration.ofDays(30)
        val ENDED_RETENTION: Duration = Duration.ofDays(1)
    }
}

/** Admin-only account removal (#13 SEC-46). Devices, sessions and the account's own invites go with it (cascades). */
class AccountRemoval {
    /** Deletes one account by username; its invitees keep their accounts (their `invited_by` becomes NULL). Returns 0 or 1. */
    fun deleteAccount(
        connection: Connection,
        username: String,
    ): Int =
        connection.prepareStatement("DELETE FROM accounts WHERE username = ?").use {
            it.setString(1, username)
            it.executeUpdate()
        }

    /**
     * Deletes an account and everyone it invited, transitively. Returns how many accounts went. Call inside a
     * transaction: it first locks `accounts` against inserts (#74 SEC-53), so a registration through an invite of
     * someone in the subtree either commits before the tree is collected (and is deleted with it) or waits and then
     * fails its foreign key check. Without the lock it could commit in between and survive with no inviter.
     */
    fun deleteWithInvitees(
        connection: Connection,
        username: String,
    ): Int {
        connection.createStatement().use { it.execute("LOCK TABLE accounts IN SHARE ROW EXCLUSIVE MODE") }
        return connection
            .prepareStatement(
                "WITH RECURSIVE tree AS (SELECT id FROM accounts WHERE username = ? " +
                    "UNION SELECT a.id FROM accounts a JOIN tree t ON a.invited_by = t.id) " +
                    "DELETE FROM accounts WHERE id IN (SELECT id FROM tree)",
            ).use {
                it.setString(1, username)
                it.executeUpdate()
            }
    }
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
