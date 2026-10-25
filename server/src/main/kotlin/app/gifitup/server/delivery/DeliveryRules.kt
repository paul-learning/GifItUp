@file:Suppress("LongParameterList") // The transaction's context travels as parameters.

package app.gifitup.server.delivery

import app.gifitup.server.db.DeliveryRepository
import app.gifitup.server.db.GroupRow
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The caps of ADR-0015. Defaults are the production values; tests pass smaller ones. */
class DeliveryRules(
    private val maxGroupDevices: Int = MAX_GROUP_DEVICES,
    private val maxMessagesPerGroup: Long = MAX_MESSAGES_PER_GROUP,
    private val maxGroupBytes: Long = MAX_GROUP_BYTES,
    private val maxSenderBytes: Long = MAX_SENDER_BYTES,
    private val addWindow: Duration = ADD_WINDOW,
) {
    /** A device may have created at most 100 live groups and belong to at most 300. */
    internal fun createLimit(
        c: Connection,
        repo: DeliveryRepository,
        device: UUID,
    ): Delivery.Refused? =
        if (repo.createdGroups(c, device) >= MAX_CREATED_GROUPS || repo.memberships(c, device) >= MAX_MEMBERSHIPS) {
            Delivery.Refused(Refusal.GROUP_LIMIT)
        } else {
            null
        }

    /**
     * The first rule [envelope] breaks in the locked group, or null. [members] are the group's current member devices
     * and [device] is one of them. Order: envelope shape and epoch, then membership changes, then the storage caps.
     */
    internal fun check(
        c: Connection,
        repo: DeliveryRepository,
        id: ByteArray,
        group: GroupRow,
        device: UUID,
        members: Set<UUID>,
        envelope: Envelope,
        now: Instant,
    ): Refusal? =
        commitRefusal(c, repo, group, device, members, envelope, now)
            ?: storageRefusal(c, repo, id, group, device, envelope)

    private fun commitRefusal(
        c: Connection,
        repo: DeliveryRepository,
        group: GroupRow,
        device: UUID,
        members: Set<UUID>,
        envelope: Envelope,
        now: Instant,
    ): Refusal? =
        if (!envelope.isCommit) {
            null
        } else if (envelope.epoch != group.epoch) {
            Refusal.EPOCH_CONFLICT
        } else {
            membershipRefusal(c, repo, group, device, members, envelope)
                ?: addLimitRefusal(c, repo, device, envelope, now)
        }

    /**
     * `removed` is a current member (the sender itself, or anyone if the sender is the creator) or a ghost, which any
     * member may name and which needs no existence check (ADR-0015). `added` are existing devices that are not members.
     */
    private fun membershipRefusal(
        c: Connection,
        repo: DeliveryRepository,
        group: GroupRow,
        device: UUID,
        members: Set<UUID>,
        envelope: Envelope,
    ): Refusal? {
        val removedMembers = envelope.removed.filter { it in members }
        val removalOk = removedMembers.all { it == device } || group.creator == device
        val addOk =
            device !in envelope.added && envelope.added.none { it in members } &&
                repo.existingDevices(c, envelope.added) == envelope.added.toSet()
        val sizeOk = members.size - removedMembers.size + envelope.added.size <= maxGroupDevices
        return if (removalOk && addOk && sizeOk) null else Refusal.INVALID
    }

    private fun addLimitRefusal(
        c: Connection,
        repo: DeliveryRepository,
        device: UUID,
        envelope: Envelope,
        now: Instant,
    ): Refusal? {
        val groupLimited =
            envelope.added.any {
                repo.memberships(c, it) >= MAX_MEMBERSHIPS || repo.recentAdds(c, device, it, now.minus(addWindow)) >= MAX_ADDS_PER_TARGET
            }
        val welcomeBytes = envelope.welcomes.values.sumOf { it.size.toLong() }
        val welcomeLimited =
            envelope.added.any {
                repo.pendingFor(c, it) >= MAX_PENDING_PER_TARGET || repo.pendingFrom(c, device, it) >= MAX_PENDING_PER_PAIR
            } ||
                repo.pendingBytesFrom(c, device) + welcomeBytes > MAX_ADDER_WELCOME_BYTES ||
                repo.pendingBytes(c) + welcomeBytes > MAX_TOTAL_WELCOME_BYTES
        return when {
            groupLimited -> Refusal.GROUP_LIMIT
            welcomeLimited -> Refusal.WELCOME_LIMIT
            else -> null
        }
    }

    private fun storageRefusal(
        c: Connection,
        repo: DeliveryRepository,
        id: ByteArray,
        group: GroupRow,
        device: UUID,
        envelope: Envelope,
    ): Refusal? {
        val size = envelope.payload.size
        val full =
            repo.messageCount(c, id) >= maxMessagesPerGroup || group.retainedBytes + size > maxGroupBytes ||
                repo.bytesBySender(c, device) + size > maxSenderBytes
        return if (full) Refusal.GROUP_FULL else null
    }

    companion object {
        const val MAX_GROUP_DEVICES = 50
        const val MAX_MESSAGES_PER_GROUP = 5_000L
        const val MAX_GROUP_BYTES = 2L * 1024 * 1024
        const val MAX_SENDER_BYTES = 8L * 1024 * 1024
        const val MAX_CREATED_GROUPS = 100L
        const val MAX_MEMBERSHIPS = 300L
        const val MAX_ADDS_PER_TARGET = 3L
        const val MAX_PENDING_PER_TARGET = 20L
        const val MAX_PENDING_PER_PAIR = 3L
        const val MAX_ADDER_WELCOME_BYTES = 1L * 1024 * 1024
        const val MAX_TOTAL_WELCOME_BYTES = 64L * 1024 * 1024
        val ADD_WINDOW: Duration = Duration.ofDays(1)
    }
}
