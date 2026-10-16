package app.gifitup.server.http

import io.ktor.server.plugins.BadRequestException

/**
 * Rejects the `UNKNOWN` fallback of a growable wire enum in a request (ADR-0006 A7): a client must never ask the server
 * to act on a value neither side understands. Throws [BadRequestException], which the error handling maps to a closed
 * `400 bad_request`. Its message is fixed and never reaches the client or the logs.
 */
internal fun <T : Enum<T>> T.requireKnown(unknown: T): T {
    if (this == unknown) throw BadRequestException("unknown enum value")
    return this
}
