package app.gifitup.mls

import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.LeafInfo
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group lifecycle (ADR-0016) through the bindings, on the JVM (uniffi) and in headless Chrome (wasm-bindgen).
 * The rules themselves are tested in `crypto/`; this checks the typed results and closed errors cross unchanged, and
 * that both platforms see the same message reference for the same bytes.
 */
class MlsGroupContractTest {
    private fun key() = ByteArray(KEY_SIZE) { 7 }

    private fun assertCode(
        code: MlsErrorCode,
        block: () -> Unit,
    ) {
        val e = assertFailsWith<MlsException> { block() }
        assertEquals(code, e.code)
        assertNull(e.message, "no detail crosses the FFI")
    }

    /** Alice creates a group and adds Bob, who joins from the welcome. */
    private suspend fun withPair(test: (alice: MlsClient, bob: MlsClient, groupId: ByteArray) -> Unit) {
        val factory = platformMlsClientFactory()
        factory.create("alice", key()).use { alice ->
            factory.create("bob", key()).use { bob ->
                val groupId = alice.createGroup()
                val prepared = alice.prepareAdd(groupId, listOf(bob.createKeyPackages(1).single().keyPackage))
                alice.mergePending(groupId)
                val welcome = checkNotNull(prepared.welcome)
                bob.joinWelcome(bob.inspectWelcome(welcome, groupId).token)
                test(alice, bob, groupId)
            }
        }
    }

    @Test
    fun aGroupIsCreatedAndAMemberAddedFromAWelcome() =
        runTest {
            val factory = platformMlsClientFactory()
            factory.create("alice", key()).use { alice ->
                factory.create("bob", key()).use { bob ->
                    val groupId = alice.createGroup()
                    assertEquals(GROUP_ID_SIZE, groupId.size)
                    assertEquals(1, alice.memberCount(groupId))
                    val prepared = alice.prepareAdd(groupId, listOf(bob.createKeyPackages(1).single().keyPackage))
                    assertEquals(0L, prepared.epoch)
                    assertEquals(listOf("bob"), prepared.added.map { it.username })
                    assertEquals(LeafInfo.UNASSIGNED_LEAF, prepared.added.single().leafIndex)
                    assertTrue(prepared.removed.isEmpty())
                    assertTrue(alice.hasPendingCommit(groupId))
                    assertEquals(1L, alice.mergePending(groupId))
                    assertFalse(alice.hasPendingCommit(groupId))

                    val info = bob.inspectWelcome(checkNotNull(prepared.welcome), groupId)
                    assertEquals("alice", info.sender.username)
                    assertEquals(listOf("alice", "bob"), info.members.map { it.username }.sorted())
                    assertContentEquals(groupId, bob.joinWelcome(info.token))
                    assertEquals(2, bob.memberCount(groupId))
                    assertEquals(1L, bob.epoch(groupId))
                    assertEquals(alice.members(groupId).map { it.username }, bob.members(groupId).map { it.username })
                    assertCode(MlsErrorCode.UnknownToken) { bob.joinWelcome(info.token) }
                }
            }
        }

    @Test
    fun anApplicationMessageNamesItsSenderAndHasTheSameReferenceOnBothSides() =
        runTest {
            withPair { alice, bob, groupId ->
                val sealed = alice.encrypt(groupId, "hello".encodeToByteArray())
                assertEquals(REF_SIZE, sealed.messageRef.size)
                val incoming = assertIs<IncomingMessage.Application>(bob.stageIncoming(groupId, sealed.message))
                assertEquals("hello", incoming.plaintext.decodeToString())
                assertEquals("alice", incoming.sender.username)
                assertContentEquals(sealed.messageRef, incoming.messageRef)
                assertCode(MlsErrorCode.Replay) { bob.stageIncoming(groupId, sealed.message) }
            }
        }

    @Test
    fun aStagedCommitChangesNothingUntilMergedAndBlocksExport() =
        runTest {
            withPair { alice, bob, groupId ->
                val prepared = alice.prepareUpdate(groupId)
                val staged = assertIs<IncomingMessage.Commit>(bob.stageIncoming(groupId, prepared.commit))
                assertEquals(1L, staged.epoch)
                assertEquals("alice", staged.sender.username)
                assertFalse(staged.selfRemoved)
                assertEquals(1L, bob.epoch(groupId))
                assertCode(MlsErrorCode.PendingOperation) { bob.exportState() }
                assertEquals(2L, bob.mergeStaged(staged.token))
                assertEquals(2L, alice.mergePending(groupId))
                assertTrue(bob.exportState().isNotEmpty())
                assertCode(MlsErrorCode.UnknownToken) { bob.mergeStaged(staged.token) }
            }
        }

    @Test
    fun aRemovedMemberLearnsItAndAClearedCommitLeavesNoPendingState() =
        runTest {
            withPair { alice, bob, groupId ->
                val bobLeaf = alice.members(groupId).single { it.username == "bob" }.leafIndex
                val prepared = alice.prepareRemove(groupId, listOf(bobLeaf))
                assertEquals(listOf("bob"), prepared.removed.map { it.username })
                alice.clearPending(groupId)
                assertFalse(alice.hasPendingCommit(groupId))
                assertEquals(1L, alice.epoch(groupId))

                val again = alice.prepareRemove(groupId, listOf(bobLeaf))
                val staged = assertIs<IncomingMessage.Commit>(bob.stageIncoming(groupId, again.commit))
                assertTrue(staged.selfRemoved)
                bob.mergeStaged(staged.token)
                alice.mergePending(groupId)
                bob.forgetGroup(groupId)
                assertCode(MlsErrorCode.UnknownGroup) { bob.epoch(groupId) }
            }
        }

    @Test
    fun badInputsAreClosedErrors() =
        runTest {
            val factory = platformMlsClientFactory()
            factory.create("alice", key()).use { alice ->
                val groupId = alice.createGroup()
                assertCode(MlsErrorCode.UnknownGroup) { alice.epoch(ByteArray(GROUP_ID_SIZE)) }
                assertCode(MlsErrorCode.LimitExceeded) { alice.prepareAdd(groupId, emptyList()) }
                assertCode(MlsErrorCode.InvalidInput) { alice.prepareRemove(groupId, listOf(-1)) }
                assertCode(MlsErrorCode.UnknownToken) { alice.discardStaged(1) }
                assertCode(MlsErrorCode.UnknownToken) { alice.clearPending(groupId) }
                assertCode(MlsErrorCode.InvalidKeyPackage) { alice.prepareAdd(groupId, listOf(ByteArray(10))) }
                assertCode(MlsErrorCode.InvalidInput) { alice.encrypt(groupId, ByteArray(MAX_CONTENT + 1)) }
                assertCode(MlsErrorCode.UnknownGroup) { alice.encrypt(ByteArray(GROUP_ID_SIZE), ByteArray(1)) }
            }
        }

    @Test
    fun appStateTravelsInTheSnapshotAndRekeyingSwitchesTheKey() =
        runTest {
            val factory = platformMlsClientFactory()
            factory.create("alice", key()).use { alice ->
                alice.setAppState(byteArrayOf(1, 2, 3))
                factory.restore(alice.exportState(), key()).use { restored ->
                    assertContentEquals(byteArrayOf(1, 2, 3), restored.appState())
                }
                assertCode(MlsErrorCode.LimitExceeded) { alice.setAppState(ByteArray(MAX_APP_STATE + 1)) }
                assertCode(MlsErrorCode.InvalidInput) { alice.rekeyAndExport(key()) }

                val newKey = ByteArray(KEY_SIZE) { 9 }
                val rekeyed = alice.rekeyAndExport(newKey)
                assertContentEquals(ByteArray(KEY_SIZE), newKey)
                assertEquals(1L, alice.exportsUnderKey())
                assertCode(MlsErrorCode.SnapshotAuth) { factory.restore(rekeyed, key()) }
                factory.restore(rekeyed, ByteArray(KEY_SIZE) { 9 }).close()
            }
        }

    private companion object {
        const val KEY_SIZE = 32
        const val GROUP_ID_SIZE = 16
        const val REF_SIZE = 32
        const val MAX_CONTENT = 896
        const val MAX_APP_STATE = 256 * 1024
    }
}
