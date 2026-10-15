package app.gifitup.server

import app.gifitup.protocol.HealthResponse
import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRouteTest {
    @Test
    fun healthReturnsOk() =
        testApplication {
            application { module() }
            val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                HealthResponse(
                    status = "ok",
                    protocolVersion = PROTOCOL_VERSION,
                    minProtocolVersion = MIN_SUPPORTED_PROTOCOL_VERSION,
                ),
                response.body(),
            )
        }

    @Test
    fun unknownRouteIsNotFound() =
        testApplication {
            application { module() }

            assertEquals(HttpStatusCode.NotFound, client.get("/does-not-exist").status)
        }
}
