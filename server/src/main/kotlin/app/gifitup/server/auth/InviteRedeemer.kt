package app.gifitup.server.auth

import app.gifitup.server.db.InviteRepository
import app.gifitup.server.db.RedeemedInvite
import java.sql.Connection
import java.time.Instant

/**
 * Redeems an invite code as part of a registration (ADR-0009, ADR-0010). Runs on the registration's own transaction
 * connection, so the invite is consumed if and only if the account is created (#68 round 0, SEC-3).
 */
fun interface InviteRedeemer {
    /**
     * Uses up [code] if it is valid; returns what was redeemed, or null for an unknown, used, revoked or expired code
     * (one answer for all, so the caller can't tell which). Must not throw for any well-formed code.
     */
    fun redeem(
        connection: Connection,
        code: String,
        now: Instant,
    ): RedeemedInvite?
}

/** Refuses every code. For tests and as a fail-closed fallback; production uses [DbInviteRedeemer]. */
object DenyAllInviteRedeemer : InviteRedeemer {
    override fun redeem(
        connection: Connection,
        code: String,
        now: Instant,
    ): RedeemedInvite? = null
}

/**
 * The real redeemer (ADR-0010): hashes the code (SHA-256 of its 16 decoded bytes) and uses up the matching invite in
 * one statement on the registration transaction's connection ([InviteRepository.redeem]). The code itself never
 * reaches the database or a log.
 */
class DbInviteRedeemer(
    private val invites: InviteRepository = InviteRepository(),
) : InviteRedeemer {
    override fun redeem(
        connection: Connection,
        code: String,
        now: Instant,
    ): RedeemedInvite? = InviteCodes.hash(code)?.let { invites.redeem(connection, it, now) }
}
