package app.gifitup.spike.mlsweb

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt8Array
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.TimeSource

/** SPIKE (#4): the Android round trip, in a real browser (Karma + Chrome headless). */
class MlsWebRoundTripTest {
    private suspend fun ready() {
        if (!initialized) {
            val mark = TimeSource.Monotonic.markNow()
            initCrypto().await<JsAny?>()
            println("MlsSpike: wasm init took ${mark.elapsedNow().inWholeMilliseconds} ms")
            initialized = true
        }
    }

    @Test
    fun aliceAndBobExchangeMessages() =
        runTest {
            ready()
            val mark = TimeSource.Monotonic.markNow()
            val alice = NativeMlsClient("alice", key())
            val bob = NativeMlsClient("bob", key())
            val groupId = alice.createGroup()
            val added = alice.addMember(groupId, bob.keyPackage())
            assertContentEquals(groupId.toByteArray(), bob.joinFromWelcome(added.welcome).toByteArray())
            assertEquals(2, bob.memberCount(groupId))

            val toBob = alice.encrypt(groupId, "hi bob".encodeToByteArray().toUint8Array())
            assertEquals("hi bob", bob.process(groupId, toBob)?.toByteArray()?.decodeToString())
            val toAlice = bob.encrypt(groupId, "hi alice".encodeToByteArray().toUint8Array())
            assertEquals("hi alice", alice.process(groupId, toAlice)?.toByteArray()?.decodeToString())
            println("MlsSpike: round trip took ${mark.elapsedNow().inWholeMilliseconds} ms")

            added.free()
            alice.free()
            bob.free()
        }

    @Test
    fun thirdMemberJoinsAndExistingMemberProcessesCommit() =
        runTest {
            ready()
            val alice = NativeMlsClient("alice", key())
            val bob = NativeMlsClient("bob", key())
            val carol = NativeMlsClient("carol", key())
            val groupId = alice.createGroup()
            bob.joinFromWelcome(alice.addMember(groupId, bob.keyPackage()).welcome)

            val addCarol = alice.addMember(groupId, carol.keyPackage())
            assertNull(bob.process(groupId, addCarol.commit))
            carol.joinFromWelcome(addCarol.welcome)

            val msg = carol.encrypt(groupId, "hi all".encodeToByteArray().toUint8Array())
            assertEquals("hi all", alice.process(groupId, msg)?.toByteArray()?.decodeToString())
            assertEquals("hi all", bob.process(groupId, msg)?.toByteArray()?.decodeToString())
        }

    @Test
    fun stateSurvivesExportAndRestore() =
        runTest {
            ready()
            val alice = NativeMlsClient("alice", key())
            val bob = NativeMlsClient("bob", key())
            val groupId = alice.createGroup()
            bob.joinFromWelcome(alice.addMember(groupId, bob.keyPackage()).welcome)

            val snapshot = bob.exportState().toByteArray()
            println("MlsSpike: snapshot with 1 group is ${snapshot.size} bytes")
            bob.free()
            val restored = NativeMlsClient.restore(snapshot.toUint8Array(), key())

            val msg = alice.encrypt(groupId, "after restore".encodeToByteArray().toUint8Array())
            assertEquals("after restore", restored.process(groupId, msg)?.toByteArray()?.decodeToString())
        }

    @Test
    fun rustErrorsSurfaceAsKotlinExceptions() =
        runTest {
            ready()
            val alice = NativeMlsClient("alice", key())
            val groupId = alice.createGroup()
            val e = assertFailsWith<JsException> { alice.addMember(groupId, GARBAGE.toUint8Array()) }
            // Closed error codes (ADR-0007): the message is exactly the code name, nothing about the input.
            assertEquals("InvalidInput", e.message)
            println("MlsSpike: error surfaced as ${e::class.simpleName}: ${e.message}")
        }

    @Test
    fun byteArrayCopyCost() =
        runTest {
            ready()
            // A GIF blob is ~1-3 MB (ADR-0003): measure the Kotlin <-> JS copy that every call pays.
            val data = ByteArray(BLOB_SIZE) { it.toByte() }
            val mark = TimeSource.Monotonic.markNow()
            val js = data.toUint8Array()
            val back = js.toByteArray()
            println("MlsSpike: 2 MiB via element-loop Uint8Array took ${mark.elapsedNow().inWholeMilliseconds} ms")
            assertContentEquals(data, back)

            val mark2 = TimeSource.Monotonic.markNow()
            val js2 = data.toInt8Array()
            val back2 = js2.toByteArray()
            println(
                "MlsSpike: 2 MiB via kotlinx-browser Int8Array took ${mark2.elapsedNow().inWholeMilliseconds} ms",
            )
            assertContentEquals(data, back2)
        }

    private fun key() = ByteArray(KEY_SIZE) { KEY_BYTE }.toUint8Array()

    private fun NativeMlsClient.keyPackage() = createKeyPackages(1)[0]!!.keyPackage

    private companion object {
        const val BLOB_SIZE = 2 * 1024 * 1024
        const val KEY_SIZE = 32
        const val KEY_BYTE: Byte = 7
        val GARBAGE = byteArrayOf(0x01, 0x02, 0x03)
        var initialized = false
    }
}
