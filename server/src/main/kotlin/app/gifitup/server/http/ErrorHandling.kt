package app.gifitup.server.http

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.gifitup.server.http.ErrorHandling")

/**
 * Maps every failure to a fixed status and a closed [ErrorResponse] (ADR-0006 A8, SEC-M0-10). Exception messages and
 * causes are never returned or logged: kotlinx decoder messages echo the input, and other messages can carry request
 * data. Logs name a closed code and the exception class only, never the request URI, headers or body.
 */
internal fun Application.installErrorHandling() {
    install(StatusPages) {
        // Decoding failures. Ktor wraps them in BadRequestException; kotlinx throws IllegalArgumentException for some
        // malformed JSON, so both base types are caught as well (ADR-0006 B5).
        exception<BadRequestException> { call, cause ->
            // The JSON converter wraps a body limit hit during the read, depending on timing; it is still a 413.
            if (cause.causes().any { it is PayloadTooLargeException }) {
                call.respondError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE)
            } else {
                call.respondError(HttpStatusCode.BadRequest, ApiErrorCode.BAD_REQUEST)
            }
        }
        exception<SerializationException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ApiErrorCode.BAD_REQUEST) }
        exception<IllegalArgumentException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ApiErrorCode.BAD_REQUEST) }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE)
        }
        // No converter for the request's content type (e.g. text/plain to a JSON route).
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondError(HttpStatusCode.UnsupportedMediaType, ApiErrorCode.UNSUPPORTED_MEDIA_TYPE)
        }
        exception<CannotTransformContentToTypeException> { call, _ ->
            call.respondError(HttpStatusCode.UnsupportedMediaType, ApiErrorCode.UNSUPPORTED_MEDIA_TYPE)
        }
        exception<ContentTransformationException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, ApiErrorCode.BAD_REQUEST) }
        exception<Throwable> { call, cause ->
            log.error("event=unhandled_error code=internal exception={}", cause::class.java.name)
            call.respondError(HttpStatusCode.InternalServerError, ApiErrorCode.INTERNAL)
        }
        status(HttpStatusCode.NotFound) { call, status -> call.respondError(status, ApiErrorCode.NOT_FOUND) }
        status(HttpStatusCode.MethodNotAllowed) { call, status -> call.respondError(status, ApiErrorCode.METHOD_NOT_ALLOWED) }
    }
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: ApiErrorCode,
) = respond(status, ErrorResponse(code))

/** The cause chain below this throwable, bounded so a cyclic chain can't loop. */
private fun Throwable.causes(): Sequence<Throwable> = generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH)

private const val MAX_CAUSE_DEPTH = 16
