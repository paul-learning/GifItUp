package app.gifitup.shared.domain.contacts

import app.gifitup.shared.data.crypto.FingerprintEmoji
import app.gifitup.shared.data.crypto.FingerprintEmojis
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.GuardedMlsFactory
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.keypackages.StoredClient

/**
 * The emojis of this device's own key and of the requested keys (ADR-0014). [own] comes from the local identity, never
 * from a server answer. A key that wasn't asked for has no entry.
 */
class Fingerprints(
    val own: List<FingerprintEmoji>,
    private val byKey: Map<KeyBytes, List<FingerprintEmoji>>,
) {
    fun of(key: KeyBytes): List<FingerprintEmoji>? = byKey[key]
}

/**
 * Computes fingerprints with one restore of the MLS client for all keys of a screen update (guarded restore, device
 * lock, like login). Any failure fails the whole call: nothing is shown from a partial answer.
 */
class ShowFingerprints internal constructor(
    private val client: StoredClient,
) {
    constructor(
        origin: String,
        mls: GuardedMlsFactory,
        identities: IdentityStore,
        keys: SnapshotKeyStore,
        lock: DeviceLock,
    ) : this(StoredClient(origin, mls, identities, keys, lock))

    suspend operator fun invoke(keys: Set<KeyBytes>): AuthResult<Fingerprints> =
        client.use { mls, _ ->
            val own = FingerprintEmojis.cells(mls.fingerprint(mls.publicIdentity().signaturePublicKey))
            AuthResult.Ok(Fingerprints(own, keys.associateWith { FingerprintEmojis.cells(mls.fingerprint(it.bytes)) }))
        }
}
