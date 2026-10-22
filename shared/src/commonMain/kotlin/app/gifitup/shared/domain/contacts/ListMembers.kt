package app.gifitup.shared.domain.contacts

import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.MemberList
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.toAuth

/**
 * The caller's invite neighbours as the server lists them (ADR-0014): the inviter and the accounts invited, checked
 * as a whole by the API layer. A convenience view, not an access control. Nothing here pins a key; the contact book
 * does that (#18, next PR).
 */
class ListMembers(
    private val api: GifItUpApi,
) {
    suspend operator fun invoke(session: Session): AuthResult<MemberList> = api.listMembers(session).toAuth()
}
