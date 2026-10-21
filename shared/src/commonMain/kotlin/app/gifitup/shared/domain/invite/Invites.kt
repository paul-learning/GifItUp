package app.gifitup.shared.domain.invite

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Invite
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.toAuth

/** A new invite (ADR-0010). The code is a one-use credential: it never prints, and the link is built here, never taken from the server. */
class CreatedInvite(
    val inviteId: String,
    val code: InviteCode,
    val expiresAt: Long,
    /** `<configured origin>/invite#<code>`, built by the client (ADR-0013). */
    val link: String,
) {
    override fun toString(): String = "CreatedInvite(inviteId=$inviteId, expiresAt=$expiresAt)"
}

/**
 * Creates an invite to share (ADR-0010, ADR-0013, #17 plan review SEC-8). The server's `link` is checked with the strict
 * parser against the app's own origin: anything but exactly `<origin>/invite#<code>` (a look-alike host, an extra
 * path, another code shape), or an expiry a date can't show, is never shown; the just-created invite is revoked, best
 * effort, so it doesn't hold one of the 5 open slots (it still counts toward the 20 a day, ADR-0010), and the answer
 * is `MALFORMED_RESPONSE`. Never repeated automatically: a lost answer means an invite may exist that the user never
 * saw; it shows in [ListInvites] and can be revoked there.
 */
class CreateInvite(
    private val api: GifItUpApi,
) {
    suspend operator fun invoke(session: Session): AuthResult<CreatedInvite> =
        when (val created = api.createInvite(session)) {
            is ApiResult.Failed -> {
                created.toAuth()
            }

            is ApiResult.Ok -> {
                val answer = created.value
                val parsed = InviteLinks.parse(answer.link, api.endpoint.origin)
                if (parsed is InviteInput.Link && answer.expiresAt in 1..GifItUpApi.MAX_TIME) {
                    val link = InviteLinks.build(api.endpoint.origin, parsed.code)
                    AuthResult.Ok(CreatedInvite(answer.inviteId, parsed.code, answer.expiresAt, link))
                } else {
                    api.revokeInvite(session, answer.inviteId)
                    AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE))
                }
            }
        }
}

/** The account's own invites, newest first as the server sends them. */
class ListInvites(
    private val api: GifItUpApi,
) {
    suspend operator fun invoke(session: Session): AuthResult<List<Invite>> = api.listInvites(session).toAuth()
}

/** Revokes one of the account's open invites; anything else (used, expired, someone else's, unknown) is `NOT_FOUND`. */
class RevokeInvite(
    private val api: GifItUpApi,
) {
    suspend operator fun invoke(
        session: Session,
        inviteId: String,
    ): AuthResult<Unit> = api.revokeInvite(session, inviteId).toAuth()
}
