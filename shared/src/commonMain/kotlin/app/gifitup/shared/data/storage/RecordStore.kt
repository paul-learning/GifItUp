package app.gifitup.shared.data.storage

/**
 * A closed store failure: the platform could not read or write. No message, no cause, nothing of what was stored
 * (CLAUDE.md, security rule 4).
 */
class RecordStoreException : Exception()

/**
 * Where sealed history records live (ADR-0017): folders of records, both named by lower-case hex of the 32-byte names the
 * crate's `HistoryCipher` makes, so the store sees no group id and no message reference. It holds opaque bytes only; the
 * implementations are separate from the `LocalStore` of ADR-0011, which keeps nothing but wrapped keys and public values.
 *
 * Every write is atomic for its one record (a reader sees the old or the new bytes, never a mix) and durable when it
 * returns. There is **no** multi-record transaction. An implementation checks every folder and record name against
 * `[0-9a-f]{64}` before it touches storage, so no name can leave the store's place (SEC-9), and it ignores and removes
 * what a crash left behind (temporary files). Every failure is a [RecordStoreException].
 */
interface RecordStore {
    suspend fun put(
        folder: String,
        name: String,
        bytes: ByteArray,
    )

    /** The record, or `null` if there is none. */
    suspend fun get(
        folder: String,
        name: String,
    ): ByteArray?

    /** The names of the records in [folder] (none for a folder that doesn't exist). */
    suspend fun list(folder: String): List<String>

    /** Deleting what is not there is no error. */
    suspend fun delete(
        folder: String,
        name: String,
    )

    /** Deletes the folder and everything in it; what is not there is no error. */
    suspend fun deleteFolder(folder: String)

    suspend fun folders(): List<String>

    /**
     * `true` only if the store **positively** holds nothing: no folder and no record, malformed or foreign names
     * included (a leftover that [folders] would skip still counts). A listing error is a [RecordStoreException], never
     * `true`: a new history key is made on this answer, and a wrong "empty" would orphan existing history (ADR-0017).
     */
    suspend fun isEmpty(): Boolean

    /** Deletes every folder and record (a reset or a wipe). */
    suspend fun deleteAll()
}

/** The names a [RecordStore] accepts: 64 lower-case hex digits, the form of a 32-byte record name. */
fun isRecordName(name: String): Boolean = name.length == RECORD_NAME_CHARS && name.all { it in '0'..'9' || it in 'a'..'f' }

private const val RECORD_NAME_CHARS = 64
