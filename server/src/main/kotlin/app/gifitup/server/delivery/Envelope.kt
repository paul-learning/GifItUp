package app.gifitup.server.delivery

import app.gifitup.protocol.DeliveryKinds
import app.gifitup.protocol.PostMessageRequest
import app.gifitup.protocol.WelcomeItem
import app.gifitup.server.auth.Base64Url
import app.gifitup.server.db.DeliveryRepository
import app.gifitup.server.invites.parseCanonicalUuid
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Size limits of ADR-0015, in decoded bytes. */
internal object DeliveryLimitsBytes {
    const val APPLICATION_PAYLOAD = 2 * 1024
    const val COMMIT_PAYLOAD = 16 * 1024
    const val WELCOME = 16 * 1024
    const val CLIENT_MSG_ID = 16
    const val MIN_GROUP_ID = 16
    const val MAX_GROUP_ID = 64
    const val MAX_CHANGED_DEVICES = 10
}

/** A parsed envelope: bytes decoded, ids canonical, sizes within the limits. Nothing about group state is checked yet. */
@Suppress("LongParameterList") // One property per wire field.
internal class Envelope(
    val clientMsgId: ByteArray,
    val kind: Int,
    val epoch: Long?,
    val added: List<UUID>,
    val removed: List<UUID>,
    val welcomes: Map<UUID, ByteArray>,
    val payload: ByteArray,
) {
    val isCommit: Boolean get() = kind == DeliveryRepository.COMMIT

    /** SHA-256 over a canonical, length-prefixed encoding of the whole envelope (ADR-0015, retries). */
    fun hash(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte(kind)
            data.writeBoolean(epoch != null)
            data.writeLong(epoch ?: 0L)
            data.writeInt(added.size)
            added.forEach { data.writeUuid(it) }
            data.writeInt(removed.size)
            removed.forEach { data.writeUuid(it) }
            data.writeInt(welcomes.size)
            welcomes.entries.sortedBy { it.key }.forEach { (id, welcome) ->
                data.writeUuid(id)
                data.writeInt(welcome.size)
                data.write(welcome)
            }
            data.writeInt(payload.size)
            data.write(payload)
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
    }
}

private fun DataOutputStream.writeUuid(id: UUID) {
    writeLong(id.mostSignificantBits)
    writeLong(id.leastSignificantBits)
}

/** Why a request body is not an acceptable envelope. */
internal sealed interface EnvelopeParse {
    data class Ok(
        val envelope: Envelope,
    ) : EnvelopeParse

    /** A byte field or id is malformed, or a list is wrong for the kind. */
    data object Invalid : EnvelopeParse

    /** A payload or welcome is over its cap. */
    data object TooLarge : EnvelopeParse
}

internal object EnvelopeParser {
    fun parse(request: PostMessageRequest): EnvelopeParse =
        when (request.kind) {
            DeliveryKinds.COMMIT -> fields(request, DeliveryRepository.COMMIT, DeliveryLimitsBytes.COMMIT_PAYLOAD)
            DeliveryKinds.APPLICATION -> fields(request, DeliveryRepository.APPLICATION, DeliveryLimitsBytes.APPLICATION_PAYLOAD)
            else -> EnvelopeParse.Invalid
        }

    private fun fields(
        request: PostMessageRequest,
        kind: Int,
        payloadCap: Int,
    ): EnvelopeParse {
        val clientMsgId = Base64Url.decode(request.clientMsgId, DeliveryLimitsBytes.CLIENT_MSG_ID)
        val payload = Base64Url.decodeUpTo(request.payload, payloadCap)
        val added = ids(request.added)
        val removed = ids(request.removed)
        val welcomes = welcomes(request.welcomes)
        return when {
            clientMsgId == null || added == null || removed == null -> {
                EnvelopeParse.Invalid
            }

            payload == null -> {
                tooLargeOrInvalid(request.payload.length > Base64Url.encodedLength(payloadCap))
            }

            welcomes == null -> {
                tooLargeOrInvalid(
                    request.welcomes.any {
                        it.welcome.length >
                            Base64Url.encodedLength(DeliveryLimitsBytes.WELCOME)
                    },
                )
            }

            !shapeOk(request, kind, added, removed) || !listsOk(request, added, removed, welcomes) -> {
                EnvelopeParse.Invalid
            }

            else -> {
                EnvelopeParse.Ok(Envelope(clientMsgId, kind, request.epoch, added, removed, welcomes, payload))
            }
        }
    }

    private fun shapeOk(
        request: PostMessageRequest,
        kind: Int,
        added: List<UUID>,
        removed: List<UUID>,
    ): Boolean =
        if (kind == DeliveryRepository.COMMIT) {
            request.epoch.let { it != null && it >= 0 } && added.size + removed.size in 1..DeliveryLimitsBytes.MAX_CHANGED_DEVICES
        } else {
            request.epoch == null && added.isEmpty() && removed.isEmpty() && request.welcomes.isEmpty()
        }

    /** No duplicates, no overlap, and exactly one welcome per added device. */
    private fun listsOk(
        request: PostMessageRequest,
        added: List<UUID>,
        removed: List<UUID>,
        welcomes: Map<UUID, ByteArray>,
    ): Boolean =
        added.toSet().size == added.size && removed.toSet().size == removed.size && added.intersect(removed.toSet()).isEmpty() &&
            welcomes.keys == added.toSet() && welcomes.size == request.welcomes.size

    private fun ids(texts: List<String>): List<UUID>? =
        texts
            .map(::parseCanonicalUuid)
            .takeIf { list ->
                list.none {
                    it == null
                }
            }?.filterNotNull()

    private fun welcomes(items: List<WelcomeItem>): Map<UUID, ByteArray>? {
        val result = LinkedHashMap<UUID, ByteArray>()
        for (item in items) {
            val id = parseCanonicalUuid(item.deviceId)
            val welcome = Base64Url.decodeUpTo(item.welcome, DeliveryLimitsBytes.WELCOME)
            if (id == null || welcome == null || result.put(id, welcome) != null) return null
        }
        return result
    }

    private fun tooLargeOrInvalid(tooLarge: Boolean): EnvelopeParse = if (tooLarge) EnvelopeParse.TooLarge else EnvelopeParse.Invalid
}
