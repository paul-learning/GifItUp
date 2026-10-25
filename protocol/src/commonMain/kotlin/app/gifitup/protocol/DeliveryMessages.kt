package app.gifitup.protocol

import kotlinx.serialization.Serializable

// Delivery Service (ADR-0015). All endpoints need a session; the caller's device comes from the session, never from a
// body. Byte fields are MLS bytes in canonical base64url without padding, ids are canonical UUID strings, a group id is
// 16..64 random bytes in base64url. The server never parses a payload or a welcome. Every field is required
// (ADR-0006 A7); nullable fields are `null` when they do not apply.

/** Message kinds on the wire (`kind` of [PostMessageRequest] and [GroupMessage]). */
object DeliveryKinds {
    const val COMMIT: String = "commit"
    const val APPLICATION: String = "application"

    /** Only in [SocketPush]: a new pending welcome for this device. */
    const val WELCOME: String = "welcome"
}

/**
 * One Welcome for one added device. Each added device gets exactly one, of at most 16 KiB decoded (21,846 characters).
 */
@Serializable
data class WelcomeItem(
    val deviceId: String,
    val welcome: String,
)

/**
 * `POST /groups/{groupId}/messages` and the commit inside [CreateGroupRequest]. [clientMsgId] is 16 random bytes that
 * make a retry safe: a repeat of the same envelope returns the first answer, a repeat with another envelope is `409
 * envelope_conflict`. For [kind] `commit`: [epoch] is the epoch the commit applies to, [added] and [removed] list the
 * device ids it changes ([removed] may name a ghost, ADR-0015), [welcomes] has exactly one item per added device.
 * For [kind] `application`: [epoch] is `null` and the three lists are empty. [payload] is at most 2 KiB decoded for
 * an application message and 16 KiB for a commit. The whole envelope is checked first and rejected as
 * `400 envelope_invalid` on any violation, with nothing applied. `409 epoch_conflict` when a commit's epoch is not
 * the group's current epoch, `409 group_full`, `409 welcome_limit`, `409 group_limit`, `404 not_found` for a
 * non-member, `429 rate_limited`, `413 payload_too_large`.
 */
@Serializable
data class PostMessageRequest(
    val clientMsgId: String,
    val kind: String,
    val epoch: Long?,
    val added: List<String>,
    val removed: List<String>,
    val welcomes: List<WelcomeItem>,
    val payload: String,
)

/** `POST /groups`: creates the group with its first commit (epoch 0), which adds at least one device. */
@Serializable
data class CreateGroupRequest(
    val groupId: String,
    val commit: PostMessageRequest,
)

/** Answer to a post and to a create: the message's [seq] in the group's log and the group's [epoch] afterwards. */
@Serializable
data class PostMessageResponse(
    val seq: Long,
    val epoch: Long,
)

/**
 * One retained message. [senderDeviceId] is `null` when the sender's device no longer exists. The client checks
 * [added] and [removed] against the commit it processes and flags any mismatch (ADR-0002).
 */
@Serializable
data class GroupMessage(
    val seq: Long,
    val senderDeviceId: String?,
    val kind: String,
    val epoch: Long?,
    val added: List<String>,
    val removed: List<String>,
    val payload: String,
)

/**
 * `GET /groups/{groupId}/messages?after=<seq>&limit=<1..100>`: messages with `seq` greater than the larger of `after`
 * and the caller's join point, in order. [oldestSeq] is the first retained seq of the group (the next seq when nothing is
 * retained): a client whose cursor, plus one, is below it has a gap and must be re-added (ADR-0015). [lastSeq] is the
 * group's newest seq.
 */
@Serializable
data class FetchMessagesResponse(
    val oldestSeq: Long,
    val lastSeq: Long,
    val messages: List<GroupMessage>,
)

/** `POST /groups/{groupId}/ack`: the caller has processed everything up to [seq] (monotonic, at most `lastSeq`). */
@Serializable
data class AckRequest(
    val seq: Long,
)

/**
 * `GET /groups/{groupId}`: the caller's view. [lastSentSeq] and [lastClientMsgId] are the caller's own last accepted
 * message (the freshness anchor of ADR-0015); both are `null` before its first send.
 */
@Serializable
data class GroupStatusResponse(
    val epoch: Long,
    val oldestSeq: Long,
    val lastSeq: Long,
    val ackedSeq: Long,
    val lastSentSeq: Long?,
    val lastClientMsgId: String?,
)

/** One pending Welcome for the caller's device. */
@Serializable
data class PendingWelcome(
    val id: String,
    val groupId: String,
    val welcome: String,
)

/** `GET /welcomes`: the caller's pending welcomes, oldest first, at most 20. */
@Serializable
data class PendingWelcomesResponse(
    val welcomes: List<PendingWelcome>,
)

/**
 * WebSocket frames (ADR-0015). The client's first frame is [SocketHello]; after it the server sends [SocketPush]
 * (a hint, never content) and the client sends nothing but pongs.
 */
@Serializable
data class SocketHello(
    val v: Int,
    val token: String,
)

/** A hint that [groupId] has a new message [seq]; [kind] [DeliveryKinds.WELCOME] (with `seq` 0) hints at a new pending welcome. */
@Serializable
data class SocketPush(
    val groupId: String,
    val seq: Long,
    val kind: String,
)

/** Close codes of the WebSocket (ADR-0006 A2; 4400 and 4401 are ours). */
object SocketCloseCodes {
    const val CLIENT_OUTDATED: Int = 4001
    const val SERVER_OUTDATED: Int = 4002
    const val BAD_HANDSHAKE: Int = 4400
    const val UNAUTHORIZED: Int = 4401
}
