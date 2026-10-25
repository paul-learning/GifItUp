@file:Suppress("LongParameterList") // The transaction's context travels as parameters.

package app.gifitup.server.delivery

import app.gifitup.server.db.DeliveryRepository
import app.gifitup.server.db.GroupMemberRow
import app.gifitup.server.db.GroupRow
import app.gifitup.server.db.NewMessage
import app.gifitup.server.db.StoredMessage
import app.gifitup.server.db.TransactionRunner
import app.gifitup.server.db.WelcomeRow
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.util.UUID

/** Why a Delivery Service call was refused. The route maps each to one closed answer (ADR-0015). */
enum class Refusal {
    INVALID,
    TOO_LARGE,
    NOT_FOUND,
    EPOCH_CONFLICT,
    ENVELOPE_CONFLICT,
    GROUP_FULL,
    WELCOME_LIMIT,
    GROUP_LIMIT,
    RATE_LIMITED,
}

/** The answer of a call: a value, or the one [Refusal] that stopped it. */
sealed interface Delivery<out T> {
    data class Ok<T>(
        val value: T,
    ) : Delivery<T>

    data class Refused(
        val reason: Refusal,
    ) : Delivery<Nothing>
}

/** An accepted post: the message's [seq] and the group's [epoch] afterwards. */
data class Posted(
    val seq: Long,
    val epoch: Long,
)

/** Thrown inside a transaction to undo it and answer with [reason]. */
private class Rollback(
    val reason: Refusal,
) : RuntimeException(null, null, false, false)

class Fetched(
    val oldestSeq: Long,
    val lastSeq: Long,
    val messages: List<StoredMessage>,
)

class GroupStatus(
    val epoch: Long,
    val oldestSeq: Long,
    val lastSeq: Long,
    val member: GroupMemberRow,
)

/**
 * The Delivery Service (ADR-0015). HTTP-agnostic: the device always comes from the session, never from a request. A
 * post runs in one transaction under the group's row lock, so seq order is commit order. Logs only `event=` names with
 * closed reasons: no group, device, account or bytes (rule 4).
 */
@Suppress("TooManyFunctions") // One function per endpoint plus the shared transaction steps.
class DeliveryService(
    private val db: TransactionRunner,
    private val clock: Clock,
    private val limits: DeliveryLimits,
    private val rules: DeliveryRules = DeliveryRules(),
) {
    private val repo = DeliveryRepository()
    private val log = LoggerFactory.getLogger(DeliveryService::class.java)

    /** Creates the group with its first commit (epoch 0, at least one added device). */
    internal fun createGroup(
        device: UUID,
        groupId: ByteArray,
        envelope: Envelope,
    ): Delivery<Posted> =
        guarded(spendCreate(device)) {
            try {
                db.transaction { c ->
                    rules.createLimit(c, repo, device)?.let { throw Rollback(it.reason) }
                    createRefusal(envelope)?.let { throw Rollback(it.reason) }
                    if (!repo.insertGroup(c, groupId, device, clock.instant())) throw Rollback(Refusal.INVALID)
                    repo.insertMember(c, groupId, device, 0L, null, clock.instant())
                    // The group exists only if its first commit is accepted: a refusal rolls the whole transaction back.
                    append(c, groupId, checkNotNull(repo.lockGroup(c, groupId)), device, envelope).also {
                        if (it is Delivery.Refused) throw Rollback(it.reason)
                    }
                }
            } catch (e: Rollback) {
                Delivery.Refused(e.reason)
            }
        }.also { logOutcome("create_group", it) }

    /** Appends a commit or an application message to the group the device belongs to. */
    internal fun post(
        device: UUID,
        groupId: ByteArray,
        envelope: Envelope,
    ): Delivery<Posted> =
        guarded(spendPost(device, envelope)) {
            db.transaction { c ->
                repo.lockGroup(c, groupId)?.let { append(c, groupId, it, device, envelope) } ?: Delivery.Refused(Refusal.NOT_FOUND)
            }
        }.also { logOutcome("post", it) }

    /** Messages above the larger of [after] and the device's join point, at most [limit]. */
    fun fetch(
        device: UUID,
        groupId: ByteArray,
        after: Long,
        limit: Int,
    ): Delivery<Fetched> =
        read(device, groupId) { c, group, member ->
            val messages = withinByteBudget(repo.messages(c, groupId, maxOf(after, member.joinedSeq), limit))
            Fetched(repo.oldestSeq(c, groupId) ?: group.nextSeq, group.nextSeq - 1, messages)
        }

    fun status(
        device: UUID,
        groupId: ByteArray,
    ): Delivery<GroupStatus> =
        read(device, groupId) { c, group, member ->
            GroupStatus(group.epoch, repo.oldestSeq(c, groupId) ?: group.nextSeq, group.nextSeq - 1, member)
        }

    /** Moves the device's acknowledged position forward; [seq] beyond the group's newest message is invalid. */
    fun ack(
        device: UUID,
        groupId: ByteArray,
        seq: Long,
    ): Delivery<Unit> =
        guarded(limits.reads.spend(device.toString())) {
            db.transaction { c ->
                val group = repo.group(c, groupId)
                when {
                    group == null || repo.member(c, groupId, device) == null -> Delivery.Refused(Refusal.NOT_FOUND)
                    seq < 0 || seq >= group.nextSeq -> Delivery.Refused(Refusal.INVALID)
                    else -> Delivery.Ok(repo.ack(c, groupId, device, seq, clock.instant()))
                }
            }
        }

    /** Drops the device's own membership without a commit (ADR-0015); an empty group goes with it. */
    fun leave(
        device: UUID,
        groupId: ByteArray,
    ): Delivery<Unit> =
        guarded(limits.reads.spend(device.toString())) {
            db.transaction { c ->
                if (repo.lockGroup(c, groupId) == null || repo.deleteMembers(c, groupId, listOf(device)) == 0) {
                    Delivery.Refused(Refusal.NOT_FOUND)
                } else {
                    Delivery.Ok(repo.deleteGroupIfEmpty(c, groupId))
                }
            }
        }

    fun welcomes(device: UUID): Delivery<List<WelcomeRow>> =
        guarded(limits.reads.spend(device.toString())) { db.transaction { c -> Delivery.Ok(repo.welcomes(c, device, MAX_WELCOMES)) } }

    fun ackWelcome(
        device: UUID,
        id: UUID,
    ): Delivery<Unit> =
        guarded(limits.reads.spend(device.toString())) {
            db.transaction { c ->
                if (repo.deleteWelcome(c, id, device)) Delivery.Ok(Unit) else Delivery.Refused(Refusal.NOT_FOUND)
            }
        }

    /** At most [MAX_FETCH_BYTES] of payload per page (always at least one message): the page limit alone is a count. */
    private fun withinByteBudget(messages: List<StoredMessage>): List<StoredMessage> {
        var total = 0L
        val page =
            messages.takeWhile { message ->
                (total + message.payload.size <= MAX_FETCH_BYTES).also { total += message.payload.size }
            }
        return page.ifEmpty { messages.take(1) }
    }

    private fun <T> read(
        device: UUID,
        groupId: ByteArray,
        block: (Connection, GroupRow, GroupMemberRow) -> T,
    ): Delivery<T> =
        guarded(limits.reads.spend(device.toString())) {
            db.transaction { c ->
                val group = repo.group(c, groupId)
                val member = group?.let { repo.member(c, groupId, device) }
                if (group == null || member == null) Delivery.Refused(Refusal.NOT_FOUND) else Delivery.Ok(block(c, group, member))
            }
        }

    private fun <T> guarded(
        allowed: Boolean,
        block: () -> Delivery<T>,
    ): Delivery<T> = if (allowed) block() else Delivery.Refused(Refusal.RATE_LIMITED)

    private fun spendPost(
        device: UUID,
        envelope: Envelope,
    ): Boolean = limits.posts.spend(device.toString()) && (!envelope.isCommit || limits.commits.spend(device.toString()))

    private fun spendCreate(device: UUID): Boolean =
        listOf(limits.creates, limits.posts, limits.commits).all { it.spend(device.toString()) }

    private fun createRefusal(envelope: Envelope): Delivery.Refused? =
        if (isFirstCommit(envelope)) null else Delivery.Refused(Refusal.INVALID)

    /** The first commit of a group: epoch 0, adds at least one device, removes nobody. */
    private fun isFirstCommit(envelope: Envelope): Boolean =
        envelope.isCommit && envelope.epoch == 0L && envelope.removed.isEmpty() && envelope.added.isNotEmpty()

    /** The group row is locked. Checks everything, then appends the message and applies a commit. */
    private fun append(
        c: Connection,
        id: ByteArray,
        group: GroupRow,
        device: UUID,
        envelope: Envelope,
    ): Delivery<Posted> {
        // A retry comes first: the first answer stays true even if that commit removed the sender itself.
        return retryOf(c, id, group, device, envelope) ?: run {
            val members = repo.memberDevices(c, id)
            val refusal =
                if (device in
                    members
                ) {
                    rules.check(c, repo, id, group, device, members, envelope, clock.instant())
                } else {
                    Refusal.NOT_FOUND
                }
            if (refusal != null) Delivery.Refused(refusal) else Delivery.Ok(apply(c, id, group, device, members, envelope))
        }
    }

    /** A repeat of a message this device sent: the first answer, or a conflict if the envelope differs. */
    private fun retryOf(
        c: Connection,
        id: ByteArray,
        group: GroupRow,
        device: UUID,
        envelope: Envelope,
    ): Delivery<Posted>? =
        repo.sent(c, id, device, envelope.clientMsgId)?.let { prior ->
            if (prior.kind == envelope.kind && MessageDigest.isEqual(prior.hash, envelope.hash())) {
                Delivery.Ok(Posted(prior.seq, prior.epoch?.plus(1) ?: group.epoch))
            } else {
                Delivery.Refused(Refusal.ENVELOPE_CONFLICT)
            }
        }

    private fun apply(
        c: Connection,
        id: ByteArray,
        group: GroupRow,
        device: UUID,
        members: Set<UUID>,
        envelope: Envelope,
    ): Posted {
        val now = clock.instant()
        val seq = group.nextSeq
        repo.appendMessage(
            c,
            id,
            NewMessage(
                seq,
                device,
                envelope.kind,
                envelope.epoch,
                envelope.added,
                envelope.removed,
                envelope.clientMsgId,
                envelope.hash(),
                envelope.payload,
            ),
            now,
        )
        repo.recordSend(c, id, device, seq, envelope.clientMsgId)
        val epoch = if (envelope.isCommit) group.epoch + 1 else group.epoch
        if (envelope.isCommit) {
            repo.deleteMembers(c, id, envelope.removed.filter { it in members })
            envelope.added.forEach { target ->
                repo.insertMember(c, id, target, seq, device, now)
                repo.insertWelcome(c, UUID.randomUUID(), id, target, device, envelope.welcomes.getValue(target), now)
            }
            repo.deleteGroupIfEmpty(c, id)
        }
        repo.advanceGroup(c, id, seq + 1, epoch, repo.retained(c, id).second)
        return Posted(seq, epoch)
    }

    private fun logOutcome(
        event: String,
        outcome: Delivery<*>,
    ) {
        val result = (outcome as? Delivery.Refused)?.reason?.name?.lowercase() ?: "ok"
        log.info("event=delivery_{} outcome={}", event, result)
    }

    companion object {
        internal const val MAX_WELCOMES = 20
        internal const val MAX_FETCH_BYTES = 256L * 1024
    }
}
