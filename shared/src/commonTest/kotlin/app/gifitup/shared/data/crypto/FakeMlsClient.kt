package app.gifitup.shared.data.crypto

/**
 * Test double for [MlsClient]: no cryptography, but the same username rule, limits, size caps, close
 * behaviour, key handling and error codes as the real client (ADR-0007), so tests built on it fail the same
 * way. Only [MlsException] ever escapes.
 */
class FakeMlsClient private constructor(
    private val username: String,
    private val keyTag: String,
    private val keyPackagesHeld: MutableList<ByteArray> = mutableListOf(),
) : MlsClient {
    private var closed = false

    override fun publicIdentity(): PublicIdentity = live { PublicIdentity(username, signatureKey(username)) }

    override fun createKeyPackages(count: Int): List<CreatedKeyPackage> =
        live {
            if (count !in 1..MAX_PER_CALL || keyPackagesHeld.size + count > MAX_HELD) fail(MlsErrorCode.InvalidInput)
            List(count) {
                val bytes = "kp:$username:${keyPackagesHeld.size}".encodeToByteArray()
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
            if (parts.size != 3 || parts[0] != "kp" || parts[1] != expectedUsername) fail(MlsErrorCode.InvalidKeyPackage)
            PublicIdentity(expectedUsername, signatureKey(expectedUsername))
        }

    /** "Encrypted" with the key: restoring with another key fails like the real client (SnapshotAuth). */
    override fun exportState(): ByteArray = live { "state:$keyTag:$username:${keyPackagesHeld.size}".encodeToByteArray() }

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
            return FakeMlsClient(username, tag, MutableList(held) { "kp:$username:$it".encodeToByteArray() })
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

        fun isValidUsername(name: String) = USERNAME.matches(name)

        fun signatureKey(username: String) = "sig:$username".encodeToByteArray()

        fun fail(code: MlsErrorCode): Nothing = throw MlsException(code)
    }
}
