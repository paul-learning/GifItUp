package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.GuardedMlsFactory
import app.gifitup.shared.domain.contacts.ContactBookAccess
import app.gifitup.shared.domain.contacts.ContactLoad
import app.gifitup.shared.domain.contacts.ContactStatus
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.keypackages.ClaimKeyPackages
import app.gifitup.shared.domain.keypackages.ClaimedDeviceKey
import app.gifitup.shared.domain.keypackages.StoredClient

/**
 * What a user does with a group, as one call each (ADR-0016): the registered identity is restored under the device lock,
 * a [GroupSyncEngine] works on it with the real Delivery Service, the contact book as pins, and every change of state is
 * saved to the identity store in one piece. Members to add come from a claim that the contact book has already checked
 * ([ClaimKeyPackages]); members to remove come from the same book, so a device id and its key always belong together.
 * The claim happens before the engine is started: it takes the device lock itself, which is not reentrant.
 */
@Suppress("LongParameterList") // The composition root hands over the same seams as for the other use cases.
class ChatActions(
    private val api: GifItUpApi,
    mls: GuardedMlsFactory,
    private val identities: IdentityStore,
    keys: SnapshotKeyStore,
    lock: DeviceLock,
    private val access: ContactBookAccess,
    private val claim: ClaimKeyPackages,
    private val random: SecureRandomSource,
) {
    private val stored = StoredClient(api.endpoint.origin, mls, identities, keys, lock)

    /** Claims [target]'s devices and creates a group with them. */
    suspend fun create(
        session: Session,
        target: String,
        sink: IncomingSink,
    ): AuthResult<GroupCreation> =
        withMembers(session, target) { members ->
            if (members.isEmpty()) {
                AuthResult.Ok(GroupCreation(null, SyncOutcome.InvalidRequest))
            } else {
                engine(session, sink) { engine, _ -> engine.createGroup(members) }
            }
        }

    suspend fun add(
        session: Session,
        groupId: ByteArray,
        target: String,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> =
        withMembers(session, target) { members ->
            if (members.isEmpty()) {
                AuthResult.Ok(SyncOutcome.InvalidRequest)
            } else {
                engine(session, sink) { engine, _ -> engine.addMembers(groupId, members) }
            }
        }

    /** Removes every device of the contact [target] that is a leaf of the group (the book's device ids and keys). */
    suspend fun remove(
        session: Session,
        groupId: ByteArray,
        target: String,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> =
        engine(session, sink) { engine, client ->
            val leaves = client.leavesOf(groupId)
            val targets = pinnedDevices(target).filter { (key, _) -> leaves != null && key in leaves }
            when {
                leaves == null -> SyncOutcome.NotJoined
                targets.isEmpty() -> SyncOutcome.InvalidRequest
                else -> engine.removeMembers(groupId, targets.map { (key, device) -> RemoveTarget(device, key.bytes) })
            }
        }

    suspend fun send(
        session: Session,
        groupId: ByteArray,
        text: ByteArray,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> = engine(session, sink) { engine, _ -> engine.send(groupId, text) }

    suspend fun sync(
        session: Session,
        groupId: ByteArray,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> = engine(session, sink) { engine, _ -> engine.sync(groupId) }

    suspend fun resume(
        session: Session,
        groupId: ByteArray,
        sink: IncomingSink,
    ): AuthResult<SyncOutcome> = engine(session, sink) { engine, _ -> engine.resume(groupId) }

    suspend fun welcomes(
        session: Session,
        sink: IncomingSink,
    ): AuthResult<WelcomesReport> = engine(session, sink) { engine, _ -> engine.processWelcomes() }

    private suspend fun <T> withMembers(
        session: Session,
        target: String,
        then: suspend (List<NewMember>) -> AuthResult<T>,
    ): AuthResult<T> =
        when (val claimed = claim(session, target)) {
            is AuthResult.Failed -> {
                claimed
            }

            is AuthResult.Ok -> {
                then(
                    claimed.value.filterIsInstance<ClaimedDeviceKey.Usable>().map { NewMember(it.deviceId, it.keyPackage) },
                )
            }
        }

    /** The pinned devices (key and device id) of the `OK` contact called [username]. */
    private suspend fun pinnedDevices(username: String): Map<KeyBytes, String> {
        val book = (access.read() as? ContactLoad.Book)?.book
        val contact = book?.contacts?.values?.singleOrNull { it.username == username && it.status == ContactStatus.OK }
        return contact?.devices.orEmpty()
    }

    private suspend fun <T> engine(
        session: Session,
        sink: IncomingSink,
        block: suspend (GroupSyncEngine, MlsClient) -> T,
    ): AuthResult<T> =
        stored.use { client, identity ->
            val saver =
                SnapshotSaver { snapshot ->
                    identities.save(StoredIdentity(identity.origin, identity.username, snapshot, identity.deviceId))
                }
            val engine = GroupSyncEngine(client, SessionDeliveryPort(api, session), ContactBookPins(access), saver, sink, random)
            AuthResult.Ok(block(engine, client))
        }
}

/** The signature keys of the group's leaves, or null if this device has no such group. */
private fun MlsClient.leavesOf(groupId: ByteArray): Set<KeyBytes>? =
    try {
        members(groupId).map { KeyBytes(it.signatureKey) }.toSet()
    } catch (e: MlsException) {
        if (e.code != MlsErrorCode.UnknownGroup) throw e
        null
    }
