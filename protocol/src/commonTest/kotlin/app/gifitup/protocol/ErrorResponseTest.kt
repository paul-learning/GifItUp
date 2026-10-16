package app.gifitup.protocol

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ErrorResponseTest {
    @Test
    fun errorResponseUsesTheLowercaseWireCode() {
        assertEquals("""{"error":"bad_request"}""", ProtocolJson.encodeToString(ErrorResponse(ApiErrorCode.BAD_REQUEST)))
    }

    @Test
    fun clientOutdatedCarriesTheMinimumVersion() {
        val json = """{"error":"client_outdated","minProtocolVersion":3}"""

        assertEquals(json, ProtocolJson.encodeToString(ClientOutdatedResponse(ApiErrorCode.CLIENT_OUTDATED, 3)))
        assertEquals(ApiErrorCode.CLIENT_OUTDATED, ProtocolJson.decodeFromString<ErrorResponse>(json).error)
    }

    @Test
    fun everyKnownCodeRoundTrips() {
        ApiErrorCode.entries.filter { it != ApiErrorCode.UNKNOWN }.forEach { code ->
            val json = ProtocolJson.encodeToString(ErrorResponse(code))

            assertEquals("""{"error":"${code.wire}"}""", json)
            assertEquals(code, ProtocolJson.decodeFromString<ErrorResponse>(json).error)
        }
    }

    @Test
    fun unknownOrDifferentlyCasedCodeDecodesToUnknown() {
        assertEquals(ApiErrorCode.UNKNOWN, ProtocolJson.decodeFromString<ErrorResponse>("""{"error":"added_later"}""").error)
        assertEquals(ApiErrorCode.UNKNOWN, ProtocolJson.decodeFromString<ErrorResponse>("""{"error":"BAD_REQUEST"}""").error)
    }

    @Test
    fun encodingUnknownFails() {
        assertFailsWith<SerializationException> { ProtocolJson.encodeToString(ErrorResponse(ApiErrorCode.UNKNOWN)) }
    }

    @Test
    fun duplicateWireNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            FallbackEnumSerializer("Dup", ApiErrorCode.entries, ApiErrorCode.UNKNOWN) { "same" }
        }
    }
}
