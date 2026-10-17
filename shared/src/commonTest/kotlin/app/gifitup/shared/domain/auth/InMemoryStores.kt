package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity

class InMemoryIdentityStore(
    var identity: StoredIdentity? = null,
) : IdentityStore {
    val saved = mutableListOf<StoredIdentity>()

    override suspend fun load(): StoredIdentity? = identity

    override suspend fun save(identity: StoredIdentity) {
        saved += identity
        this.identity = identity
    }
}

class InMemoryKeyStore(
    var key: ByteArray? = null,
) : SnapshotKeyStore {
    var saves = 0

    override suspend fun load(): ByteArray? = key?.copyOf()

    override suspend fun save(key: ByteArray) {
        saves++
        this.key = key.copyOf()
    }
}

/** Deterministic, distinct keys: fine for tests, never for anything else. */
class CountingRandom : SecureRandomSource {
    val sizes = mutableListOf<Int>()

    override fun nextBytes(size: Int): ByteArray {
        sizes += size
        return ByteArray(size) { (it + sizes.size).toByte() }
    }
}
