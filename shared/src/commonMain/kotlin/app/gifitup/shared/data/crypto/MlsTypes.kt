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

/** What an auth signature authorizes (ADR-0009). Closed by design: the crate signs nothing else. */
enum class AuthPurpose {
    Register,
    Login,
}

/** A new own KeyPackage: bytes to upload, its reference and its expiry (Unix seconds, #14). */
class CreatedKeyPackage(
    val keyPackage: ByteArray,
    val keyPackageRef: ByteArray,
    val notAfterEpochSeconds: Long,
)

/**
 * What the MLS tree says about one leaf: the username in its credential and its signature key. [leafIndex] is the
 * position in the tree ([UNASSIGNED_LEAF] for a leaf that a commit would add and that has no position yet). The leaf
 * has no device id; the Delivery Service's device ids are advisory only (ADR-0015, ADR-0016).
 */
class LeafInfo(
    val username: String,
    val signatureKey: ByteArray,
    val leafIndex: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is LeafInfo &&
            username == other.username &&
            signatureKey.contentEquals(other.signatureKey) &&
            leafIndex == other.leafIndex

    override fun hashCode(): Int = 31 * (31 * username.hashCode() + signatureKey.contentHashCode()) + leafIndex

    override fun toString(): String = "LeafInfo(username=$username, leafIndex=$leafIndex)"

    companion object {
        /** `u32::MAX` in the crate, read as a signed int. */
        const val UNASSIGNED_LEAF: Int = -1
    }
}

/**
 * A commit the Delivery Service has not accepted yet: post [commit] (and [welcome], if it adds anyone) in the
 * ADR-0015 envelope, then `mergePending` or `clearPending`. [epoch] is the epoch the commit applies to.
 */
class PreparedCommit(
    val commit: ByteArray,
    val welcome: ByteArray?,
    val epoch: Long,
    val added: List<LeafInfo>,
    val removed: List<LeafInfo>,
) {
    override fun toString(): String = "PreparedCommit(epoch=$epoch, added=${added.size}, removed=${removed.size})"
}

/** A sealed application message and its message reference (ADR-0006 B9, ADR-0016). */
class EncryptedMessage(
    val message: ByteArray,
    val messageRef: ByteArray,
) {
    override fun toString(): String = "EncryptedMessage(size=${message.size})"
}

/** The typed result of `stageIncoming` (ADR-0016). Nothing changes the group until a commit is merged. */
sealed interface IncomingMessage {
    /** The leaf the MLS signature authenticates; never taken from the server's envelope. */
    val sender: LeafInfo

    class Application(
        val plaintext: ByteArray,
        override val sender: LeafInfo,
        val messageRef: ByteArray,
    ) : IncomingMessage {
        override fun toString(): String = "Application(size=${plaintext.size})"
    }

    /** A commit, staged and **not applied**: decide with `mergeStaged(token)` or `discardStaged(token)`. */
    class Commit(
        val token: Int,
        override val sender: LeafInfo,
        val epoch: Long,
        val added: List<LeafInfo>,
        val removed: List<LeafInfo>,
        val selfRemoved: Boolean,
    ) : IncomingMessage {
        override fun toString(): String = "Commit(token=$token, epoch=$epoch, added=${added.size}, removed=${removed.size})"
    }
}

/** A welcome, parsed in memory and **not joined**: `joinWelcome(token)` or `discardWelcome(token)`. */
class WelcomeInfo(
    val token: Int,
    val groupId: ByteArray,
    val sender: LeafInfo,
    val members: List<LeafInfo>,
) {
    override fun toString(): String = "WelcomeInfo(token=$token, members=${members.size})"
}

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

    /** A staged or pending commit or welcome is in the way (ADR-0016). */
    PendingOperation,

    /** The message belongs to another epoch than the group's current one. */
    WrongEpoch,

    /** The message was processed before (its secret is used up). */
    Replay,

    /** The commit, welcome or proposal breaks a rule of ours (ADR-0002, ADR-0016); nothing was applied. */
    Refused,

    /** A limit of ADR-0015/ADR-0016 (leaves, changes, sizes) would be exceeded. */
    LimitExceeded,

    /** No staged commit or welcome under that token, or nothing pending. */
    UnknownToken,

    /** A history record has a size, header or inner layout that no seal makes (ADR-0017). */
    HistoryInvalid,

    /** A history record did not authenticate: another key, name or origin, or a changed byte (ADR-0017). */
    HistoryAuth,
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
