package app.gifitup.shared.data.crypto

/**
 * Test double for [MlsClient]: no cryptography, but the same username rule, limits, close
 * behaviour and error codes as the real client (ADR-0007), so tests built on it fail the same way.
 */
class FakeMlsClient private constructor(
    private val username: String,
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
            if (!isValidUsername(expectedUsername)) fail(MlsErrorCode.InvalidInput)
            val owner =
                keyPackage
                    .decodeToString()
                    .split(":")
                    .takeIf { it.size == 3 && it[0] == "kp" }
                    ?.get(1)
            if (owner != expectedUsername) fail(MlsErrorCode.InvalidKeyPackage)
            PublicIdentity(expectedUsername, signatureKey(expectedUsername))
        }

    override fun exportState(): ByteArray = live { "state:$username:${keyPackagesHeld.size}".encodeToByteArray() }

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
            val keyOk = snapshotKey.size == KEY_SIZE
            snapshotKey.fill(0)
            if (!keyOk) fail(MlsErrorCode.InvalidInput)
            if (!isValidUsername(username)) fail(MlsErrorCode.InvalidUsername)
            return FakeMlsClient(username)
        }

        override fun restore(
            state: ByteArray,
            snapshotKey: ByteArray,
        ): MlsClient {
            val keyOk = snapshotKey.size == KEY_SIZE
            snapshotKey.fill(0)
            if (!keyOk) fail(MlsErrorCode.InvalidInput)
            val parts = state.decodeToString().split(":")
            if (parts.size != 3 || parts[0] != "state") fail(MlsErrorCode.SnapshotInvalid)
            val held = MutableList(parts[2].toInt()) { "kp:${parts[1]}:$it".encodeToByteArray() }
            return FakeMlsClient(parts[1], held)
        }
    }

    private companion object {
        const val MAX_PER_CALL = 100
        const val MAX_HELD = 200
        const val KEY_SIZE = 32
        const val REF_SIZE = 32
        const val NOT_AFTER = 4_102_444_800L // 2100-01-01
        val USERNAME = Regex("^[a-z0-9_]{3,32}$")

        fun isValidUsername(name: String) = USERNAME.matches(name)

        fun signatureKey(username: String) = "sig:$username".encodeToByteArray()

        fun fail(code: MlsErrorCode): Nothing = throw MlsException(code)
    }
}
