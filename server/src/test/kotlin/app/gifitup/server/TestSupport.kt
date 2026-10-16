package app.gifitup.server

import app.gifitup.protocol.HealthResponse
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import org.slf4j.LoggerFactory

/** Test-only routes that exercise decoding, body limits and unhandled errors before real endpoints exist. */
internal const val ECHO_PATH = "/test/echo"
internal const val BOOM_PATH = "/test/boom"

/** Marker that must never appear in a response body or a log line. */
internal const val MARKER = "MARKER-7f3a9c"

internal fun Application.testModule(config: ServerConfig = ServerConfig()) {
    module(config)
    routing {
        post(ECHO_PATH) { call.respond(call.receive<HealthResponse>()) }
        get(BOOM_PATH) { error("failure carrying $MARKER") }
    }
}

/** A client that sends the current protocol version on every request, as the real clients do. */
internal fun ApplicationTestBuilder.versionedClient(): HttpClient =
    createClient { defaultRequest { header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString()) } }

/** Captures every log event of the whole server while [block] runs. */
internal inline fun <T> captureLogs(block: (ListAppender<ILoggingEvent>) -> T): T {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
        return block(appender)
    } finally {
        root.detachAppender(appender)
    }
}

/** Every logged line with its arguments filled in, plus the class and message of any attached throwable. */
internal fun ListAppender<ILoggingEvent>.allText(): String =
    list.joinToString("\n") { event ->
        buildString {
            append(event.formattedMessage)
            generateSequence(event.throwableProxy) { it.cause }.forEach { append(' ').append(it.className).append(' ').append(it.message) }
        }
    }
