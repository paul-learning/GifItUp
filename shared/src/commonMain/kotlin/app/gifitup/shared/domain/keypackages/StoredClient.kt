package app.gifitup.shared.domain.keypackages

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.GuardedMlsFactory
import app.gifitup.shared.domain.auth.RestoreBlockedException
import app.gifitup.shared.domain.auth.failed
import app.gifitup.shared.domain.auth.toAuthFailure

/**
 * Runs a block with this device's restored [MlsClient] under the [DeviceLock], the way login does (#16): the identity
 * must be registered for the configured origin, the snapshot key must be there, and every restore goes through the
 * guard. The client is closed afterwards, also when the block throws. Failures are the same closed [AuthError]s the
 * auth use cases use.
 */
internal class StoredClient(
    private val origin: String,
    private val mls: GuardedMlsFactory,
    private val identities: IdentityStore,
    private val keys: SnapshotKeyStore,
    private val lock: DeviceLock,
) {
    suspend fun <T> use(block: suspend (client: MlsClient, identity: StoredIdentity) -> AuthResult<T>): AuthResult<T> =
        lock.holding {
            try {
                val stored = identities.load()
                val key = if (stored?.origin == origin && stored.deviceId != null) keys.load() else null
                when {
                    stored == null || stored.deviceId == null -> failed(AuthError.Local.NOT_REGISTERED)
                    stored.origin != origin -> failed(AuthError.Local.OTHER_ORIGIN)
                    key == null -> failed(AuthError.Local.STATE_INVALID)
                    else -> mls.restore(stored.snapshot, key).use { block(it, stored) }
                }
            } catch (e: MlsException) {
                e.toAuthFailure()
            } catch (e: LocalStateException) {
                e.toAuthFailure()
            } catch (ignored: RestoreBlockedException) {
                failed(AuthError.Local.RESTORE_BLOCKED)
            }
        }
}
