// JDBC parameter indices (setObject(3, …)) are positions in the SQL right next to them, not magic numbers.
@file:Suppress("MagicNumber")

package app.gifitup.server.db

import app.gifitup.server.auth.SignatureVerifier
import app.gifitup.server.auth.UsernamePolicy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// Account, device and session storage (ADR-0008, ADR-0009). Rules for every query here (#69 SEC-23, SEC-5):
// - prepared statements only, with typed binds (UUID, bytea, timestamptz); values are validated before binding, so
//   Postgres never sees a value it would quote back in a conversion error;
// - expected conflicts use ON CONFLICT DO NOTHING RETURNING, so a taken username or key is a normal result, never an
//   exception whose message could carry the value;
// - each method takes the caller's Connection, so a use case can run several of them in one transaction.

/** How an account joined (ADR-0010): the invite tree's edge. */
sealed interface Joined {
    /** With an admin invite: a bootstrap root, no inviter. */
    data object ViaAdmin : Joined

    /** With an invite created by [inviter]. */
    data class InvitedBy(
        val inviter: UUID,
    ) : Joined
}

class AccountRepository {
    /**
     * Inserts an account; false if the username (or, astronomically unlikely, the id) is taken. [joined] records the
     * invite tree edge (ADR-0010).
     */
    fun insert(
        connection: Connection,
        id: UUID,
        username: String,
        now: Instant,
        joined: Joined,
    ): Boolean {
        require(UsernamePolicy.isValid(username)) { "username must be validated before it reaches the database" }
        return connection.returnsRow(
            "INSERT INTO accounts (id, username, created_at, invited_by, joined_via_admin) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT DO NOTHING RETURNING id",
        ) {
            setObject(1, id)
            setString(2, username)
            setObject(3, now.utc())
            setObject(4, (joined as? Joined.InvitedBy)?.inviter)
            setBoolean(5, joined == Joined.ViaAdmin)
        }
    }
}

data class DeviceRecord(
    val id: UUID,
    val accountId: UUID,
    val signatureKey: ByteArray,
    val createdAt: Instant,
    val lastSeenAt: Instant,
) {
    override fun equals(other: Any?): Boolean =
        other is DeviceRecord &&
            id == other.id &&
            accountId == other.accountId &&
            signatureKey.contentEquals(other.signatureKey) &&
            createdAt == other.createdAt &&
            lastSeenAt == other.lastSeenAt

    override fun hashCode(): Int = id.hashCode()
}

class DeviceRepository {
    /** Inserts a device; false if its signature key (or id) is already registered. */
    fun insert(
        connection: Connection,
        id: UUID,
        accountId: UUID,
        signatureKey: ByteArray,
        now: Instant,
    ): Boolean {
        require(signatureKey.size == SignatureVerifier.PUBLIC_KEY_SIZE) { "signature key must be 32 bytes" }
        return connection.returnsRow(
            "INSERT INTO devices (id, account_id, signature_key, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT DO NOTHING RETURNING id",
        ) {
            setObject(1, id)
            setObject(2, accountId)
            setBytes(3, signatureKey)
            setObject(4, now.utc())
            setObject(5, now.utc())
        }
    }

    fun find(
        connection: Connection,
        id: UUID,
    ): DeviceRecord? =
        connection.query("SELECT $DEVICE_COLUMNS FROM devices WHERE id = ?", { setObject(1, id) }) { it.toDevice() }.singleOrNull()

    /** The device registered with [signatureKey] and its account's username, or null. */
    fun findByKey(
        connection: Connection,
        signatureKey: ByteArray,
    ): Pair<DeviceRecord, String>? {
        require(signatureKey.size == SignatureVerifier.PUBLIC_KEY_SIZE) { "signature key must be 32 bytes" }
        return connection
            .query(
                "SELECT d.id, d.account_id, d.signature_key, d.created_at, d.last_seen_at, a.username " +
                    "FROM devices d JOIN accounts a ON a.id = d.account_id WHERE d.signature_key = ?",
                { setBytes(1, signatureKey) },
            ) { it.toDevice() to it.getString("username") }
            .singleOrNull()
    }

    fun listForAccount(
        connection: Connection,
        accountId: UUID,
    ): List<DeviceRecord> =
        connection.query("SELECT $DEVICE_COLUMNS FROM devices WHERE account_id = ? ORDER BY created_at, id", {
            setObject(1, accountId)
        }) { it.toDevice() }

    /**
     * Sets `last_seen_at` to [now] only if it is older than [LAST_SEEN_RESOLUTION], so the database records at most
     * hourly activity per device (less metadata, fewer writes; ADR-0009).
     */
    fun touch(
        connection: Connection,
        id: UUID,
        now: Instant,
    ) {
        connection.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ? AND last_seen_at < ?").use {
            it.setObject(1, now.utc())
            it.setObject(2, id)
            it.setObject(3, now.minus(LAST_SEEN_RESOLUTION).utc())
            it.executeUpdate()
        }
    }

    private fun ResultSet.toDevice() =
        DeviceRecord(
            id = getObject("id", UUID::class.java),
            accountId = getObject("account_id", UUID::class.java),
            signatureKey = getBytes("signature_key"),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            lastSeenAt = getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
        )

    companion object {
        val LAST_SEEN_RESOLUTION: Duration = Duration.ofHours(1)
        private const val DEVICE_COLUMNS = "id, account_id, signature_key, created_at, last_seen_at"
    }
}

/** A live session: the device and account it authenticates. */
data class SessionRecord(
    val deviceId: UUID,
    val accountId: UUID,
    val expiresAt: Instant,
)

class SessionRepository {
    /**
     * Stores a session by the SHA-256 of its token (the token itself never reaches the database), drops the device's
     * expired sessions, and keeps only its [MAX_SESSIONS_PER_DEVICE] newest. Two concurrent logins of one device can
     * briefly leave one extra session; the next insert trims it.
     */
    fun insert(
        connection: Connection,
        tokenHash: ByteArray,
        deviceId: UUID,
        now: Instant,
        expiresAt: Instant,
    ) {
        require(tokenHash.size == TOKEN_HASH_SIZE) { "token hash must be 32 bytes" }
        require(expiresAt.isAfter(now)) { "a session must expire after it starts" }
        connection.prepareStatement("DELETE FROM sessions WHERE device_id = ? AND expires_at <= ?").use {
            it.setObject(1, deviceId)
            it.setObject(2, now.utc())
            it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO sessions (token_hash, device_id, created_at, expires_at) VALUES (?, ?, ?, ?)").use {
            it.setBytes(1, tokenHash)
            it.setObject(2, deviceId)
            it.setObject(3, now.utc())
            it.setObject(4, expiresAt.utc())
            it.executeUpdate()
        }
        // Keeps the new session plus the device's newest others; never the new one itself, even if the clock stepped
        // back or another login ran at the same time (#71 CR-2).
        connection
            .prepareStatement(
                "DELETE FROM sessions WHERE device_id = ? AND token_hash <> ? AND token_hash NOT IN " +
                    "(SELECT token_hash FROM sessions WHERE device_id = ? AND token_hash <> ? " +
                    "ORDER BY created_at DESC, token_hash LIMIT ?)",
            ).use {
                it.setObject(1, deviceId)
                it.setBytes(2, tokenHash)
                it.setObject(3, deviceId)
                it.setBytes(4, tokenHash)
                it.setInt(5, MAX_SESSIONS_PER_DEVICE - 1)
                it.executeUpdate()
            }
    }

    /** The session for [tokenHash] if it exists and hasn't expired at [now]. Expiry is checked here, on every lookup. */
    fun findValid(
        connection: Connection,
        tokenHash: ByteArray,
        now: Instant,
    ): SessionRecord? {
        if (tokenHash.size != TOKEN_HASH_SIZE) return null
        return connection
            .query(
                "SELECT s.device_id, d.account_id, s.expires_at FROM sessions s JOIN devices d ON d.id = s.device_id " +
                    "WHERE s.token_hash = ? AND s.expires_at > ?",
                {
                    setBytes(1, tokenHash)
                    setObject(2, now.utc())
                },
            ) {
                SessionRecord(
                    deviceId = it.getObject("device_id", UUID::class.java),
                    accountId = it.getObject("account_id", UUID::class.java),
                    expiresAt = it.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                )
            }.singleOrNull()
    }

    /** Deletes the session for [tokenHash]; false if there was none. */
    fun delete(
        connection: Connection,
        tokenHash: ByteArray,
    ): Boolean =
        connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use {
            it.setBytes(1, tokenHash)
            it.executeUpdate() > 0
        }

    companion object {
        const val MAX_SESSIONS_PER_DEVICE = 5
        const val TOKEN_HASH_SIZE = 32
    }
}

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun Connection.returnsRow(
    sql: String,
    bind: PreparedStatement.() -> Unit,
): Boolean =
    prepareStatement(sql).use { statement ->
        statement.bind()
        statement.executeQuery().use { it.next() }
    }

private fun <T> Connection.query(
    sql: String,
    bind: PreparedStatement.() -> Unit,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind()
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }
