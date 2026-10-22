package app.gifitup.server

import app.gifitup.protocol.HealthResponse
import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.ProtocolJson
import app.gifitup.server.http.ReadinessProbe
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
    fun healthReturnsOkWithoutProtocolHeader() =
        testApplication {
            application {
                module(
                    testConfig(),
                    AlwaysReady,
                    noDatabaseAuth(),
                    noDatabaseInvites(),
                    noDatabaseKeyPackages(),
                    noDatabaseMembers(),
                )
            }
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
    fun healthIsUnavailableWhenNotReady() =
        testApplication {
            application {
                module(
                    testConfig(),
                    ReadinessProbe { false },
                    noDatabaseAuth(),
                    noDatabaseInvites(),
                    noDatabaseKeyPackages(),
                    noDatabaseMembers(),
                )
            }
            val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("unavailable", response.body<HealthResponse>().status)
        }

    @Test
    fun unknownRouteWithoutSessionIsUnauthorizedNotNotFound() =
        testApplication {
            // Deny by default (ADR-0009): a caller without a session can't even tell which paths exist.
            application {
                module(
                    testConfig(),
                    AlwaysReady,
                    noDatabaseAuth(),
                    noDatabaseInvites(),
                    noDatabaseKeyPackages(),
                    noDatabaseMembers(),
                )
            }

            val response = versionedClient().get("/does-not-exist")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""{"error":"unauthorized"}""", response.body<String>())
        }
}
