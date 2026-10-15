package app.gifitup.shared.data.crypto

/** What others may know about a device: the username in its credential and its signature key. */
class PublicIdentity(
    val username: String,
    val signaturePublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PublicIdentity &&
            username == other.username &&
            signaturePublicKey.contentEquals(other.signaturePublicKey)

    override fun hashCode(): Int = 31 * username.hashCode() + signaturePublicKey.contentHashCode()

    override fun toString(): String = "PublicIdentity(username=$username)"
}

/** A new own KeyPackage: bytes to upload, its reference and its expiry (Unix seconds, #14). */
class CreatedKeyPackage(
    val keyPackage: ByteArray,
    val keyPackageRef: ByteArray,
    val notAfterEpochSeconds: Long,
)

/**
 * Mirrors `ErrorCode` in `crypto/src/error.rs`. The bindings map the native codes by type (uniffi) or by exact
 * name (web); anything they don't recognise means the native side is in an unknown state and becomes
 * [Poisoned] (ADR-0007). On the web, a bug in the wrapper's own Kotlin code outside a call into the wasm
 * module is reported as [Internal] and leaves the module usable; one inside such a call can't be told apart
 * from a trap and becomes [Poisoned] (#66 SEC-10).
 */
enum class MlsErrorCode {
    InvalidInput,
    InvalidUsername,
    InvalidKeyPackage,
    UnknownGroup,
    Mls,
    SnapshotInvalid,
    SnapshotVersion,
    SnapshotAuth,
    Poisoned,
    Internal,
}

/**
 * The only exception [MlsClient] throws. It carries a closed code and nothing else: no message
 * and no cause, so no input or internal state can reach logs (ADR-0007, security rule 4).
 */
class MlsException(
    val code: MlsErrorCode,
) : Exception() {
    override fun toString(): String = "MlsException(code=$code)"
}
