package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.GroupState
import app.gifitup.shared.data.network.MessagePage
import app.gifitup.shared.data.network.PendingWelcomeEntry
import app.gifitup.shared.data.network.PostedMessage

/**
 * The Delivery Service as the sync engine needs it (ADR-0015). The app's implementation is [ApiDeliveryPort]; tests use
 * an in-memory fake. Everything it returns is untrusted and already shape-checked by the API client.
 */
interface DeliveryPort {
    suspend fun createGroup(
        groupId: ByteArray,
        commit: DeliveryEnvelope,
    ): ApiResult<PostedMessage>

    suspend fun post(
        groupId: ByteArray,
        envelope: DeliveryEnvelope,
    ): ApiResult<PostedMessage>

    suspend fun fetch(
        groupId: ByteArray,
        after: Long,
    ): ApiResult<MessagePage>

    suspend fun status(groupId: ByteArray): ApiResult<GroupState>

    suspend fun ack(
        groupId: ByteArray,
        seq: Long,
    ): ApiResult<Unit>

    suspend fun leave(groupId: ByteArray): ApiResult<Unit>

    suspend fun pendingWelcomes(): ApiResult<List<PendingWelcomeEntry>>

    suspend fun ackWelcome(welcomeId: String): ApiResult<Unit>
}

/** What the contact book says about one leaf (ADR-0014, ADR-0016). */
enum class PinCheck {
    /** Not a contact: a normal group member, shown as "not in your friends". */
    STRANGER,

    /** A contact, and this is one of its pinned keys. */
    PINNED,

    /** A contact (or a blocked one), and this is not a pinned key. */
    MISMATCH,
}

/** A snapshot of the contact book for one decision; synchronous, so nothing is awaited between staging and deciding. */
fun interface ContactPins {
    fun check(
        username: String,
        signatureKey: ByteArray,
    ): PinCheck
}

/** Loads the contact book. Null means the lookup is incomplete (not loaded, damaged): nothing is staged, nothing freezes. */
fun interface ContactPinsSource {
    suspend fun load(): ContactPins?
}

/**
 * Where decrypted messages go. [deliver] returns only when the message is durably stored (the cursor moves right after),
 * and must tolerate the same message twice (a crash between storing and persisting the snapshot): [messageRef] is its id.
 */
fun interface IncomingSink {
    suspend fun deliver(message: DeliveredMessage)

    /**
     * A commit this device **merged** changed the roster (never one that was discarded or held), or this device joined from
     * a welcome. Durable when it returns, and idempotent for the same change (#31). The default ignores it.
     */
    suspend fun membership(change: MembershipChange) {}
}

/**
 * The roster change of one processed commit or welcome. [by] is the MLS-authenticated sender of the commit (or of the
 * welcome); [added] and [removed] are the usernames of the leaves it changed, and [joined] the roster size when this
 * device joined (then both lists are empty). Never taken from the server's envelope.
 */
class MembershipChange(
    val groupId: ByteArray,
    val epoch: Long,
    val by: String,
    val added: List<MemberChange>,
    val removed: List<MemberChange>,
    val joined: Int = 0,
) {
    override fun toString(): String = "MembershipChange(epoch=$epoch, added=${added.size}, removed=${removed.size})"
}

class MemberChange(
    val username: String,
    val leafIndex: Int,
)

class DeliveredMessage(
    val groupId: ByteArray,
    val seq: Long,
    val senderUsername: String,
    val senderSignatureKey: ByteArray,
    val messageRef: ByteArray,
    val plaintext: ByteArray,
) {
    override fun toString(): String = "DeliveredMessage(seq=$seq)"
}

/** Stores the encrypted snapshot durably (the identity store, #16), atomically with the cursor inside it. */
fun interface SnapshotSaver {
    suspend fun save(snapshot: ByteArray)
}
