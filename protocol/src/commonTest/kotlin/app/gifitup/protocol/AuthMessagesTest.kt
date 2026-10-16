package app.gifitup.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Wire format of the auth messages (ADR-0009) under the ADR-0006 rules. */
class AuthMessagesTest {
    @Test
    fun registerRequestHasStableFieldNames() {
        val request = RegisterRequest("alice", "invite", "key", "challenge", "signature")
        val json =
            """{"username":"alice","inviteCode":"invite","signaturePublicKey":"key",""" +
                """"challenge":"challenge","signature":"signature"}"""

        assertEquals(json, ProtocolJson.encodeToString(request))
        assertEquals(request, ProtocolJson.decodeFromString<RegisterRequest>(json))
    }

    @Test
    fun sessionResponseRoundTrips() {
        val response = SessionResponse("123e4567-e89b-12d3-a456-426614174000", "token", 1_800_000_000L)
        val json = """{"deviceId":"123e4567-e89b-12d3-a456-426614174000","sessionToken":"token","expiresAt":1800000000}"""

        assertEquals(json, ProtocolJson.encodeToString(response))
        assertEquals(response, ProtocolJson.decodeFromString<SessionResponse>(json))
    }

    @Test
    fun everyFieldIsRequired() {
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<LoginRequest>("""{"deviceId":"d","challenge":"c"}""") }
        assertFailsWith<SerializationException> { ProtocolJson.decodeFromString<RegisterRequest>("""{"username":"alice"}""") }
    }

    @Test
    fun unknownFieldsFromANewerClientAreIgnored() {
        val json = """{"deviceId":"d","challenge":"c","signature":"s","addedLater":1}"""

        assertEquals(LoginRequest("d", "c", "s"), ProtocolJson.decodeFromString<LoginRequest>(json))
    }

    @Test
    fun newErrorCodesHaveTheirWireNames() {
        listOf(
            ApiErrorCode.UNAUTHORIZED to "unauthorized",
            ApiErrorCode.INVITE_INVALID to "invite_invalid",
            ApiErrorCode.USERNAME_UNAVAILABLE to "username_unavailable",
            ApiErrorCode.RATE_LIMITED to "rate_limited",
        ).forEach { (code, wire) -> assertEquals("""{"error":"$wire"}""", ProtocolJson.encodeToString(ErrorResponse(code))) }
    }
}
