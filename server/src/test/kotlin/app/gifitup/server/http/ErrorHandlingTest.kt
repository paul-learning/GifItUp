package app.gifitup.server.http

import app.gifitup.server.BOOM_PATH
import app.gifitup.server.ECHO_PATH
import app.gifitup.server.MARKER
import app.gifitup.server.REQUIRE_PATH
import app.gifitup.server.SEALED_PATH
import app.gifitup.server.allText
import app.gifitup.server.captureLogs
import app.gifitup.server.testModule
import app.gifitup.server.versionedClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorHandlingTest {
    @Test
    fun malformedJsonIsClosedBadRequestWithoutEcho() = assertClosedBadRequest("""{"status":"$MARKER""")

    @Test
    fun wrongTypeIsClosedBadRequestWithoutEcho() =
        assertClosedBadRequest("""{"status":"ok","protocolVersion":"$MARKER","minProtocolVersion":1}""")

    @Test
    fun missingFieldIsClosedBadRequestWithoutEcho() = assertClosedBadRequest("""{"status":"$MARKER"}""")

    @Test
    fun plainIllegalArgumentFromTheDecoderIsClosedBadRequest() =
        testApplication {
            application { testModule() }
            captureLogs { logs ->
                val response =
                    versionedClient().post(SEALED_PATH) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"type":{"x":"$MARKER"},"member":"m"}""")
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("""{"error":"bad_request"}""", response.body<String>())
                assertFalse(logs.allText().contains(MARKER))
            }
        }

    @Test
    fun failedRequireInServerCodeIsLoggedInternalErrorNotBadRequest() =
        testApplication {
            application { testModule() }
            captureLogs { logs ->
                val response = versionedClient().get(REQUIRE_PATH)

                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals("""{"error":"internal"}""", response.body<String>())
                val logged = logs.allText()
                assertTrue(logged.contains("exception=java.lang.IllegalArgumentException"), logged)
                assertFalse(logged.contains(MARKER))
            }
        }

    @Test
    fun wellFormedBodyIsAccepted() =
        testApplication {
            application { testModule() }

            val response = postJson("""{"status":"ok","protocolVersion":1,"minProtocolVersion":1}""")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun unhandledErrorIsClosedInternalErrorAndLogsOnlyTheClass() =
        testApplication {
            application { testModule() }
            captureLogs { logs ->
                val response = versionedClient().get("$BOOM_PATH?q=$MARKER")

                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals("""{"error":"internal"}""", response.body<String>())
                val logged = logs.allText()
                assertTrue(logged.contains("event=unhandled_error code=internal"), logged)
                assertFalse(logged.contains(MARKER), "message or URI leaked into the logs")
                assertFalse(logged.contains(BOOM_PATH), "URI leaked into the logs")
            }
        }

    @Test
    fun unsupportedMediaTypeIsClosed() =
        testApplication {
            application { testModule() }

            val response =
                versionedClient().post(ECHO_PATH) {
                    contentType(ContentType.Text.Plain)
                    setBody(MARKER)
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals("""{"error":"unsupported_media_type"}""", response.body<String>())
        }

    @Test
    fun methodNotAllowedIsClosed() =
        testApplication {
            application { testModule() }

            val response = versionedClient().get(ECHO_PATH)

            assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
            assertEquals("""{"error":"method_not_allowed"}""", response.body<String>())
        }

    @Test
    fun bodyLimitWrappedByTheConverterIsStillPayloadTooLarge() =
        testApplication {
            application {
                testModule()
                routing {
                    get("/test/wrapped") { throw BadRequestException("conversion failed", PayloadTooLargeException(1)) }
                }
            }

            val response = versionedClient().get("/test/wrapped")

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("""{"error":"payload_too_large"}""", response.body<String>())
        }

    @Test
    fun unknownEnumValueIsRejected() {
        val error = runCatching { Kind.UNKNOWN.requireKnown(Kind.UNKNOWN) }.exceptionOrNull()

        assertTrue(error is BadRequestException)
        assertEquals(Kind.KNOWN, Kind.KNOWN.requireKnown(Kind.UNKNOWN))
    }

    private enum class Kind { KNOWN, UNKNOWN }

    private fun assertClosedBadRequest(body: String) =
        testApplication {
            application { testModule() }
            captureLogs { logs ->
                val response = postJson(body)

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("""{"error":"bad_request"}""", response.body<String>())
                assertFalse(logs.allText().contains(MARKER), "decoder message leaked into the logs")
            }
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postJson(body: String): HttpResponse =
        versionedClient().post(ECHO_PATH) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}
