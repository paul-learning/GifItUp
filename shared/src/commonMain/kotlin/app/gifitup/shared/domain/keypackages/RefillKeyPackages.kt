package app.gifitup.shared.domain.keypackages

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.KeyPackageStatus
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.GuardedMlsFactory
import app.gifitup.shared.domain.auth.toAuth

/** What a refill did: how many KeyPackages it uploaded, and how many the server holds for this device afterwards. */
data class Refilled(
    val uploaded: Int,
    val available: Int,
)

/**
 * Keeps this device's KeyPackage pool on the server filled (ADR-0012), for onboarding (#17) and every app start.
 *
 * - Asks the server how many are left and what its low-water mark is; the mark is the server's word and untrusted, so
 *   it is clamped to 1..[MAX_HELD]. At or above the mark nothing is created.
 * - Otherwise: delete the private keys of expired KeyPackages, create a batch of at most [BATCH] (and never more than
 *   the 200 the server and the crate allow), **persist the exported state first**, then upload. A KeyPackage is never
 *   public without its private key: if the upload fails or its answer is lost, the keys are kept (they expire and are
 *   pruned later), and the next refill asks the server again.
 * - **Known limits (ADR-0012, threat model):** the batch is sized from the server's count, but the crate counts every
 *   unused private key it still holds, including ones a peer claimed and never used. Once held plus
 *   the new batch would exceed the crate's cap of 200, `createKeyPackages` fails (`CRYPTO`) until old keys expire (up to
 *   84 days). The planned crate change drops the oldest
 *   held keys at the cap. A server that always reports 0 available makes each refill mint up to [BATCH] more keys, which
 *   leads to the same stop; the server is trusted for availability only, never for secrecy.
 * - Holds the device lock, like login, for the restore, the state write and the upload.
 */
class RefillKeyPackages internal constructor(
    private val api: GifItUpApi,
    private val client: StoredClient,
    private val identities: IdentityStore,
) {
    constructor(
        api: GifItUpApi,
        mls: GuardedMlsFactory,
        identities: IdentityStore,
        keys: SnapshotKeyStore,
        lock: DeviceLock,
    ) : this(api, StoredClient(api.endpoint.origin, mls, identities, keys, lock), identities)

    suspend operator fun invoke(session: Session): AuthResult<Refilled> =
        client.use { mls, stored ->
            when (val status = api.keyPackageStatus(session)) {
                is ApiResult.Failed -> status.toAuth()
                is ApiResult.Ok -> refill(session, mls, stored, status.value)
            }
        }

    private suspend fun refill(
        session: Session,
        mls: MlsClient,
        stored: StoredIdentity,
        status: KeyPackageStatus,
    ): AuthResult<Refilled> {
        val low = status.lowWaterMark.coerceIn(1, MAX_HELD)
        if (status.available >= low) return AuthResult.Ok(Refilled(0, status.available))
        mls.pruneExpiredKeyPackages()
        val created = mls.createKeyPackages(minOf(BATCH, MAX_HELD - status.available))
        // Persist first: from here on the private keys exist only in this snapshot (ADR-0012).
        identities.save(StoredIdentity(stored.origin, stored.username, mls.exportState(), stored.deviceId))
        return when (val uploaded = api.uploadKeyPackages(session, created.map { it.keyPackage })) {
            is ApiResult.Ok -> AuthResult.Ok(Refilled(uploaded.value.accepted, uploaded.value.available))
            is ApiResult.Failed -> uploaded.toAuth()
        }
    }

    internal companion object {
        const val BATCH = 50
        const val MAX_HELD = 200
    }
}
