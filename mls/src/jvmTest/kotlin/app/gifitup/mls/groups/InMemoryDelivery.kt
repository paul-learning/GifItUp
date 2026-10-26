package app.gifitup.mls.groups

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.DeliveryEnvelope
import app.gifitup.shared.data.network.GroupMessageEntry
import app.gifitup.shared.data.network.GroupState
import app.gifitup.shared.data.network.MessageKind
import app.gifitup.shared.data.network.MessagePage
import app.gifitup.shared.data.network.PendingWelcomeEntry
import app.gifitup.shared.data.network.PostedMessage
import app.gifitup.shared.domain.groups.DeliveryPort

/**
 * A Delivery Service in memory, faithful to the parts of ADR-0015 the engine relies on: per-group log with seq, epoch
 * check on commits (`EPOCH_CONFLICT`), join points, advisory membership, idempotent retries by client message id,
 * welcomes per device, acks, the freshness anchor, and `NOT_FOUND` for non-members. Test code only.
 */
class InMemoryDelivery {
    private class Logged(
        val entry: GroupMessageEntry,
        val clientMsgId: List<Byte>,
    )

    private class Member(
        var joinSeq: Long,
        var acked: Long,
        var lastSentSeq: Long? = null,
        var lastClientMsgId: ByteArray? = null,
    )

    private class Group(
        var epoch: Long = 0,
        val log: MutableList<Logged> = mutableListOf(),
        val members: MutableMap<String, Member> = mutableMapOf(),
        var oldestSeq: Long = 1,
    ) {
        val lastSeq get() = log.lastOrNull()?.entry?.seq ?: 0L
    }

    private val groups = mutableMapOf<List<Byte>, Group>()
    private val welcomes = mutableMapOf<String, MutableList<PendingWelcomeEntry>>()
    private var nextWelcome = 0

    /** Calls of any kind, newest last, to check orders such as "stored before acknowledged". */
    val calls = mutableListOf<String>()

    /** Lets a test make one kind of call fail: `"ack"` for example. */
    var failing = emptySet<String>()

    fun viewFor(deviceId: String): DeliveryPort = View(deviceId)

    /** Forgets the messages up to and including [seq] (cleanup), as the server does after acks. */
    fun trim(
        groupId: ByteArray,
        seq: Long,
    ) {
        val group = groups.getValue(groupId.toList())
        group.log.removeAll { it.entry.seq <= seq }
        group.oldestSeq = seq + 1
    }

    /** Replaces the payload of a retained message (a hostile server or a corrupted store). */
    fun tamper(
        groupId: ByteArray,
        seq: Long,
        change: (ByteArray) -> ByteArray,
    ) {
        val group = groups.getValue(groupId.toList())
        val index = group.log.indexOfFirst { it.entry.seq == seq }
        val old = group.log[index]
        val e = old.entry
        group.log[index] =
            Logged(GroupMessageEntry(e.seq, e.senderDeviceId, e.kind, e.epoch, e.added, e.removed, change(e.payload)), old.clientMsgId)
    }

    /** Changes who the log says sent a message (the label is the server's word). */
    fun relabel(
        groupId: ByteArray,
        seq: Long,
        sender: String,
    ) {
        val group = groups.getValue(groupId.toList())
        val index = group.log.indexOfFirst { it.entry.seq == seq }
        val old = group.log[index]
        val e = old.entry
        group.log[index] = Logged(GroupMessageEntry(e.seq, sender, e.kind, e.epoch, e.added, e.removed, e.payload), old.clientMsgId)
    }

    /** Appends a copy of an existing message as a new one: the server delivering a ciphertext twice. */
    fun duplicate(
        groupId: ByteArray,
        seq: Long,
    ) {
        val group = groups.getValue(groupId.toList())
        val e = group.log.first { it.entry.seq == seq }.entry
        val copy = GroupMessageEntry(group.lastSeq + 1, e.senderDeviceId, e.kind, e.epoch, e.added, e.removed, e.payload)
        group.log += Logged(copy, listOf(copy.seq.toByte()))
    }

    fun lastSeq(groupId: ByteArray): Long = groups.getValue(groupId.toList()).lastSeq

    fun ackedSeq(
        groupId: ByteArray,
        deviceId: String,
    ): Long =
        groups
            .getValue(groupId.toList())
            .members
            .getValue(deviceId)
            .acked

    private inner class View(
        private val device: String,
    ) : DeliveryPort {
        private fun <T> ensure(
            call: String,
            block: () -> ApiResult<T>,
        ): ApiResult<T> {
            calls += "$device:$call"
            return if (call in failing) ApiResult.Failed(ApiError.NETWORK) else block()
        }

        private fun member(groupId: ByteArray): Pair<Group, Member>? {
            val group = groups[groupId.toList()] ?: return null
            return group.members[device]?.let { group to it }
        }

        override suspend fun createGroup(
            groupId: ByteArray,
            commit: DeliveryEnvelope,
        ): ApiResult<PostedMessage> =
            ensure("create") {
                val key = groupId.toList()
                val existing = groups[key]
                when {
                    existing != null -> {
                        repeat(existing, commit) ?: ApiResult.Failed(ApiError.ENVELOPE_INVALID)
                    }

                    commit.epoch != 0L || commit.added.isEmpty() -> {
                        ApiResult.Failed(ApiError.ENVELOPE_INVALID)
                    }

                    else -> {
                        val group = Group().also { groups[key] = it }
                        group.members[device] = Member(joinSeq = 0, acked = 0)
                        append(group, commit)
                    }
                }
            }

        override suspend fun post(
            groupId: ByteArray,
            envelope: DeliveryEnvelope,
        ): ApiResult<PostedMessage> =
            ensure("post") {
                val found = member(groupId)
                when {
                    found == null -> ApiResult.Failed(ApiError.NOT_FOUND)
                    else -> repeat(found.first, envelope) ?: apply(found.first, found.second, envelope)
                }
            }

        private fun repeat(
            group: Group,
            envelope: DeliveryEnvelope,
        ): ApiResult<PostedMessage>? {
            val id = envelope.clientMsgId.toList()
            val first = group.log.firstOrNull { it.clientMsgId == id && it.entry.senderDeviceId == device } ?: return null
            return ApiResult.Ok(PostedMessage(first.entry.seq, group.epoch))
        }

        private fun apply(
            group: Group,
            sender: Member,
            envelope: DeliveryEnvelope,
        ): ApiResult<PostedMessage> {
            val isCommit = envelope.kind == MessageKind.COMMIT
            return when {
                isCommit && envelope.epoch != group.epoch -> {
                    ApiResult.Failed(ApiError.EPOCH_CONFLICT)
                }

                isCommit && (envelope.added.toSet().size != envelope.added.size || envelope.added.any { it in group.members }) -> {
                    ApiResult.Failed(ApiError.ENVELOPE_INVALID)
                }

                isCommit && envelope.welcomes.keys != envelope.added.toSet() -> {
                    ApiResult.Failed(ApiError.ENVELOPE_INVALID)
                }

                else -> {
                    append(group, envelope).also { result ->
                        val seq = (result as ApiResult.Ok).value.seq
                        sender.lastSentSeq = seq
                        sender.lastClientMsgId = envelope.clientMsgId
                    }
                }
            }
        }

        private fun append(
            group: Group,
            envelope: DeliveryEnvelope,
        ): ApiResult<PostedMessage> {
            val seq = group.lastSeq + 1
            val epoch = if (envelope.kind == MessageKind.COMMIT) envelope.epoch else null
            val entry = GroupMessageEntry(seq, device, envelope.kind, epoch, envelope.added, envelope.removed, envelope.payload)
            group.log += Logged(entry, envelope.clientMsgId.toList())
            group.members[device]?.apply {
                lastSentSeq = seq
                lastClientMsgId = envelope.clientMsgId
            }
            if (envelope.kind == MessageKind.COMMIT) {
                group.epoch += 1
                envelope.removed.forEach { group.members.remove(it) }
                for (added in envelope.added) {
                    group.members[added] = Member(joinSeq = seq, acked = seq)
                    welcomes.getOrPut(added) { mutableListOf() } +=
                        PendingWelcomeEntry("welcome-${nextWelcome++}", groupIdOf(group), envelope.welcomes.getValue(added))
                }
            }
            return ApiResult.Ok(PostedMessage(seq, group.epoch))
        }

        private fun groupIdOf(group: Group): ByteArray =
            groups.entries
                .first { it.value === group }
                .key
                .toByteArray()

        override suspend fun fetch(
            groupId: ByteArray,
            after: Long,
        ): ApiResult<MessagePage> =
            ensure("fetch") {
                val found = member(groupId)
                if (found == null) {
                    ApiResult.Failed(ApiError.NOT_FOUND)
                } else {
                    val (group, me) = found
                    val from = maxOf(after, me.joinSeq)
                    val messages =
                        group.log
                            .map { it.entry }
                            .filter { it.seq > from }
                            .take(PAGE)
                    ApiResult.Ok(MessagePage(group.oldestSeq, group.lastSeq, messages))
                }
            }

        override suspend fun status(groupId: ByteArray): ApiResult<GroupState> =
            ensure("status") {
                val found = member(groupId)
                if (found == null) {
                    ApiResult.Failed(ApiError.NOT_FOUND)
                } else {
                    val (group, me) = found
                    ApiResult.Ok(GroupState(group.epoch, group.oldestSeq, group.lastSeq, me.acked, me.lastSentSeq, me.lastClientMsgId))
                }
            }

        override suspend fun ack(
            groupId: ByteArray,
            seq: Long,
        ): ApiResult<Unit> =
            ensure("ack") {
                val found = member(groupId)
                if (found == null) {
                    ApiResult.Failed(ApiError.NOT_FOUND)
                } else {
                    found.second.acked = maxOf(found.second.acked, seq.coerceAtMost(found.first.lastSeq))
                    ApiResult.Ok(Unit)
                }
            }

        override suspend fun leave(groupId: ByteArray): ApiResult<Unit> =
            ensure("leave") {
                groups[groupId.toList()]?.members?.remove(device)
                ApiResult.Ok(Unit)
            }

        override suspend fun pendingWelcomes(): ApiResult<List<PendingWelcomeEntry>> =
            ensure("welcomes") { ApiResult.Ok(welcomes[device].orEmpty().toList()) }

        override suspend fun ackWelcome(welcomeId: String): ApiResult<Unit> =
            ensure("ackWelcome") {
                welcomes[device]?.removeAll { it.id == welcomeId }
                ApiResult.Ok(Unit)
            }
    }

    private companion object {
        const val PAGE = 100
    }
}
