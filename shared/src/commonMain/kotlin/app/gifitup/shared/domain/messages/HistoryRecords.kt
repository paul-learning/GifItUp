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
    ) {
        val message = stored.message
        val name = cipher.messageName(message.groupId.bytes, message.reference.bytes)
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
                cipher.seal(name, HistoryRecordCodec.encode(StoredMessage(plain, stored.arrival)))
            }
        records.put(folder, hex(name), sealed)
    }

    /** Deletes the oldest records of the group beyond [max]; one that can't be deleted stays for the next insert. */
    suspend fun evict(
        folder: String,
        items: MutableList<StoredMessage>,
        max: Int,
    ) {
        while (items.size > max) {
            val oldest = items.first().message
            val name = hex(cipher.messageName(oldest.groupId.bytes, oldest.reference.bytes))
            try {
                records.delete(folder, name)
            } catch (_: RecordStoreException) {
                return
            }
            items.removeAt(0)
        }
    }
}
