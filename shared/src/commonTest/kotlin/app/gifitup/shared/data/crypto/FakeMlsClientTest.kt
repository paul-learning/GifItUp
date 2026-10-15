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
        val malformed = "state:x:alice:notanumber".encodeToByteArray()
        assertCode(MlsErrorCode.SnapshotInvalid) { FakeMlsClient.Factory.restore(malformed, key()) }
    }

    @Test
    fun exceptionsCarryOnlyTheCode() {
        val e = MlsException(MlsErrorCode.SnapshotAuth)
        assertNull(e.message)
        assertNull(e.cause)
    }
}
