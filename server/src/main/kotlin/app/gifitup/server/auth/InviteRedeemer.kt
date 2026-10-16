package app.gifitup.server.auth

import java.sql.Connection
import java.time.Instant

/**
 * Redeems an invite code as part of a registration (ADR-0009). Runs on the registration's own transaction connection,
 * so the invite is consumed if and only if the account is created (#68 round 0, SEC-3). The real implementation comes
 * with #13 (≥128-bit codes, stored hashed, one atomic `UPDATE … WHERE unused AND unexpired`).
 */
fun interface InviteRedeemer {
    /** True if [code] was valid and is now used. Must not throw for an unknown code. */
    fun redeem(
        connection: Connection,
        code: String,
        now: Instant,
    ): Boolean
}

/** Until #13: nobody can register, so the deployed server can't be used to create accounts yet (fails closed). */
object DenyAllInviteRedeemer : InviteRedeemer {
    override fun redeem(
        connection: Connection,
        code: String,
        now: Instant,
    ): Boolean = false
}
