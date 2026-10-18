package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.data.storage.RecordIdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredRestoreCounter

/**
 * This device's storage (ADR-0011), built once per app instance by the platform's composition root: once per page on
 * the web, once per process on Android. It owns the one [DeviceLock], so every [DeviceAuth] built from it shares it
 * (#108 SEC-22).
 *
 * [snapshotKeys] builds the wrapped-key store for a canonical origin over [store] and the given identity store; it is
 * the platform's `EnvelopeSnapshotKeyStore` (`:mls`), since `:shared` never sees a wrapping key.
 */
class DeviceStorage(
    val store: LocalStore,
    val data: DeviceData,
    private val snapshotKeys: (origin: String, identities: IdentityStore) -> SnapshotKeyStore,
) {
    val identities: IdentityStore = RecordIdentityStore(store)
    val restores = StoredRestoreCounter(store)
    internal val lock = DeviceLock()

    fun snapshotKeys(origin: String): SnapshotKeyStore = snapshotKeys(origin, identities)
}

/**
 * The identity use cases over one [DeviceStorage] and one server. Registration, discard, login and wipe all hold the
 * storage's [DeviceLock], and every restore goes through one [GuardedMlsFactory] over the storage's counter.
 */
class DeviceAuth(
    storage: DeviceStorage,
    api: GifItUpApi,
    mls: MlsClientFactory,
    random: SecureRandomSource,
) {
    private val guarded = GuardedMlsFactory(mls, storage.restores)
    private val keys = storage.snapshotKeys(api.endpoint.origin)

    val register = RegisterDevice(api, guarded, storage.identities, keys, random, storage.lock)
    val logIn = LogIn(api, guarded, storage.identities, keys, storage.lock)
    val logOut = LogOut(api)
    val wipe = WipeLocalData(storage.data, logOut, storage.lock)

    /** The user's "try again" after `RESTORE_BLOCKED`. */
    suspend fun tryAgain() = guarded.tryAgain()
}
