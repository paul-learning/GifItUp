package app.gifitup.shared.domain.messages

import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [MessageStore] in memory. Bounded, because any member can send as many messages as the server relays: the newest
 * [MAX_PER_GROUP] messages of each of at most [MAX_GROUPS] groups. A message that was pushed out and arrives again
 * counts as new.
 */
class InMemoryMessageStore : HistoryAccess {
    private val mutex = Mutex()
    private val groups = LinkedHashMap<KeyBytes, LinkedHashMap<KeyBytes, ChatMessage>>()

    override suspend fun retry() = Unit

    override suspend fun usable(): Boolean = true

    override suspend fun canReset(): Boolean = false

    override suspend fun reset(): ResetResult = ResetResult.UNAVAILABLE

    override suspend fun unreadable(groupId: KeyBytes): Int = 0

    override suspend fun insert(message: ChatMessage): Boolean =
        mutex.withLock {
            val known = groups[message.groupId]
            if (known == null && groups.size >= MAX_GROUPS) return@withLock false
            val log = known ?: LinkedHashMap<KeyBytes, ChatMessage>().also { groups[message.groupId] = it }
            if (message.reference in log) return@withLock false
            log[message.reference] = message
            if (log.size > MAX_PER_GROUP) log.remove(log.keys.first())
            true
        }

    override suspend fun setState(
        groupId: KeyBytes,
        reference: KeyBytes,
        state: DeliveryState,
        seq: Long?,
    ) {
        mutex.withLock {
            val log = groups[groupId] ?: return
            val old = log[reference]?.takeIf { it.state != null } ?: return
            log[reference] = ChatMessage(old.groupId, old.reference, old.sender, seq ?: old.seq, old.body, state, old.at)
        }
    }

    override suspend fun messages(groupId: KeyBytes): List<ChatMessage> = mutex.withLock { groups[groupId]?.values?.toList().orEmpty() }

    override suspend fun forget(groupId: KeyBytes) {
        mutex.withLock { groups.remove(groupId) }
    }

    companion object {
        const val MAX_PER_GROUP = 1000
        const val MAX_GROUPS = 50
    }
}
