package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.HistoryKeyStore
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.data.storage.RecordContactStore
import app.gifitup.shared.data.storage.RecordIdentityStore
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredRestoreCounter
import app.gifitup.shared.domain.chat.FriendNames
import app.gifitup.shared.domain.contacts.AcceptContact
import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.ContactLoad
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.ContactsUseCases
import app.gifitup.shared.domain.contacts.RefreshContacts
import app.gifitup.shared.domain.contacts.ShowFingerprints
import app.gifitup.shared.domain.groups.ChatActions
import app.gifitup.shared.domain.keypackages.ClaimKeyPackages
import app.gifitup.shared.domain.keypackages.RefillKeyPackages
import app.gifitup.shared.domain.origin.ServerOriginSetting
import kotlin.time.Clock

/**
 * This device's storage (ADR-0011), built once per app instance by the platform's composition root: once per page on
 * the web, once per process on Android. It owns the one [DeviceLock], so every [DeviceAuth] built from it shares it
 * (#108 SEC-22).
 *
 * [keyStoreFor] builds the wrapped-key store for a canonical origin over [store] and the given identity store; it is
 * the platform's `EnvelopeSnapshotKeyStore` (`:mls`), since `:shared` never sees a wrapping key. [historyKeyStoreFor] is the
 * same for the history key (`EnvelopeHistoryKeyStore`, ADR-0017).
 */
class DeviceStorage(
    val store: LocalStore,
    val data: DeviceData,
    private val keyStoreFor: (origin: String, identities: IdentityStore) -> SnapshotKeyStore,
    private val historyKeyStoreFor: (origin: String) -> HistoryKeyStore,
) {
    val identities: IdentityStore = RecordIdentityStore(store)
    val restores = StoredRestoreCounter(store)
    internal val lock = DeviceLock()

    fun snapshotKeys(origin: String): SnapshotKeyStore = keyStoreFor(origin, identities)

    fun historyKeys(origin: String): HistoryKeyStore = historyKeyStoreFor(origin)

    /**
     * The history key of the registered identity of [origin] (the canonical, configured one), made once if neither a key
     * nor any history exists; see [OpenHistoryKey]. The caller zeroes the array after use.
     */
    suspend fun openHistoryKey(
        origin: String,
        records: RecordStore,
        random: SecureRandomSource,
    ): ByteArray = OpenHistoryKey(historyKeys(origin), identities, origin, records, random, lock)()

    /** The server origin setting over this storage and its lock (ADR-0013); [default] is the build's origin. */
    fun originSetting(default: String): ServerOriginSetting = ServerOriginSetting(store, identities, lock, default)
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
    private val lock = storage.lock
    private val guarded = GuardedMlsFactory(mls, storage.restores)
    private val keys = storage.snapshotKeys(api.endpoint.origin)

    val register = RegisterDevice(api, guarded, storage.identities, keys, random, lock)
    val refillKeyPackages = RefillKeyPackages(api, guarded, storage.identities, keys, lock)
    val logIn = LogIn(api, guarded, storage.identities, keys, lock)
    val logOut = LogOut(api)
    val wipe = WipeLocalData(storage.data, logOut, lock)

    private val book = ContactBookAccess(RecordContactStore(storage.store, storage.identities)) { Clock.System.now().epochSeconds }

    /** The contact book and what the Friends screen needs from it (ADR-0014); the clock is the book's display time only. */
    val contacts: ContactsUseCases =
        ContactsUseCases(
            RefreshContacts(api, guarded, storage.identities, keys, lock, book),
            book,
            ShowFingerprints(api.endpoint.origin, guarded, storage.identities, keys, lock),
            AcceptContact(book),
        )

    /**
     * The friends' usernames for the new-chat screen: the contact book's `OK` contacts and the ones only the server's
     * member list has shown (`UNCONFIRMED`: a chat's key claim is what saves them, ADR-0014). Blocked and gone contacts
     * are left out. `null` if the book can't be read.
     */
    val friendNames: FriendNames =
        object : FriendNames {
            override suspend fun names(): List<String>? =
                when (val load = book.read()) {
                    is ContactLoad.Book -> {
                        load.book.contacts.values
                            .filter { it.status == ContactStatus.OK || it.status == ContactStatus.UNCONFIRMED }
                            .map { it.username }
                    }

                    else -> {
                        null
                    }
                }

            override suspend fun refresh(session: Session): AuthResult<Unit> =
                when (val refreshed = contacts.refresh(session)) {
                    is AuthResult.Ok -> AuthResult.Ok(Unit)
                    is AuthResult.Failed -> refreshed
                }
        }

    /** Groups and messages (ADR-0016): the same device lock, restore guard, contact book and keys as everything above. */
    val chat: ChatActions =
        ChatActions(
            api,
            guarded,
            storage.identities,
            keys,
            lock,
            book,
            ClaimKeyPackages(api, guarded, storage.identities, keys, lock, book),
            random,
        )

    /**
     * The user's "try again" after `RESTORE_BLOCKED`. Under the storage's lock like every restore, so a reset from one
     * [DeviceAuth] can't land in the middle of another one's restore and drop its count (#111 CR-1, SEC-2).
     */
    suspend fun tryAgain() = lock.holding { guarded.tryAgain() }
}
