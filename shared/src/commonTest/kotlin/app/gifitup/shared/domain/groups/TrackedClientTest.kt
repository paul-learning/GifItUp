package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The flag that keeps an engine from exporting a client that is ahead of its snapshot: raised by throwing calls too. */
class TrackedClientTest {
    private val flag = AheadFlag()
    private val client = TrackedClient(FakeMlsClient.Factory.create("ann", ByteArray(32) { 1 }), flag)
    private val group = ByteArray(16) { 2 }

    private val mutators: Map<String, (MlsClient) -> Unit> =
        mapOf(
            "prepareAdd" to { c -> c.prepareAdd(group, listOf(ByteArray(8))) },
            "prepareRemove" to { c -> c.prepareRemove(group, listOf(1)) },
            "prepareUpdate" to { c -> c.prepareUpdate(group) },
            "mergePending" to { c -> c.mergePending(group) },
            "clearPending" to { c -> c.clearPending(group) },
            "encrypt" to { c -> c.encrypt(group, ByteArray(4)) },
            "stageIncoming" to { c -> c.stageIncoming(group, ByteArray(4)) },
            "mergeStaged" to { c -> c.mergeStaged(1) },
            "discardStaged" to { c -> c.discardStaged(1) },
            "inspectWelcome" to { c -> c.inspectWelcome(ByteArray(4), group) },
            "joinWelcome" to { c -> c.joinWelcome(1) },
            "discardWelcome" to { c -> c.discardWelcome(1) },
            "forgetGroup" to { c -> c.forgetGroup(group) },
        )

    /** Calls that may use up a generation or a KeyPackage before they fail: the flag goes up on a throw too. */
    private val raisedOnThrow = setOf("mergePending", "encrypt", "stageIncoming", "mergeStaged", "inspectWelcome", "joinWelcome")

    @Test
    fun everyMutatingCallRaisesTheFlagAndTheGenerationUsingOnesAlsoWhenTheyThrow() {
        for ((name, call) in mutators) {
            val fresh = AheadFlag()
            val tracked = TrackedClient(FakeMlsClient.Factory.create("ann", ByteArray(32) { 1 }), fresh)
            assertFalse(fresh.ahead, name)
            val failure = runCatching { call(tracked) }.exceptionOrNull()
            assertTrue(failure == null || failure is MlsException, name)
            assertEquals(name in raisedOnThrow, fresh.ahead, "$name: raised by a call that throws")
        }
    }

    @Test
    fun anUndoneStepPutsTheFlagBack() {
        flag.moved()
        flag.undone(false)
        assertFalse(flag.ahead)
        flag.moved()
        flag.undone(true)
        assertTrue(flag.ahead)
    }

    @Test
    fun readsDoNotRaiseTheFlag() {
        runCatching { client.members(group) }
        runCatching { client.epoch(group) }
        runCatching { client.memberCount(group) }
        assertFalse(flag.ahead)
    }

    @Test
    fun savedLowersTheFlag() {
        assertFailsWith<MlsException> { client.stageIncoming(group, ByteArray(4)) }
        assertTrue(flag.ahead)
        flag.saved()
        assertFalse(flag.ahead)
    }
}
