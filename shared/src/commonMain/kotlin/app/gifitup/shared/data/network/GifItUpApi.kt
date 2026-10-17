package app.gifitup.shared.data.network

import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.DeviceListResponse
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.LoginRequest
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlin.coroutines.cancellation.CancellationException

/**
 * The server API (ADR-0009), one function per endpoint, on a Ktor client over an injected [engine] (the platform's,
 * chosen by the composition root), so `commonMain` holds no engine.
 *
 * Every response is untrusted (#79 plan, SEC-3, SEC-8):
 * - redirects are not followed, timeouts are set, and a body over [MAX_BODY_BYTES] is refused before decoding;
 * - every body, 2xx included, is decoded with [ProtocolJson] inside one boundary, and every field is checked;
 *   anything off is [ApiError.MALFORMED_RESPONSE], with no message and no cause, because decoder errors quote their
 *   input (a bad `SessionResponse` would quote the token);
 * - an error answer counts only through its closed code; the body's text is never kept, logged or shown.
 */
class GifItUpApi(
    engine: HttpClientEngine,
    val endpoint: ApiEndpoint,
) : AutoCloseable {
    private val client =
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }

    /** `POST /auth/challenge`: a fresh single-use challenge, as raw bytes. */
    suspend fun challenge(): ApiResult<ByteArray> =
        exchange(Route.CHALLENGE, body = null, session = null) { bytes ->
            decode(ChallengeResponse.serializer(), bytes)?.let { Base64Url.decode(it.challenge, CHALLENGE_SIZES) }
        }

    /** `POST /accounts`: registers [username] with its first device (ADR-0009). Byte fields go out as base64url. */
    suspend fun register(
        username: String,
        inviteCode: String,
        signaturePublicKey: ByteArray,
        challenge: ByteArray,
        signature: ByteArray,
    ): ApiResult<Session> {
        val request =
            RegisterRequest(
                username = username,
                inviteCode = inviteCode,
                signaturePublicKey = Base64Url.encode(signaturePublicKey),
                challenge = Base64Url.encode(challenge),
                signature = Base64Url.encode(signature),
            )
        val body = ProtocolJson.encodeToString(RegisterRequest.serializer(), request)
        return exchange(Route.REGISTER, body, session = null, ::readSession)
    }

    /** `POST /auth/login` for [deviceId]. */
    suspend fun login(
        deviceId: String,
        challenge: ByteArray,
        signature: ByteArray,
    ): ApiResult<Session> {
        val request = LoginRequest(deviceId, Base64Url.encode(challenge), Base64Url.encode(signature))
        val body = ProtocolJson.encodeToString(LoginRequest.serializer(), request)
        return exchange(Route.LOGIN, body, session = null, ::readSession)
    }

    /** `POST /auth/logout`: ends [session] on the server. */
    suspend fun logout(session: Session): ApiResult<Unit> = exchange(Route.LOGOUT, body = null, session) { }

    /** `GET /devices`: the account's own devices. */
    suspend fun devices(session: Session): ApiResult<List<Device>> =
        exchange(Route.DEVICES, body = null, session) { bytes ->
            decode(DeviceListResponse.serializer(), bytes)
                ?.devices
                ?.takeIf { list -> list.all { UUID.matches(it.deviceId) && it.createdAt >= 0 && it.lastSeenAt >= 0 } }
                ?.map { Device(it.deviceId, it.createdAt, it.lastSeenAt) }
        }

    override fun close() = client.close()

    /** An endpoint: its method, path and the one status that means success. */
    private enum class Route(
        val method: HttpMethod,
        val path: String,
        val success: HttpStatusCode,
    ) {
        CHALLENGE(HttpMethod.Post, "/auth/challenge", HttpStatusCode.OK),
        REGISTER(HttpMethod.Post, "/accounts", HttpStatusCode.Created),
        LOGIN(HttpMethod.Post, "/auth/login", HttpStatusCode.OK),
        LOGOUT(HttpMethod.Post, "/auth/logout", HttpStatusCode.NoContent),
        DEVICES(HttpMethod.Get, "/devices", HttpStatusCode.OK),
    }

    /**
     * One request and its answer. [read] turns the body of a successful answer into a value, or null if it breaks the
     * protocol. The response is streamed (`execute`), so an oversized body is never buffered in full.
     */
    @Suppress("TooGenericExceptionCaught") // Engines throw platform-specific I/O exceptions; all of them mean NETWORK.
    private suspend fun <T> exchange(
        route: Route,
        body: String?,
        session: Session?,
        read: (ByteArray) -> T?,
    ): ApiResult<T> =
        try {
            client
                .prepareRequest {
                    method = route.method
                    url(endpoint.baseUrl + route.path)
                    header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString())
                    session?.let { header(HttpHeaders.Authorization, "Bearer ${it.token}") }
                    body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
                }.execute { response -> answer(response, route.success, read) }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            ApiResult.Failed(ApiError.NETWORK)
        }

    companion object {
        /** Cap on any response body; every answer of these endpoints is far smaller (#79 SEC-8). */
        const val MAX_BODY_BYTES = 64 * 1024

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SOCKET_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L

        /** The crate signs challenges of 16..64 bytes (ADR-0009); the server sends 56. */
        private val CHALLENGE_SIZES = 16..64

        /** Device ids are lowercase canonical UUIDs (ADR-0009). */
        internal val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

private suspend fun <T> answer(
    response: HttpResponse,
    success: HttpStatusCode,
    read: (ByteArray) -> T?,
): ApiResult<T> {
    val bytes = response.readCapped()
    val result =
        when {
            bytes == null -> null
            response.status == success -> read(bytes)?.let { ApiResult.Ok(it) }
            response.status.value in FIRST_ERROR_STATUS..LAST_ERROR_STATUS -> decode(ErrorResponse.serializer(), bytes)?.let(::failure)
            else -> null
        }
    return result ?: ApiResult.Failed(ApiError.MALFORMED_RESPONSE)
}

private const val FIRST_ERROR_STATUS = 400
private const val LAST_ERROR_STATUS = 599

private fun failure(error: ErrorResponse): ApiResult.Failed =
    ApiResult.Failed(
        when (error.error) {
            ApiErrorCode.BAD_REQUEST -> ApiError.BAD_REQUEST
            ApiErrorCode.CLIENT_OUTDATED -> ApiError.CLIENT_OUTDATED
            ApiErrorCode.SERVER_OUTDATED -> ApiError.SERVER_OUTDATED
            ApiErrorCode.UNAUTHORIZED -> ApiError.UNAUTHORIZED
            ApiErrorCode.INVITE_INVALID -> ApiError.INVITE_INVALID
            ApiErrorCode.USERNAME_UNAVAILABLE -> ApiError.USERNAME_UNAVAILABLE
            ApiErrorCode.RATE_LIMITED -> ApiError.RATE_LIMITED
            else -> ApiError.SERVER_ERROR
        },
    )

/** A session token is 32 random bytes (ADR-0009). */
private const val TOKEN_BYTES = 32

private fun readSession(bytes: ByteArray): Session? =
    decode(SessionResponse.serializer(), bytes)
        ?.takeIf {
            GifItUpApi.UUID.matches(it.deviceId) && Base64Url.decode(it.sessionToken, TOKEN_BYTES..TOKEN_BYTES) != null &&
                it.expiresAt > 0
        }?.let { Session(it.deviceId, it.sessionToken, it.expiresAt) }

/** The body, or null if it is larger than [GifItUpApi.MAX_BODY_BYTES] (by header or by content). */
private suspend fun HttpResponse.readCapped(): ByteArray? {
    val max = GifItUpApi.MAX_BODY_BYTES
    if ((contentLength() ?: 0) > max) return null
    return bodyAsChannel().readRemaining(max + 1L).readByteArray().takeIf { it.size <= max }
}

/** Decodes [bytes], or null. kotlinx throws `SerializationException` and plain `IllegalArgumentException`, both caught. */
private fun <T> decode(
    strategy: DeserializationStrategy<T>,
    bytes: ByteArray,
): T? =
    try {
        ProtocolJson.decodeFromString(strategy, bytes.decodeToString())
    } catch (ignored: IllegalArgumentException) {
        null
    }
