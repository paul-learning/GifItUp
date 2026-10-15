package app.gifitup.spike.mls

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.gifitup_crypto.NativeMlsClient

/** SPIKE (#3): full MLS round trip through the uniffi bindings on a device/emulator. */
@RunWith(AndroidJUnit4::class)
class MlsRoundTripTest {
    @Test
    fun aliceAndBobExchangeMessages() {
        val start = System.nanoTime()
        NativeMlsClient("alice").use { alice ->
            NativeMlsClient("bob").use { bob ->
                val groupId = alice.createGroup()
                val added = alice.addMember(groupId, bob.createKeyPackage())
                assertArrayEquals(groupId, bob.joinFromWelcome(added.welcome))
                assertEquals(2u, alice.memberCount(groupId))
                assertEquals(2u, bob.memberCount(groupId))

                val toBob = alice.encrypt(groupId, "hi bob".encodeToByteArray())
                assertEquals("hi bob", bob.process(groupId, toBob)?.decodeToString())

                val toAlice = bob.encrypt(groupId, "hi alice".encodeToByteArray())
                assertEquals("hi alice", alice.process(groupId, toAlice)?.decodeToString())
            }
        }
        Log.i("MlsSpike", "round trip took ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    @Test
    fun thirdMemberJoinsAndExistingMemberProcessesCommit() {
        NativeMlsClient("alice").use { alice ->
            NativeMlsClient("bob").use { bob ->
                NativeMlsClient("carol").use { carol ->
                    val groupId = alice.createGroup()
                    bob.joinFromWelcome(alice.addMember(groupId, bob.createKeyPackage()).welcome)

                    val addCarol = alice.addMember(groupId, carol.createKeyPackage())
                    assertNull(bob.process(groupId, addCarol.commit))
                    carol.joinFromWelcome(addCarol.welcome)

                    val msg = carol.encrypt(groupId, "hi all".encodeToByteArray())
                    assertEquals("hi all", alice.process(groupId, msg)?.decodeToString())
                    assertEquals(3u, bob.memberCount(groupId))
                }
            }
        }
    }
}
