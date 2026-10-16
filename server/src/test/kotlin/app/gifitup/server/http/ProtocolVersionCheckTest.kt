package app.gifitup.server.http

import app.gifitup.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.server.allText
import app.gifitup.server.captureLogs
import app.gifitup.server.testModule
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProtocolVersionCheckTest {
    private val clientOutdated = """{"error":"client_outdated","minProtocolVersion":$MIN_SUPPORTED_PROTOCOL_VERSION}"""

    @Test
    fun currentVersionPasses() =
        versionTest {
            val response = getWith(PROTOCOL_VERSION.toString())

            // Passes the check and reaches routing (an unknown route).
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun missingHeaderIsClientOutdated() =
        versionTest {
            assertClientOutdated(client.get("/anything"))
        }

    @Test
    fun malformedValuesAreClientOutdated() =
        versionTest {
            listOf("", "0", "01", "+1", "-1", " 1", "1 ", "1.0", "a", "12345", "1,1", "١").forEach { raw ->
                assertClientOutdated(getWith(raw), raw)
            }
        }

    @Test
    fun repeatedHeaderIsClientOutdated() =
        versionTest {
            val response =
                client.get("/anything") {
                    header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString())
                    header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString())
                }

            assertClientOutdated(response)
        }

    @Test
    fun newerVersionIsServerOutdated() =
        versionTest {
            val response = getWith((PROTOCOL_VERSION + 1).toString())

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("""{"error":"server_outdated"}""", response.body<String>())
        }

    @Test
    fun rejectedCallNeverReachesRouteScopedPluginsOrHandlers() {
        val reached = AtomicInteger()
        val counting = createRouteScopedPlugin("Counting") { onCall { reached.incrementAndGet() } }
        testApplication {
            application {
                testModule()
                routing {
                    route("/counted") {
                        install(counting)
                        get { call.respond(HttpStatusCode.NoContent).also { reached.incrementAndGet() } }
                    }
                }
            }

            assertClientOutdated(client.get("/counted"))
            assertEquals(0, reached.get(), "a route-scoped plugin or the handler ran for a rejected call")
            assertEquals(HttpStatusCode.NoContent, getWith(PROTOCOL_VERSION.toString(), "/counted").status)
            assertEquals(2, reached.get())
        }
    }

    @Test
    fun versionBelowMinimumIsClientOutdated() {
        assertEquals(VersionCheck.ClientOutdated, checkProtocolVersion(listOf("2"), min = 3, current = 5))
        assertEquals(VersionCheck.Supported, checkProtocolVersion(listOf("3"), min = 3, current = 5))
        assertEquals(VersionCheck.Supported, checkProtocolVersion(listOf("5"), min = 3, current = 5))
        assertEquals(VersionCheck.ServerOutdated, checkProtocolVersion(listOf("6"), min = 3, current = 5))
        assertEquals(VersionCheck.ClientOutdated, checkProtocolVersion(emptyList(), min = 3, current = 5))
    }

    @Test
    fun rawHeaderValueIsNeverLoggedOrEchoed() =
        versionTest {
            val raw = "9$MARKER"
            captureLogs { logs ->
                val response = getWith(raw)

                assertFalse(response.body<String>().contains(MARKER))
                assertFalse(logs.allText().contains(MARKER))
            }
        }

    private fun versionTest(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { testModule() }
            block()
        }

    private suspend fun ApplicationTestBuilder.getWith(
        raw: String,
        path: String = "/anything",
    ): HttpResponse = client.get(path) { header(PROTOCOL_VERSION_HEADER, raw) }

    private suspend fun assertClientOutdated(
        response: HttpResponse,
        context: String = "",
    ) {
        assertEquals(HttpStatusCode.BadRequest, response.status, context)
        assertEquals(clientOutdated, response.body<String>(), context)
    }

    private companion object {
        const val MARKER = "MARKER-header"
    }
}
