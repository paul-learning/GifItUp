package app.gifitup.shared.data.crypto

/**
 * Seals and opens the records of the local message history (ADR-0017). The implementation is the Rust crate's
 * `HistoryCipher` behind the platform bindings: no other module touches the history key or a sealed record's format
 * (CLAUDE.md, crypto boundary). One cipher holds one random 32-byte history key and the account's canonical origin.
 *
 * The three `...Name` functions give what the store sees of a record: 32 bytes that say nothing about the group id or
 * the message reference without the key. [seal] pads to 1 KiB buckets, binds the record to its name and the origin,
 * and refuses more than 4092 bytes of plaintext ([MlsErrorCode.HistoryInvalid]); [open] answers
 * [MlsErrorCode.HistoryAuth] for another key, name or origin or any changed byte, and [MlsErrorCode.HistoryInvalid] for
 * a size or layout no seal makes. Typed errors leave the cipher usable; [MlsErrorCode.Poisoned] does not.
 */
interface HistoryCipher : AutoCloseable {
    /** The name of the group's folder. */
    fun groupName(groupId: ByteArray): ByteArray

    /** The name of one message of a group. */
    fun messageName(
        groupId: ByteArray,
        reference: ByteArray,
    ): ByteArray

    /** The name of the group's read mark. */
    fun readMarkName(groupId: ByteArray): ByteArray

    fun seal(
        name: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    fun open(
        name: ByteArray,
        sealed: ByteArray,
    ): ByteArray
}

/** Makes a [HistoryCipher]; the platform module implements it (uniffi on Android and the JVM, wasm in the browser). */
fun interface HistoryCipherFactory {
    /** [historyKey] (32 bytes) is copied and our copy of it zeroed whatever happens; [origin] is the canonical origin. */
    fun create(
        historyKey: ByteArray,
        origin: String,
    ): HistoryCipher
}
