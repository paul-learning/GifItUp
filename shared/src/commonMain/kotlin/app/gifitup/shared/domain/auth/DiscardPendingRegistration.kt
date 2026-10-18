package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore

/**
 * Discards a pending registration on the user's explicit request (#95 CR-5, SEC-6): after a refused resume, the user
 * decides whether the earlier attempt is lost. If that attempt did create the account, it is orphaned (the owner can
 * delete it). A registered identity is never discarded: this returns [AuthError.Local.ALREADY_REGISTERED].
 */
class DiscardPendingRegistration(
    private val identities: IdentityStore,
    private val keys: SnapshotKeyStore,
) {
    suspend operator fun invoke(): AuthResult<Unit> {
        if (identities.load()?.deviceId != null) return failed(AuthError.Local.ALREADY_REGISTERED)
        identities.clear()
        keys.clear()
        return AuthResult.Ok(Unit)
    }
}
