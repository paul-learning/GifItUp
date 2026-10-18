package app.gifitup.shared.data.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

typealias Reply = suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

/**
 * The server side of the auth endpoints for tests: answers like the real one by default (a new challenge per call,
 * sessions for register and login), and [on] queues other replies per path. Records every request with its body.
 */
class FakeAuthServer {
    class Request(
        val path: String,
        val body: String,
        val data: HttpRequestData,
    )

    val requests = mutableListOf<Request>()
    private val queued = mutableMapOf<String, ArrayDeque<Reply>>()
    private var challenges = 0

    val engine =
        MockEngine { request ->
            val path = request.url.encodedPath
            requests += Request(path, (request.body as? TextContent)?.text.orEmpty(), request)
            val reply = queued[path]?.removeFirstOrNull() ?: defaultReply(path)
            reply(request)
        }

    fun on(
        path: String,
        vararg replies: Reply,
    ) {
        queued.getOrPut(path) { ArrayDeque() }.addAll(replies)
    }

    fun api(endpoint: ApiEndpoint = ENDPOINT) = GifItUpApi(engine, endpoint)

    fun bodiesFor(path: String): List<String> = requests.filter { it.path == path }.map { it.body }

    private fun defaultReply(path: String): Reply =
        when (path) {
            "/auth/challenge" -> json("""{"challenge":"${Base64Url.encode(ByteArray(CHALLENGE_SIZE) { (++challenges).toByte() })}"}""")
            "/accounts" -> json(session(DEVICE_ID), HttpStatusCode.Created)
            "/auth/login" -> json(session(DEVICE_ID))
            "/auth/logout" -> { _ -> respond("", HttpStatusCode.NoContent) }
            "/devices" -> json("""{"devices":[{"deviceId":"$DEVICE_ID","createdAt":1,"lastSeenAt":2}]}""")
            else -> json("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
        }

    companion object {
        const val ORIGIN = "https://gifitup.test"
        val ENDPOINT = checkNotNull(ApiEndpoint.forOrigin(ORIGIN))
        const val DEVICE_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val CHALLENGE_SIZE = 56
        val TOKEN = Base64Url.encode(ByteArray(32) { 7 })

        fun session(deviceId: String) = """{"deviceId":"$deviceId","sessionToken":"$TOKEN","expiresAt":1700000000}"""

        fun json(
            body: String,
            status: HttpStatusCode = HttpStatusCode.OK,
        ): Reply = { _ -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

        fun error(
            status: HttpStatusCode,
            code: String,
        ): Reply = json("""{"error":"$code"}""", status)

        /** A reply that never arrives: the engine fails like a dropped connection. */
        val dropped: Reply = { _ -> throw IllegalStateException("connection reset") }

        /** How Ktor's web (fetch) engine reports a network failure: `kotlin.Error`, not an `Exception`. */
        @Suppress("TooGenericExceptionThrown")
        val fetchFailed: Reply = { _ -> throw Error("Fail to fetch") }
    }
}
