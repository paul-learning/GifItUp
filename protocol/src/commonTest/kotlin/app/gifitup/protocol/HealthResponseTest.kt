package app.gifitup.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthResponseTest {
    @Test
    fun roundTripsThroughJson() {
        val response = HealthResponse(status = "ok", protocolVersion = PROTOCOL_VERSION)

        val json = ProtocolJson.encodeToString(response)

        assertEquals("""{"status":"ok","protocolVersion":1,"minProtocolVersion":1}""", json)
        assertEquals(response, ProtocolJson.decodeFromString<HealthResponse>(json))
    }

    @Test
    fun ignoresFieldsAddedByNewerServers() {
        val json = """{"status":"ok","protocolVersion":1,"minProtocolVersion":1,"addedLater":"x"}"""

        val decoded = ProtocolJson.decodeFromString<HealthResponse>(json)

        assertEquals(HealthResponse(status = "ok", protocolVersion = 1, minProtocolVersion = 1), decoded)
    }

    @Test
    fun readsResponsesFromServersWithoutMinProtocolVersion() {
        val decoded = ProtocolJson.decodeFromString<HealthResponse>("""{"status":"ok","protocolVersion":1}""")

        assertEquals(MIN_SUPPORTED_PROTOCOL_VERSION, decoded.minProtocolVersion)
    }
}
