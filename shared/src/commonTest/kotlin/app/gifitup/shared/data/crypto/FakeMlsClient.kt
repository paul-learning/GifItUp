package app.gifitup.shared.data.crypto

import app.gifitup.shared.data.network.ApiEndpoint

/**
 * Test double for [MlsClient]: no cryptography, but the same username rule, limits, size caps, close
 * behaviour, key handling and error codes as the real client (ADR-0007), so tests built on it fail the same
 * way. Only [MlsException] ever escapes.
 */
@Suppress("TooManyFunctions") // Mirrors the MlsClient interface.
class FakeMlsClient private constructor(
    private val username: String,
    private val keyTag: String,
    private val keyPackagesHeld: MutableList<ByteArray> = mutableListOf(),
) : MlsClient {
    private var closed = false
    private var created = keyPackagesHeld.size

    override fun publicIdentity(): PublicIdentity = live { PublicIdentity(username, signatureKey(username, keyTag)) }

    override fun createKeyPackages(count: Int): List<CreatedKeyPackage> =
        live {
            if (count !in 1..MAX_PER_CALL) fail(MlsErrorCode.InvalidInput)
            // At the cap the oldest held ones give way, like the real client (#121).
            repeat((keyPackagesHeld.size + count - MAX_HELD).coerceAtLeast(0)) { keyPackagesHeld.removeAt(0) }
            List(count) {
                val bytes = "kp:$username:$keyTag:${created++}".encodeToByteArray()
                keyPackagesHeld += bytes
                CreatedKeyPackage(bytes, bytes.copyOf(REF_SIZE), NOT_AFTER)
            }
        }

    override fun pruneExpiredKeyPackages(): Int = live { 0 }

    override fun inspectKeyPackage(
        keyPackage: ByteArray,
        expectedUsername: String,
    ): PublicIdentity =
        live {
            if (keyPackage.size > MAX_KEY_PACKAGE_BYTES || !isValidUsername(expectedUsername)) {
                fail(MlsErrorCode.InvalidInput)
            }
            val parts = keyPackage.decodeToString().split(":")
            if (parts.size != 4 || parts[0] != "kp" || parts[1] != expectedUsername) fail(MlsErrorCode.InvalidKeyPackage)
            PublicIdentity(expectedUsername, signatureKey(expectedUsername, parts[2]))
        }

    /**
     * Same input rule as the crate (exactly 32 bytes, else `InvalidInput`), but **not** its algorithm: the first 12 bytes
     * of the key, so a test can tell keys apart. The real vectors are tested only against the real crate in `:mls`
     * (ADR-0014); nothing hashes in Kotlin.
     */
    override fun fingerprint(signatureKey: ByteArray): ByteArray =
        live {
            if (signatureKey.size != KEY_SIZE) fail(MlsErrorCode.InvalidInput)
            signatureKey.copyOf(FingerprintEmojis.LENGTH)
        }

    /** "Encrypted" with the key: restoring with another key fails like the real client (SnapshotAuth). */
    override fun exportState(): ByteArray = live { "state:$keyTag:$username:${keyPackagesHeld.size}".encodeToByteArray() }

    /** Same input rules as the crate; the "signature" is a readable, deterministic stand-in, not a real one. */
    override fun signAuth(
        purpose: AuthPurpose,
        challenge: ByteArray,
        origin: String,
        subject: String,
    ): ByteArray =
        live {
            val subjectOk =
                when (purpose) {
                    AuthPurpose.Register -> subject == username
                    AuthPurpose.Login -> UUID.matches(subject)
                }
            if (challenge.size !in MIN_CHALLENGE..MAX_CHALLENGE || !isCanonicalOrigin(origin) || !subjectOk) {
                fail(MlsErrorCode.InvalidInput)
            }
            "sig:$purpose:${challenge.contentHashCode()}:$origin:$subject:$username".encodeToByteArray()
        }

    // Group operations need real MLS: they are covered against the real client by the contract tests in `:mls`
    // (ADR-0016). The fake refuses them like a client whose rules forbid the call.
    override fun rekeyAndExport(newKey: ByteArray): ByteArray = live { fail(MlsErrorCode.Refused) }

    override fun exportsUnderKey(): Long = live { 0 }

    override fun setAppState(state: ByteArray) = live { fail(MlsErrorCode.Refused) }

    override fun appState(): ByteArray = live { ByteArray(0) }

    override fun createGroup(): ByteArray = live { fail(MlsErrorCode.Refused) }

    override fun prepareAdd(
        groupId: ByteArray,
        keyPackages: List<ByteArray>,
    ): PreparedCommit = live { fail(MlsErrorCode.Refused) }

    override fun prepareRemove(
        groupId: ByteArray,
        leafIndexes: List<Int>,
    ): PreparedCommit = live { fail(MlsErrorCode.Refused) }

    override fun prepareUpdate(groupId: ByteArray): PreparedCommit = live { fail(MlsErrorCode.Refused) }

    override fun mergePending(groupId: ByteArray): Long = live { fail(MlsErrorCode.Refused) }

    override fun clearPending(groupId: ByteArray) = live { fail(MlsErrorCode.Refused) }

    override fun hasPendingCommit(groupId: ByteArray): Boolean = live { false }

    override fun encrypt(
        groupId: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessage = live { fail(MlsErrorCode.UnknownGroup) }

    override fun stageIncoming(
        groupId: ByteArray,
        message: ByteArray,
    ): IncomingMessage = live { fail(MlsErrorCode.UnknownGroup) }

    override fun mergeStaged(token: Int): Long = live { fail(MlsErrorCode.UnknownToken) }

    override fun discardStaged(token: Int) = live { fail(MlsErrorCode.UnknownToken) }

    override fun inspectWelcome(
        welcome: ByteArray,
        expectedGroupId: ByteArray,
    ): WelcomeInfo = live { fail(MlsErrorCode.Refused) }

    override fun joinWelcome(token: Int): ByteArray = live { fail(MlsErrorCode.UnknownToken) }

    override fun discardWelcome(token: Int) = live { fail(MlsErrorCode.UnknownToken) }

    override fun forgetGroup(groupId: ByteArray) = live { fail(MlsErrorCode.UnknownGroup) }

    override fun members(groupId: ByteArray): List<LeafInfo> = live { fail(MlsErrorCode.UnknownGroup) }

    override fun memberCount(groupId: ByteArray): Int = live { fail(MlsErrorCode.UnknownGroup) }

    override fun epoch(groupId: ByteArray): Long = live { fail(MlsErrorCode.UnknownGroup) }

    override fun close() {
        closed = true
    }

    private fun <T> live(block: () -> T): T {
        if (closed) fail(MlsErrorCode.Poisoned)
        return block()
    }

    /** Factory with the real client's argument checks; the snapshot key is zeroed like the real one. */
    object Factory : MlsClientFactory {
        override fun create(
            username: String,
            snapshotKey: ByteArray,
        ): MlsClient {
            val tag = takeKey(snapshotKey)
            if (!isValidUsername(username)) fail(MlsErrorCode.InvalidUsername)
            return FakeMlsClient(username, tag)
        }

        override fun restore(
            state: ByteArray,
            snapshotKey: ByteArray,
        ): MlsClient {
            val tag = takeKey(snapshotKey)
            val parts = state.decodeToString().split(":")
            // Like the real client: authenticate first, then parse (#66 CR-9).
            if (parts.getOrNull(1) != tag) fail(if (parts.size == 4) MlsErrorCode.SnapshotAuth else MlsErrorCode.SnapshotInvalid)
            val held = parts.takeIf { it.size == 4 && it[0] == "state" }?.get(3)?.toIntOrNull()
            if (held == null || held !in 0..MAX_HELD || !isValidUsername(parts[2])) fail(MlsErrorCode.SnapshotInvalid)
            val username = parts[2]
            return FakeMlsClient(username, tag, MutableList(held) { "kp:$username:$tag:$it".encodeToByteArray() })
        }

        /** Checks the length, derives a tag that identifies the key, and zeroes the caller's copy. */
        private fun takeKey(snapshotKey: ByteArray): String {
            val ok = snapshotKey.size == KEY_SIZE
            val tag = snapshotKey.contentHashCode().toString()
            snapshotKey.fill(0)
            if (!ok) fail(MlsErrorCode.InvalidInput)
            return tag
        }
    }

    private companion object {
        const val MAX_PER_CALL = 100
        const val MAX_HELD = 200
        const val MAX_KEY_PACKAGE_BYTES = 16 * 1024
        const val KEY_SIZE = 32
        const val REF_SIZE = 32
        const val NOT_AFTER = 4_102_444_800L // 2100-01-01
        val USERNAME = Regex("^[a-z0-9_]{3,32}$")
        const val MIN_CHALLENGE = 16
        const val MAX_CHALLENGE = 64

        /** The crate's canonical-origin rule, through the client's copy (tested against the shared cases, #94 CR-4). */
        fun isCanonicalOrigin(origin: String): Boolean = ApiEndpoint.isCanonicalOrigin(origin)

        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun isValidUsername(name: String) = USERNAME.matches(name)

        /** Depends on the snapshot key too, like a real key pair would: a new key means a new public key (#95 SEC-3). */
        fun signatureKey(
            username: String,
            keyTag: String,
        ): ByteArray {
            // 32 bytes like a real Ed25519 key (the fingerprint rule), still a deterministic function of name and tag.
            val seed = "sig:$username:$keyTag".encodeToByteArray()
            return ByteArray(KEY_SIZE) { i -> (seed[i % seed.size] + i / seed.size * (i + 1)).toByte() }
        }

        fun fail(code: MlsErrorCode): Nothing = throw MlsException(code)
    }
}
