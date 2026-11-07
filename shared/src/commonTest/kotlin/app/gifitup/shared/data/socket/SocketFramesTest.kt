package app.gifitup.shared.data.socket

import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.events.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The frames of the socket (#27, ADR-0015): the hello this device sends and the hints it accepts, read strictly. */
class SocketFramesTest {
    private val group = "AQEBAQEBAQEBAQEBAQEBAQ" // 16 bytes of 1

    private fun push(
        kind: String = "application",
        seq: String = "3",
        groupId: String = "\"$group\"",
    ) = "{\"groupId\":$groupId,\"seq\":$seq,\"kind\":\"$kind\"}"

    @Test
    fun aGroupHintNamesTheGroupAndNothingElseIsKept() {
        for (kind in listOf("application", "commit")) {
            val message = assertIs<ServerMessage.GroupHint>(SocketFrames.parse(push(kind)))
            assertEquals(KeyBytes(ByteArray(16) { 1 }), message.groupId)
        }
    }

    @Test
    fun aWelcomeHintHasNoGroupAndSeqZero() {
        assertEquals(ServerMessage.WelcomeHint, SocketFrames.parse(push(kind = "welcome", seq = "0")))
        assertNull(SocketFrames.parse(push(kind = "welcome", seq = "5")), "a welcome hint with a seq is not one")
    }

    @Test
    fun aResyncHintIsExactlyResyncTrue() {
        assertEquals(ServerMessage.Resync, SocketFrames.parse("{\"resync\":true}"))
        assertNull(SocketFrames.parse("{\"resync\":false}"))
        assertNull(SocketFrames.parse("{\"resync\":\"true\"}"))
        assertNull(SocketFrames.parse("{\"resync\":true,\"x\":1}"))
    }

    @Test
    fun anythingElseIsDroppedNeverAnErrorNeverPartlyUsed() {
        val bad =
            listOf(
                "",
                "x",
                "[]",
                "{}",
                "null",
                push(kind = "other"),
                push(kind = "Application"),
                push(seq = "-1"),
                push(seq = "\"3\""),
                push(seq = "3.5"),
                push(groupId = "5"),
                push(groupId = "\"\""),
                push(groupId = "\"not base64 !\""),
                push(groupId = "\"${"A".repeat(400)}\""),
                "{\"groupId\":\"$group\",\"seq\":3,\"kind\":\"application\",\"extra\":1}",
                "{\"groupId\":\"$group\",\"seq\":3}",
                "{\"v\":1,\"token\":\"x\"}",
            )
        for (text in bad) assertNull(SocketFrames.parse(text), text)
    }

    @Test
    fun aFrameOverTheCapIsRefusedBeforeItIsParsed() {
        val huge = "{\"groupId\":\"$group\",\"seq\":3,\"kind\":\"application\",\"pad\":\"${"x".repeat(SocketFrames.MAX_FRAME_CHARS)}\"}"
        assertTrue(huge.length > SocketFrames.MAX_FRAME_CHARS)
        assertNull(SocketFrames.parse(huge))
        assertNull(SocketFrames.parse("[" + "[".repeat(100_000)), "a deep nesting is just not a hint")
    }

    @Test
    fun theHelloIsTheProtocolVersionAndTheTokenOnly() {
        val endpoint = checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test"))
        val hello = SocketFrames.hello(Session("device-1", "SECRET-TOKEN", 99L, endpoint))
        assertEquals("{\"v\":1,\"token\":\"SECRET-TOKEN\"}", hello)
        assertTrue("device-1" !in hello, "no device id, no origin")
    }

    @Test
    fun theSocketAddressIsTheEndpointsOwnWithTheSchemeSwapped() {
        val endpoint = checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test:8443"))
        assertEquals("wss://chat.example.test:8443/ws", endpoint.socketUrl)
    }
}
