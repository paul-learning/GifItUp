package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.RestoreCounter
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import kotlinx.coroutines.yield

class InMemoryIdentityStore(
    var identity: StoredIdentity? = null,
) : IdentityStore {
    val saved = mutableListOf<StoredIdentity>()

    /** Yields like real storage would, so concurrent callers interleave here. */
    override suspend fun load(): StoredIdentity? {
        yield()
        return identity
    }

    var failSave = false

    override suspend fun save(identity: StoredIdentity) {
        check(!failSave) { "disk full" }
        saved += identity
        this.identity = identity
    }

    override suspend fun clear() {
        identity = null
    }
}

class InMemoryKeyStore(
    var key: ByteArray? = null,
) : SnapshotKeyStore {
    var saves = 0

    override suspend fun load(): ByteArray? = key?.copyOf()

    var failSave = false

    override suspend fun save(key: ByteArray) {
        check(!failSave) { "disk full" }
        saves++
        this.key = key.copyOf()
    }

    override suspend fun clear() {
        key = null
    }
}

/** The fake factory without a restore counter, as the use cases' tests had it before the guard (#16). */
fun unguarded(mls: MlsClientFactory = FakeMlsClient.Factory) = GuardedMlsFactory(mls, RestoreCounter.Unneeded)

/** Deterministic, distinct keys: fine for tests, never for anything else. */
class CountingRandom : SecureRandomSource {
    val sizes = mutableListOf<Int>()

    override fun nextBytes(size: Int): ByteArray {
        sizes += size
        return ByteArray(size) { (it + sizes.size).toByte() }
    }
}
