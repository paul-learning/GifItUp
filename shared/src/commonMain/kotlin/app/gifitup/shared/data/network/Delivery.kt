@file:Suppress("LongParameterList") // The wire envelope and message have one property per field.

package app.gifitup.shared.data.network

import app.gifitup.protocol.DeliveryKinds
import app.gifitup.protocol.FetchMessagesResponse
import app.gifitup.protocol.GroupMessage
import app.gifitup.protocol.GroupStatusResponse
import app.gifitup.protocol.PendingWelcomesResponse
import app.gifitup.protocol.PostMessageResponse

// The Delivery Service as the client sees it (ADR-0015). Everything the server says is untrusted: every list, id and
// size is checked here, and an answer that breaks a rule is refused whole (MALFORMED_RESPONSE), never trimmed.

enum class MessageKind { COMMIT, APPLICATION }

/**
 * One message to post. [clientMsgId] is 16 random bytes that make a retry safe (the same envelope again returns the
 * first answer). A commit carries its [epoch], the device ids it [added] and [removed], and one welcome per added
 * device in [welcomes]; an application message carries none of them. [payload] and the welcomes are opaque bytes.
 */
class DeliveryEnvelope(
    val clientMsgId: ByteArray,
    val kind: MessageKind,
    val epoch: Long?,
    val added: List<String>,
    val removed: List<String>,
    val welcomes: Map<String, ByteArray>,
    val payload: ByteArray,
)

/** The answer to a post or a create: the message's [seq] and the group's [epoch] afterwards. */
data class PostedMessage(
    val seq: Long,
    val epoch: Long,
)

/** One retained message. [senderDeviceId] is null when the sender's device is gone. */
class GroupMessageEntry(
    val seq: Long,
    val senderDeviceId: String?,
    val kind: MessageKind,
    val epoch: Long?,
    val added: List<String>,
    val removed: List<String>,
    val payload: ByteArray,
)

/** A page of messages. A cursor whose next seq is below [oldestSeq] has a gap (ADR-0015); [lastSeq] is the newest seq. */
class MessagePage(
    val oldestSeq: Long,
    val lastSeq: Long,
    val messages: List<GroupMessageEntry>,
)

/** The caller's view of a group. [lastSentSeq] and [lastClientMsgId] are both null before its first send. */
class GroupState(
    val epoch: Long,
    val oldestSeq: Long,
    val lastSeq: Long,
    val ackedSeq: Long,
    val lastSentSeq: Long?,
    val lastClientMsgId: ByteArray?,
)

/** A Welcome waiting for this device. */
class PendingWelcomeEntry(
    val id: String,
    val groupId: ByteArray,
    val welcome: ByteArray,
)

internal const val MAX_GROUP_ID_BYTES = 64
internal const val MIN_GROUP_ID_BYTES = 16
internal const val CLIENT_MSG_ID_BYTES = 16
internal const val MAX_APPLICATION_PAYLOAD = 2 * 1024
internal const val MAX_COMMIT_PAYLOAD = 16 * 1024
internal const val MAX_WELCOME_BYTES = 16 * 1024
internal const val MAX_PAGE_MESSAGES = 100
internal const val MAX_PENDING_WELCOMES = 20

/** A commit with at most 10 changed devices and a 16 KiB welcome each, plus JSON framing, fits a 256 KiB request. */
internal const val MAX_DELIVERY_BODY_BYTES = 512 * 1024

internal fun readPosted(response: PostMessageResponse): PostedMessage? =
    if (response.seq >= 1 && response.epoch >= 0) PostedMessage(response.seq, response.epoch) else null

/**
 * The checked page: at most [MAX_PAGE_MESSAGES] messages with strictly rising seq, all above [after] and at most
 * `lastSeq`, a known kind with the fields that kind has, canonical ids, payloads within the caps.
 */
internal fun readPage(
    response: FetchMessagesResponse,
    after: Long,
): MessagePage? {
    val read = response.messages.takeIf { it.size <= MAX_PAGE_MESSAGES }?.map(::readMessage)
    val messages = read?.filterNotNull()?.takeIf { it.size == read.size }
    val ordered = messages != null && messages.zipWithNext().all { (a, b) -> a.seq < b.seq } && messages.all { it.seq > after }
    val inRange = messages != null && messages.all { it.seq <= response.lastSeq }
    val rangeOk = response.oldestSeq >= 1 && response.lastSeq >= 0 && response.oldestSeq <= response.lastSeq + 1
    return if (ordered && inRange && rangeOk) MessagePage(response.oldestSeq, response.lastSeq, messages) else null
}

private fun readMessage(message: GroupMessage): GroupMessageEntry? {
    val kind = kindOf(message.kind)
    val cap = if (kind == MessageKind.COMMIT) MAX_COMMIT_PAYLOAD else MAX_APPLICATION_PAYLOAD
    val payload = Base64Url.decode(message.payload, 1..cap)
    val shapeOk = kind != null && shapeFits(kind, message) && message.seq >= 1
    val idsOk =
        message.added.all(GifItUpApi.UUID::matches) && message.removed.all(GifItUpApi.UUID::matches) &&
            (message.senderDeviceId?.let(GifItUpApi.UUID::matches) != false)
    return when {
        kind == null || payload == null -> {
            null
        }

        shapeOk && idsOk -> {
            GroupMessageEntry(
                message.seq,
                message.senderDeviceId,
                kind,
                message.epoch,
                message.added,
                message.removed,
                payload,
            )
        }

        else -> {
            null
        }
    }
}

private fun shapeFits(
    kind: MessageKind,
    message: GroupMessage,
): Boolean =
    when (kind) {
        MessageKind.COMMIT -> message.epoch.let { it != null && it >= 0 }
        MessageKind.APPLICATION -> message.epoch == null && message.added.isEmpty() && message.removed.isEmpty()
    }

private fun kindOf(wire: String): MessageKind? =
    when (wire) {
        DeliveryKinds.COMMIT -> MessageKind.COMMIT
        DeliveryKinds.APPLICATION -> MessageKind.APPLICATION
        else -> null
    }

internal fun readState(response: GroupStatusResponse): GroupState? {
    val msgId = response.lastClientMsgId?.let { Base64Url.decode(it, CLIENT_MSG_ID_BYTES..CLIENT_MSG_ID_BYTES) }
    val pairOk = (response.lastSentSeq == null) == (response.lastClientMsgId == null) && (response.lastClientMsgId == null || msgId != null)
    val rangeOk =
        response.epoch >= 0 && response.oldestSeq >= 1 && response.lastSeq >= 0 && response.oldestSeq <= response.lastSeq + 1 &&
            response.ackedSeq in 0..response.lastSeq && (response.lastSentSeq?.let { it in 1..response.lastSeq } != false)
    return if (pairOk && rangeOk) {
        GroupState(response.epoch, response.oldestSeq, response.lastSeq, response.ackedSeq, response.lastSentSeq, msgId)
    } else {
        null
    }
}

/** At most [MAX_PENDING_WELCOMES] welcomes, distinct canonical ids, group ids of 16..64 bytes, welcomes within the cap. */
internal fun readWelcomes(response: PendingWelcomesResponse): List<PendingWelcomeEntry>? {
    val read =
        response.welcomes.takeIf { it.size <= MAX_PENDING_WELCOMES }?.map {
            val group = Base64Url.decode(it.groupId, MIN_GROUP_ID_BYTES..MAX_GROUP_ID_BYTES)
            val welcome = Base64Url.decode(it.welcome, 1..MAX_WELCOME_BYTES)
            if (GifItUpApi.UUID.matches(it.id) && group != null && welcome != null) PendingWelcomeEntry(it.id, group, welcome) else null
        }
    val welcomes = read?.filterNotNull()?.takeIf { it.size == read.size }
    return welcomes?.takeIf { list -> list.map { it.id }.toSet().size == list.size }
}

internal fun DeliveryEnvelope.toWire(): app.gifitup.protocol.PostMessageRequest =
    app.gifitup.protocol.PostMessageRequest(
        clientMsgId = Base64Url.encode(clientMsgId),
        kind = if (kind == MessageKind.COMMIT) DeliveryKinds.COMMIT else DeliveryKinds.APPLICATION,
        epoch = epoch,
        added = added,
        removed = removed,
        welcomes = welcomes.map { (device, welcome) -> app.gifitup.protocol.WelcomeItem(device, Base64Url.encode(welcome)) },
        payload = Base64Url.encode(payload),
    )
