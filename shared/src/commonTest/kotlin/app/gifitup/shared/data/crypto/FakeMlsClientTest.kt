package app.gifitup.shared.data.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FakeMlsClientTest {
    private fun key() = ByteArray(32) { 1 }

    private fun assertCode(
        code: MlsErrorCode,
        block: () -> Unit,
    ) = assertEquals(code, assertFailsWith<MlsException> { block() }.code)

    @Test
    fun enforcesTheUsernameRuleAndKeyLength() {
        assertCode(MlsErrorCode.InvalidUsername) { FakeMlsClient.Factory.create("Alice", key()) }
        assertCode(MlsErrorCode.InvalidInput) { FakeMlsClient.Factory.create("alice", ByteArray(16)) }
    }

    @Test
    fun signAuthFollowsTheCratesInputRules() {
        val alice = FakeMlsClient.Factory.create("alice", key())
        val challenge = ByteArray(32) { 3 }
        val origin = "https://gifitup.example.org"
        val device = "123e4567-e89b-12d3-a456-426614174000"

        alice.signAuth(AuthPurpose.Register, challenge, origin, "alice")
        alice.signAuth(AuthPurpose.Login, challenge, "https://localhost:8443", device)
        assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Register, challenge, origin, "bob") }
        assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, challenge, origin, device.uppercase()) }
        assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, ByteArray(15), origin, device) }
        assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, ByteArray(65), origin, device) }
        listOf("$origin/", "https://GifItUp.example.org", "http://gifitup.example.org", "$origin:443", "$origin:65536", "$origin:0443")
            .forEach { bad -> assertCode(MlsErrorCode.InvalidInput) { alice.signAuth(AuthPurpose.Login, challenge, bad, device) } }
    }

    @Test
    fun zeroesTheSnapshotKey() {
        val key = key()
        FakeMlsClient.Factory.create("alice", key).close()
        assertContentEquals(ByteArray(32), key)
    }

    @Test
    fun keyPackagesRoundTripThroughInspect() {
        val alice = FakeMlsClient.Factory.create("alice", key())
        val bob = FakeMlsClient.Factory.create("bob", key())
        val kp = bob.createKeyPackages(1).single()
        assertEquals(bob.publicIdentity(), alice.inspectKeyPackage(kp.keyPackage, "bob"))
        assertCode(MlsErrorCode.InvalidKeyPackage) { alice.inspectKeyPackage(kp.keyPackage, "carol") }
        assertCode(MlsErrorCode.InvalidInput) { bob.createKeyPackages(0) }
        assertCode(MlsErrorCode.InvalidInput) { alice.inspectKeyPackage(ByteArray(16 * 1024 + 1), "bob") }
    }

    @Test
    fun closedClientRefusesEveryCall() {
        val alice = FakeMlsClient.Factory.create("alice", key())
        alice.close()
        assertCode(MlsErrorCode.Poisoned) { alice.exportState() }
    }

    @Test
    fun exportAndRestoreKeepTheIdentity() {
        val alice = FakeMlsClient.Factory.create("alice", key())
        alice.createKeyPackages(2)
        val restored = FakeMlsClient.Factory.restore(alice.exportState(), key())
        assertEquals(alice.publicIdentity(), restored.publicIdentity())
        assertCode(MlsErrorCode.SnapshotInvalid) { FakeMlsClient.Factory.restore(byteArrayOf(1), key()) }
        val otherKey = ByteArray(32) { 2 }
        assertCode(MlsErrorCode.SnapshotAuth) { FakeMlsClient.Factory.restore(alice.exportState(), otherKey) }
        val tag = key().contentHashCode()
        for (count in listOf("notanumber", "-1", "201")) {
            val malformed = "state:$tag:alice:$count".encodeToByteArray()
            assertCode(MlsErrorCode.SnapshotInvalid) { FakeMlsClient.Factory.restore(malformed, key()) }
        }
    }

    @Test
    fun exceptionsCarryOnlyTheCode() {
        val e = MlsException(MlsErrorCode.SnapshotAuth)
        assertNull(e.message)
        assertNull(e.cause)
    }
}
