package app.gifitup.mls.groups

import app.gifitup.mls.UniffiMlsClientFactory
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.domain.groups.ContactPins
import app.gifitup.shared.domain.groups.ContactPinsSource
import app.gifitup.shared.domain.groups.DeliveredMessage
import app.gifitup.shared.domain.groups.GroupSyncEngine
import app.gifitup.shared.domain.groups.IncomingSink
import app.gifitup.shared.domain.groups.PinCheck
import app.gifitup.shared.domain.groups.SnapshotSaver
import java.security.SecureRandom

private val random = SecureRandom()

fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

/**
 * One device of a test: a real [MlsClient] (the Rust crate), its engine, the stored snapshot and what was delivered. The
 * helpers below play "another client" by driving the crate and the in-memory Delivery Service directly, the way the send
 * path of the engine will (PR C2).
 */
class Party(
    val name: String,
    private val ds: InMemoryDelivery,
    deviceSuffix: String = "",
) {
    val deviceId =
        java.util.UUID
            .nameUUIDFromBytes("$name$deviceSuffix".toByteArray())
            .toString()
    val view = ds.viewFor(deviceId)
    var mls: MlsClient = UniffiMlsClientFactory.create(name, key())
    var saved: ByteArray = mls.exportState()
    val received = mutableListOf<DeliveredMessage>()
    var saves = 0
    var pins: ContactPins? = ContactPins { _, _ -> PinCheck.STRANGER }
    var failSave = false
    var engine = newEngine(mls)

    fun newEngine(client: MlsClient) =
        GroupSyncEngine(
            client,
            view,
            ContactPinsSource { pins },
            SnapshotSaver {
                check(!failSave) { "disk full" }
                saved = it.copyOf()
                saves++
            },
            IncomingSink { received += it },
            SecureRandomSource { randomBytes(it) },
        )

    /** A restart: the old client goes away, the stored snapshot is restored, a new engine reads its state. */
    fun restart() {
        mls.close()
        mls = UniffiMlsClientFactory.restore(saved.copyOf(), key())
        engine = newEngine(mls)
    }

    fun keyPackage(): ByteArray = mls.createKeyPackages(1).single().keyPackage

    @Suppress("LongParameterList") // Mirrors the wire envelope.
    private fun envelope(
        kind: MessageKind,
        epoch: Long?,
        added: List<String>,
        removed: List<String>,
        welcomes: Map<String, ByteArray>,
        payload: ByteArray,
    ) = DeliveryEnvelope(randomBytes(CLIENT_MSG_ID), kind, epoch, added, removed, welcomes, payload)

    /** Creates a group at the Delivery Service with [others] as its first members; returns the group id. */
    suspend fun createGroupWith(vararg others: Party): ByteArray {
        val groupId = mls.createGroup()
        val prepared = mls.prepareAdd(groupId, others.map { it.keyPackage() })
        val devices = others.map { it.deviceId }
        val env =
            envelope(MessageKind.COMMIT, 0, devices, emptyList(), devices.associateWith { checkNotNull(prepared.welcome) }, prepared.commit)
        check(view.createGroup(groupId, env) is ApiResult.Ok)
        mls.mergePending(groupId)
        return groupId
    }

    /** Adds [other] with a commit posted at the current epoch. */
    suspend fun add(
        groupId: ByteArray,
        other: Party,
    ) {
        val prepared = mls.prepareAdd(groupId, listOf(other.keyPackage()))
        val env =
            envelope(
                MessageKind.COMMIT,
                prepared.epoch,
                listOf(other.deviceId),
                emptyList(),
                mapOf(other.deviceId to checkNotNull(prepared.welcome)),
                prepared.commit,
            )
        check(view.post(groupId, env) is ApiResult.Ok)
        mls.mergePending(groupId)
    }

    /** Removes [other] with a commit posted at the current epoch. */
    suspend fun remove(
        groupId: ByteArray,
        other: Party,
    ) {
        val key = other.mls.publicIdentity().signaturePublicKey
        val leaf = mls.members(groupId).single { it.signatureKey.contentEquals(key) }.leafIndex
        val prepared = mls.prepareRemove(groupId, listOf(leaf))
        val env = envelope(MessageKind.COMMIT, prepared.epoch, emptyList(), listOf(other.deviceId), emptyMap(), prepared.commit)
        check(view.post(groupId, env) is ApiResult.Ok)
        mls.mergePending(groupId)
    }

    /** Posts a commit that adds [other] but whose envelope lies about the numbers. */
    suspend fun addWithEnvelope(
        groupId: ByteArray,
        other: Party,
        extraDevice: String,
    ) {
        val prepared = mls.prepareAdd(groupId, listOf(other.keyPackage()))
        val devices = listOf(other.deviceId, extraDevice)
        val welcomes = devices.associateWith { checkNotNull(prepared.welcome) }
        check(
            view.post(
                groupId,
                envelope(MessageKind.COMMIT, prepared.epoch, devices, emptyList(), welcomes, prepared.commit),
            ) is ApiResult.Ok,
        )
        mls.mergePending(groupId)
    }

    suspend fun say(
        groupId: ByteArray,
        text: String,
    ): Long {
        val sealed = mls.encrypt(groupId, text.encodeToByteArray())
        val posted = view.post(groupId, envelope(MessageKind.APPLICATION, null, emptyList(), emptyList(), emptyMap(), sealed.message))
        return (posted as ApiResult.Ok).value.seq
    }

    private fun key() = ByteArray(KEY) { 7 }

    private companion object {
        const val KEY = 32
        const val CLIENT_MSG_ID = 16
    }
}
