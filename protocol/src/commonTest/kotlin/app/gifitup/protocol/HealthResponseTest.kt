package app.gifitup.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthResponseTest {
    @Test
    fun roundTripsThroughJson() {
        val response = HealthResponse(status = "ok", protocolVersion = PROTOCOL_VERSION)

        val json = Json.encodeToString(response)

        assertEquals("""{"status":"ok","protocolVersion":1}""", json)
        assertEquals(response, Json.decodeFromString<HealthResponse>(json))
    }
}
