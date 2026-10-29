package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How much history is kept (ADR-0017): per group, never across groups, and at most [maxGroups] groups. */
class HistoryLimits(
    val maxPerGroup: Int = DEFAULT_PER_GROUP,
    val maxGroups: Int = DEFAULT_GROUPS,
) {
    init {
        require(maxPerGroup > 0 && maxGroups > 0)
    }

    companion object {
        /** Android's limit; the web keeps fewer (ADR-0017, owner decision 2026-10-29). */
        const val DEFAULT_PER_GROUP = 1000
        const val DEFAULT_GROUPS = 50
    }
}

/** A closed history failure (no message, no cause, nothing of what was stored). */
class HistoryStoreException(
    val kind: Kind,
) : Exception() {
    enum class Kind {
        /** The record store could not read or write. Transient or not, the caller decides; nothing was lost silently. */
        STORAGE,

        /** The cipher refused: a defect or a poisoned module (the web shares one with MLS), not a storage problem. */
        CIPHER,
    }
}

/**
 * [MessageStore] over a [RecordStore] and a [HistoryCipher] (ADR-0017): one sealed record per message, so the plaintext
 * (sender, time, state, GIF link, caption) exists only in memory. A group is read and opened once, the first time it is
 * asked for, and kept decrypted in memory from then on.
 *
 * - `insert` is idempotent per (group, reference), first one seen wins, and writes the record **before** the message
 *   becomes visible. A store failure throws [HistoryStoreException] and nothing is half done: the caller must not treat
 *   the message as stored (the sync engine reprocesses it, ADR-0017).
 * - A message the cipher refuses to seal because the record would be too big is kept as [MessageBody.Unavailable]
 *   (ADR-0017: content over the codec's cap is `Unavailable` before it gets here, so this is a defect check); it never
 *   becomes a storage failure that blocks the group.
 * - Retention is per group (the oldest of that group goes), and a new group over [HistoryLimits.maxGroups] gets no
 *   records; nothing ever evicts another group's history. A delete that fails leaves the record for the next insert to
 *   remove: a crash leaves more, never fewer.
 * - A record that doesn't open or read is **counted** ([unreadable]), never shown, never deleted, never logged.
 */
class EncryptedMessageStore(
    private val cipher: HistoryCipher,
    private val records: RecordStore,
    private val limits: HistoryLimits = HistoryLimits(),
) : MessageStore {
    private val history = HistoryRecords(cipher, records)
    private val mutex = Mutex()
    private val groups = HashMap<KeyBytes, GroupLog>()
    private var folders: MutableSet<String>? = null

    /** One group's decrypted messages in arrival order, and what could not be read. */
    private class GroupLog(
        val folder: String,
        val items: MutableList<StoredMessage>,
        var unreadable: Int,
        var nextArrival: Long,
    )

    override suspend fun insert(message: ChatMessage): Boolean =
        mutex.withLock {
            guarded {
                val log = logFor(message.groupId, create = true) ?: return@guarded false
                if (log.items.any { it.message.reference == message.reference }) return@guarded false
                val stored = StoredMessage(message, log.nextArrival)
                history.write(log.folder, stored)
                log.nextArrival++
                log.items += stored
                known().add(log.folder)
                history.evict(log.folder, log.items, limits.maxPerGroup)
                true
            }
        }

    override suspend fun setState(
        groupId: KeyBytes,
        reference: KeyBytes,
        state: DeliveryState,
        seq: Long?,
    ) {
        mutex.withLock {
            guarded {
                val log = logFor(groupId, create = false) ?: return@guarded
                val index = log.items.indexOfFirst { it.message.reference == reference }
                val old = log.items.getOrNull(index)?.takeIf { it.message.state != null } ?: return@guarded
                val m = old.message
                val updated = StoredMessage(ChatMessage(m.groupId, m.reference, m.sender, seq ?: m.seq, m.body, state, m.at), old.arrival)
                history.write(log.folder, updated)
                log.items[index] = updated
            }
        }
    }

    override suspend fun messages(groupId: KeyBytes): List<ChatMessage> =
        mutex.withLock { guarded { logFor(groupId, create = false)?.items?.map { it.message }.orEmpty() } }

    override suspend fun forget(groupId: KeyBytes) {
        mutex.withLock {
            guarded {
                val folder = hex(cipher.groupName(groupId.bytes))
                records.deleteFolder(folder)
                groups.remove(groupId)
                known().remove(folder)
            }
        }
    }

    /** How many records of the group could not be opened or read (0 for a group nothing is known of). */
    suspend fun unreadable(groupId: KeyBytes): Int = mutex.withLock { guarded { logFor(groupId, create = false)?.unreadable ?: 0 } }

    /** Deletes the whole history and forgets what was loaded ("reset history", the wipe). */
    suspend fun deleteAll() {
        mutex.withLock {
            guarded {
                records.deleteAll()
                groups.clear()
                folders = mutableSetOf()
            }
        }
    }

    private suspend fun logFor(
        groupId: KeyBytes,
        create: Boolean,
    ): GroupLog? {
        groups[groupId]?.let { return it }
        val folder = hex(cipher.groupName(groupId.bytes))
        val exists = folder in known()
        val allowed = exists || (create && known().size < limits.maxGroups)
        return if (allowed) load(groupId, folder).also { groups[groupId] = it } else null
    }

    private suspend fun known(): MutableSet<String> = folders ?: records.folders().toMutableSet().also { folders = it }

    private suspend fun load(
        groupId: KeyBytes,
        folder: String,
    ): GroupLog {
        val items = mutableListOf<StoredMessage>()
        var unreadable = 0
        for (name in records.list(folder)) {
            val stored = history.read(groupId, folder, name)
            if (stored == null) unreadable++ else items += stored
        }
        items.sortWith(compareBy<StoredMessage> { it.arrival }.thenBy { hex(it.message.reference.bytes) })
        return GroupLog(folder, items, unreadable, (items.maxOfOrNull { it.arrival } ?: -1L) + 1)
    }

    /** Store and cipher failures leave as [HistoryStoreException]; nothing else about them escapes. */
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (_: RecordStoreException) {
            throw HistoryStoreException(HistoryStoreException.Kind.STORAGE)
        } catch (_: MlsException) {
            throw HistoryStoreException(HistoryStoreException.Kind.CIPHER)
        }
}
