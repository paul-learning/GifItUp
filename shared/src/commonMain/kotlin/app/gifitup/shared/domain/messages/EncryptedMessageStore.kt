package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.chat.ReadMarkStore
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
 *   becomes visible. It returns `false` for a message already stored (also one whose record can't be read: a record is
 *   never overwritten) and for a new group over the limit. A store
 *   failure throws [HistoryStoreException] and nothing is half done: the caller must not treat the message as stored (the
 *   sync engine reprocesses it, ADR-0017).
 * - A message the cipher refuses to seal because the record would be too big is kept as [MessageBody.Unavailable]
 *   (ADR-0017: content over the codec's cap is `Unavailable` before it gets here, so this is a defect check); it never
 *   becomes a storage failure that blocks the group.
 * - Retention is per group (the oldest of that group goes), and a new group over [HistoryLimits.maxGroups] gets no
 *   records; nothing ever evicts another group's history. A delete that fails leaves the record for the next insert to
 *   remove: a crash leaves more, never fewer.
 * - A record store that can't be **read** while a group loads is a [HistoryStoreException] for that group (nothing is cached
 *   half loaded; the next call tries again): receive is reprocessed by the sync engine, not skipped.
 * - The read mark of a group (the newest message shown, [ReadMarkStore]) is one more sealed record in the group's folder,
 *   under its own name; it is not a message, so a load neither shows nor counts it. A mark is only written for a group
 *   that is held and for a message that is held, so it can't create a folder past the limit or outlive [forget].
 * - A record that doesn't open or read is **counted** ([unreadable]), never shown, never deleted, never logged.
 */
@Suppress("TooManyFunctions") // One locked log behind two ports (messages and the read mark): splitting would share the lock by hand.
class EncryptedMessageStore(
    private val cipher: HistoryCipher,
    private val records: RecordStore,
    private val limits: HistoryLimits = HistoryLimits(),
) : MessageStore,
    ReadMarkStore {
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
        /** The names of every record in the folder, readable or not: an unreadable record is never overwritten. */
        val names: MutableSet<String> = mutableSetOf(),
    )

    override suspend fun insert(message: ChatMessage): Boolean =
        mutex.withLock {
            guarded {
                val log = logFor(message.groupId, create = true) ?: return@guarded false
                val name = hex(cipher.messageName(message.groupId.bytes, message.reference.bytes))
                if (name in log.names || log.items.any { it.message.reference == message.reference }) return@guarded false
                // What was written is what is shown, also when the record had to be stored as unavailable (SEC-21).
                val stored = history.write(log.folder, StoredMessage(message, log.nextArrival))
                log.nextArrival++
                log.names += name
                log.items += stored
                known().add(log.folder)
                groups[message.groupId] = log
                // The message is durable and visible now: a failure while tidying up must not make the insert fail.
                try {
                    history.evict(log.folder, log.items, log.names, limits.maxPerGroup)
                } catch (_: MlsException) {
                    // The next insert evicts again.
                }
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
                log.items[index] = history.write(log.folder, updated)
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

    override suspend fun newestSeen(group: KeyBytes): KeyBytes? =
        mutex.withLock {
            guarded {
                val log = logFor(group, create = false) ?: return@guarded null
                val name = cipher.readMarkName(group.bytes)
                val reference = history.readMark(log.folder, name)?.let(::KeyBytes)
                reference?.takeIf { ref -> log.items.any { it.message.reference == ref } }
            }
        }

    override suspend fun markSeen(
        group: KeyBytes,
        reference: KeyBytes,
    ) {
        mutex.withLock {
            guarded {
                val log = logFor(group, create = false) ?: return@guarded
                if (log.items.none { it.message.reference == reference }) return@guarded
                history.writeMark(log.folder, cipher.readMarkName(group.bytes), reference.bytes)
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
        return when {
            exists -> load(groupId, folder).also { groups[groupId] = it }

            // A group with no record yet is not remembered until its first record is durable: a failed first write must
            // not let a group past the limit later (SEC-19).
            create && known().size < limits.maxGroups -> GroupLog(folder, mutableListOf(), 0, 0L)

            else -> null
        }
    }

    private suspend fun known(): MutableSet<String> = folders ?: records.folders().toMutableSet().also { folders = it }

    private suspend fun load(
        groupId: KeyBytes,
        folder: String,
    ): GroupLog {
        val items = mutableListOf<StoredMessage>()
        val markName = hex(cipher.readMarkName(groupId.bytes))
        // The read mark is not a message: it is neither opened as one, nor counted, nor evicted.
        val names = records.list(folder).filter { it != markName }
        var unreadable = 0
        for (name in names) {
            val stored = history.read(groupId, folder, name)
            if (stored == null) unreadable++ else items += stored
        }
        items.sortWith(compareBy<StoredMessage> { it.arrival }.thenBy { hex(it.message.reference.bytes) })
        return GroupLog(folder, items, unreadable, (items.maxOfOrNull { it.arrival } ?: -1L) + 1, names.toMutableSet())
    }

    /** Store and cipher failures leave as [HistoryStoreException]; nothing else about them escapes. */
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (_: RecordStoreException) {
            throw HistoryStoreException(HistoryStoreException.Kind.STORAGE)
        } catch (_: MlsException) {
            throw HistoryStoreException(HistoryStoreException.Kind.CIPHER)
        } catch (_: IllegalStateException) {
            // A message the codec can't encode (a reference of the wrong size): a defect, closed like the cipher's.
            throw HistoryStoreException(HistoryStoreException.Kind.CIPHER)
        }
}
