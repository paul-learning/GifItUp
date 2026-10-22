package app.gifitup.server.members

import app.gifitup.protocol.MemberRelation
import app.gifitup.server.db.MemberDeviceRow
import app.gifitup.server.db.MemberRepository
import app.gifitup.server.db.TransactionRunner
import java.util.UUID

/** A neighbour of the caller in the invite tree, with its devices (ADR-0014). */
class Member(
    val accountId: UUID,
    val username: String,
    val relation: MemberRelation,
    val devices: List<MemberDeviceRow>,
)

/** [more] is true when the list was cut at [MemberService.LIMIT]. */
class MemberList(
    val members: List<Member>,
    val more: Boolean,
)

/**
 * The caller's invite neighbours: their inviter and the accounts they invited, nobody else (ADR-0014). A convenience
 * view, not an access control: the KeyPackage claim stays open to every member by username (ADR-0012). HTTP-agnostic;
 * the account comes from the session. Writes nothing and logs nothing.
 */
class MemberService(
    private val db: TransactionRunner,
) {
    private val members = MemberRepository()

    fun list(callerId: UUID): MemberList {
        // One more than the limit tells us whether the list was cut.
        val rows = db.transaction { c -> members.neighbours(c, callerId, LIMIT + 1) }
        val shown =
            rows.take(LIMIT).map {
                Member(it.accountId, it.username, if (it.invitedByCaller) MemberRelation.INVITEE else MemberRelation.INVITER, it.devices)
            }
        return MemberList(shown, more = rows.size > LIMIT)
    }

    companion object {
        /** At most this many members are listed (invites are capped per day, the total is not). */
        const val LIMIT = 200
    }
}
