package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.contacts.KeyBytes

/** Reads and writes single sealed records for [EncryptedMessageStore]: the cipher, the codec and the record store. */
internal class HistoryRecords(
    private val cipher: HistoryCipher,
    private val records: RecordStore,
) {
    /** The message in one record, or `null` if it doesn't open, doesn't read, or isn't the message its name says. */
    suspend fun read(
        groupId: KeyBytes,
        folder: String,
        name: String,
    ): StoredMessage? {
        val sealed = records.get(folder, name)
        val nameBytes = unhex(name)
        val plaintext = if (sealed == null || nameBytes == null) null else open(nameBytes, sealed)
        val stored = plaintext?.let(HistoryRecordCodec::decode)
        val expected = stored?.let { hex(cipher.messageName(groupId.bytes, it.message.reference.bytes)) }
        return stored?.takeIf { it.message.groupId == groupId && expected == name }
    }

    /** The reference in the group's read-mark record, or `null` if there is none or it doesn't open. */
    suspend fun readMark(
        folder: String,
        name: ByteArray,
    ): ByteArray? {
        val plain = records.get(folder, hex(name))?.let { open(name, it) }
        return plain?.takeIf { it.size == READ_MARK_SIZE && it[0] == READ_MARK_VERSION }?.copyOfRange(1, READ_MARK_SIZE)
    }

    suspend fun writeMark(
        folder: String,
        name: ByteArray,
        reference: ByteArray,
    ) {
        records.put(folder, hex(name), cipher.seal(name, byteArrayOf(READ_MARK_VERSION) + reference))
    }

    /** `null` for a typed refusal (this record is unreadable); a poisoned cipher is the caller's matter, so it is thrown. */
    private fun open(
        name: ByteArray,
        sealed: ByteArray,
    ): ByteArray? =
        try {
            cipher.open(name, sealed)
        } catch (e: MlsException) {
            if (e.code == MlsErrorCode.HistoryAuth || e.code == MlsErrorCode.HistoryInvalid) null else throw e
        }

    /**
     * Seals and stores [stored] under the name its message makes. A record too big to seal is a defect: the message is
     * kept as [MessageBody.Unavailable] instead, so it never turns into a storage failure that blocks the group.
     */
    suspend fun write(
        folder: String,
        stored: StoredMessage,
    ): StoredMessage {
        val message = stored.message
        val name = cipher.messageName(message.groupId.bytes, message.reference.bytes)
        var written = stored
        val sealed =
            try {
                cipher.seal(name, HistoryRecordCodec.encode(stored))
            } catch (e: MlsException) {
                if (e.code != MlsErrorCode.HistoryInvalid) throw e
                val plain =
                    ChatMessage(
                        message.groupId,
                        message.reference,
                        message.sender,
                        message.seq,
                        MessageBody.Unavailable,
                        message.state,
                        message.at,
                    )
                written = StoredMessage(plain, stored.arrival)
                cipher.seal(name, HistoryRecordCodec.encode(written))
            }
        records.put(folder, hex(name), sealed)
        return written
    }

    /**
     * Deletes the oldest records of the group beyond [max]; one that can't be deleted stays for the next insert. **The newest
     * group name (`group_meta`, by arrival) is never deleted** (#217): it is what makes a chat with one other member a group, and
     * it is the first message of a chat, so retention would take it first. It is recomputed from [items] on every call (a
     * reload after a crash can hold more than [max]), and it is the one record the group may hold above [max].
     */
    suspend fun evict(
        folder: String,
        items: MutableList<StoredMessage>,
        names: MutableSet<String>,
        max: Int,
    ) {
        while (items.size > max) {
            val keep = items.indexOfLast { it.message.body is MessageBody.GroupMeta }
            val index = if (keep == 0) 1 else 0
            if (index >= items.size) return
            val oldest = items[index].message
            val name = hex(cipher.messageName(oldest.groupId.bytes, oldest.reference.bytes))
            try {
                records.delete(folder, name)
            } catch (_: RecordStoreException) {
                return
            }
            items.removeAt(index)
            names -= name
        }
    }
}

private const val READ_MARK_VERSION: Byte = 1
private const val READ_MARK_SIZE = 1 + 32
