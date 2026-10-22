package app.gifitup.shared.domain.contacts

import app.gifitup.shared.data.contacts.ContactBook
import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.contacts.Sighting
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.MemberList
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.ContactLoad
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.GuardedMlsFactory
import app.gifitup.shared.domain.auth.failed
import app.gifitup.shared.domain.auth.toAuth
import app.gifitup.shared.domain.keypackages.StoredClient

/**
 * Fetches the member list and folds it into the contact book (ADR-0014). The list never pins: unknown accounts become
 * `UNCONFIRMED`, known ones are checked like a claim. Refused as a whole, with nothing recorded, if the list names this
 * device's own signature key or username (the server cannot list us as someone else). The book is read first, so an
 * unusable book makes no request; the lock is not held while the request runs.
 */
class RefreshContacts internal constructor(
    private val api: GifItUpApi,
    private val client: StoredClient,
    private val access: ContactBookAccess,
) {
    constructor(
        api: GifItUpApi,
        mls: GuardedMlsFactory,
        identities: IdentityStore,
        keys: SnapshotKeyStore,
        lock: DeviceLock,
        access: ContactBookAccess,
    ) : this(api, StoredClient(api.endpoint.origin, mls, identities, keys, lock), access)

    suspend operator fun invoke(session: Session): AuthResult<ContactBook> {
        if (access.read() !is ContactLoad.Book) return failed(AuthError.Local.CONTACTS_UNAVAILABLE)
        return client.use { mls, identity ->
            when (val answer = api.listMembers(session)) {
                is ApiResult.Failed -> answer.toAuth()
                is ApiResult.Ok -> fold(answer.value, mls, identity.username)
            }
        }
    }

    private suspend fun fold(
        list: MemberList,
        mls: MlsClient,
        ownUsername: String,
    ): AuthResult<ContactBook> {
        val own = mls.publicIdentity().signaturePublicKey
        val sightings =
            list.members.map { m -> Sighting(m.accountId, m.username, m.devices.associate { KeyBytes(it.signatureKey) to it.deviceId }) }
        val namesUs = sightings.any { it.username == ownUsername || it.devices.keys.any { k -> k == KeyBytes(own) } }
        if (namesUs) return AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE))
        return when (val updated = access.update { book, now -> book.list(sightings, complete = !list.more, now).let { it to it } }) {
            is BookUpdate.Done -> AuthResult.Ok(updated.value)
            BookUpdate.Unavailable, BookUpdate.NotSaved -> failed(AuthError.Local.CONTACTS_UNAVAILABLE)
        }
    }
}
