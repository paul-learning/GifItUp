package app.gifitup.shared.domain.keypackages

import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.contacts.Sighting
import app.gifitup.shared.data.contacts.Verdict
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.PublicIdentity
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.ClaimedKeyPackage
import app.gifitup.shared.data.network.ClaimedKeyPackages
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.ContactLoad
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.GuardedMlsFactory
import app.gifitup.shared.domain.auth.InputRules
import app.gifitup.shared.domain.auth.failed
import app.gifitup.shared.domain.auth.toAuth
import app.gifitup.shared.domain.contacts.BookUpdate
import app.gifitup.shared.domain.contacts.ContactBookAccess

/** One device of the claimed member. Everything here came from the untrusted server and passed the crate's checks. */
sealed interface ClaimedDeviceKey {
    val deviceId: String

    /**
     * A KeyPackage the crate accepted for the claimed username: signatures, credential and lifetime are consistent. That
     * is not authentication on its own: an untrusted server can supply a KeyPackage of its own for any username, and
     * [deviceId] is unauthenticated. [ClaimKeyPackages] hands it on only after the contact book (ADR-0014) has checked
     * [identity]'s signature key, so a Usable device here has passed that check.
     */
    class Usable(
        override val deviceId: String,
        val keyPackage: ByteArray,
        val identity: PublicIdentity,
    ) : ClaimedDeviceKey

    /** The device had no KeyPackage for the wanted ciphersuites (an empty pool). */
    class Empty(
        override val deviceId: String,
    ) : ClaimedDeviceKey

    /** The server sent a KeyPackage the crate refused (wrong username, malformed, bad signature); it is dropped. */
    class Unusable(
        override val deviceId: String,
    ) : ClaimedDeviceKey
}

/**
 * Claims one KeyPackage per device of [username] (ADR-0012) and checks each with the crate (`inspectKeyPackage`, which
 * verifies both signatures, the credential and the lifetime). The server is untrusted: a KeyPackage that fails the
 * check is [ClaimedDeviceKey.Unusable] and never handed on.
 *
 * **Never retried automatically**: a claim consumes the KeyPackages it returns, so a lost answer loses them. The caller
 * shows the failure and the user asks again. A claim needs the registered identity (the crate that inspects runs from
 * the restored snapshot), so it holds the device lock and counts as one restore like login does.
 */
class ClaimKeyPackages internal constructor(
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

    suspend operator fun invoke(
        session: Session,
        username: String,
    ): AuthResult<List<ClaimedDeviceKey>> =
        if (!InputRules.isValidUsername(username)) {
            failed(AuthError.Local.INVALID_USERNAME)
        } else {
            // Before the request, so a blocked contact doesn't burn the target's KeyPackages (ADR-0014).
            when (val book = access.read()) {
                ContactLoad.Unavailable -> {
                    failed(AuthError.Local.CONTACTS_UNAVAILABLE)
                }

                is ContactLoad.Book -> {
                    if (book.book.blockedReasonFor(username) !=
                        null
                    ) {
                        failed(AuthError.Local.CONTACT_BLOCKED)
                    } else {
                        claim(session, username)
                    }
                }
            }
        }

    private suspend fun claim(
        session: Session,
        username: String,
    ): AuthResult<List<ClaimedDeviceKey>> =
        client.use { mls, _ ->
            when (val claimed = api.claimKeyPackages(session, username, SUITES)) {
                is ApiResult.Failed -> claimed.toAuth()
                is ApiResult.Ok -> checked(claimed.value, claimed.value.devices.map { classify(mls, it, username) }, username)
            }
        }

    /**
     * The whole claim is judged once, after the request and not under the book's lock while it ran: any conflict, or a
     * failed write of the pin, hands nothing on (the KeyPackages are already consumed). No usable device, nothing to pin.
     */
    private suspend fun checked(
        claimed: ClaimedKeyPackages,
        devices: List<ClaimedDeviceKey>,
        username: String,
    ): AuthResult<List<ClaimedDeviceKey>> {
        val usable = devices.filterIsInstance<ClaimedDeviceKey.Usable>()
        val keys = usable.associate { KeyBytes(it.identity.signaturePublicKey) to it.deviceId }
        return when {
            usable.isEmpty() -> AuthResult.Ok(devices)
            keys.size != usable.size -> AuthResult.Failed(AuthError.Api(ApiError.MALFORMED_RESPONSE))
            else -> pin(Sighting(claimed.accountId, username, keys), devices)
        }
    }

    private suspend fun pin(
        sighting: Sighting,
        devices: List<ClaimedDeviceKey>,
    ): AuthResult<List<ClaimedDeviceKey>> =
        when (val updated = access.update { book, now -> book.claim(sighting, now) }) {
            is BookUpdate.Done -> if (updated.value == Verdict.Usable) AuthResult.Ok(devices) else failed(AuthError.Local.CONTACT_BLOCKED)
            BookUpdate.Unavailable, BookUpdate.NotSaved -> failed(AuthError.Local.CONTACTS_UNAVAILABLE)
        }

    private fun classify(
        mls: MlsClient,
        device: ClaimedKeyPackage,
        username: String,
    ): ClaimedDeviceKey {
        val bytes = device.keyPackage ?: return ClaimedDeviceKey.Empty(device.deviceId)
        val identity = inspect(mls, bytes, username)
        return if (identity ==
            null
        ) {
            ClaimedDeviceKey.Unusable(device.deviceId)
        } else {
            ClaimedDeviceKey.Usable(device.deviceId, bytes, identity)
        }
    }

    /** The crate's verdict, or null if it refused this KeyPackage. Anything but a refusal (a dead client) propagates. */
    private fun inspect(
        mls: MlsClient,
        bytes: ByteArray,
        username: String,
    ): PublicIdentity? =
        try {
            mls.inspectKeyPackage(bytes, username)
        } catch (e: MlsException) {
            if (e.code == MlsErrorCode.InvalidKeyPackage || e.code == MlsErrorCode.InvalidInput) null else throw e
        }

    internal companion object {
        /** MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519: the only suite in v1 (ADR-0002, ADR-0012). */
        val SUITES = listOf(1)
    }
}
