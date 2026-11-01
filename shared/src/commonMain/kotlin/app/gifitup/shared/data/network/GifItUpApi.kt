package app.gifitup.shared.data.network

import app.gifitup.protocol.AckRequest
import app.gifitup.protocol.ApiErrorCode
import app.gifitup.protocol.ChallengeResponse
import app.gifitup.protocol.ClaimKeyPackagesRequest
import app.gifitup.protocol.ClaimKeyPackagesResponse
import app.gifitup.protocol.CreateGroupRequest
import app.gifitup.protocol.CreateInviteResponse
import app.gifitup.protocol.DeviceListResponse
import app.gifitup.protocol.ErrorResponse
import app.gifitup.protocol.FetchMessagesResponse
import app.gifitup.protocol.GifProviderResponse
import app.gifitup.protocol.GroupStatusResponse
import app.gifitup.protocol.InviteInfo
import app.gifitup.protocol.InviteListResponse
import app.gifitup.protocol.InviteStatus
import app.gifitup.protocol.KeyPackageStatusResponse
import app.gifitup.protocol.LoginRequest
import app.gifitup.protocol.MembersResponse
import app.gifitup.protocol.PROTOCOL_VERSION
import app.gifitup.protocol.PROTOCOL_VERSION_HEADER
import app.gifitup.protocol.PendingWelcomesResponse
import app.gifitup.protocol.PostMessageRequest
import app.gifitup.protocol.PostMessageResponse
import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.protocol.SessionResponse
import app.gifitup.protocol.UploadKeyPackagesRequest
import app.gifitup.protocol.UploadKeyPackagesResponse
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
 * chosen by the composition root), so `commonMain` holds no engine. The caller owns the engine: [close] closes the
 * client only, and the caller closes the engine when it is done with it.
 *
 * Every response is untrusted (#79 plan, SEC-3, SEC-8):
 * - redirects are not followed, timeouts are set, and a body over [MAX_BODY_BYTES] is refused before decoding;
 * - every body, 2xx included, is decoded with [ProtocolJson] inside one boundary, and every field is checked;
 *   anything off is [ApiError.MALFORMED_RESPONSE], with no message and no cause, because decoder errors quote their
 *   input (a bad `SessionResponse` would quote the token);
 * - an error answer counts only through its closed code; the body's text is never kept, logged or shown.
 *
 * A [Session] belongs to the endpoint that issued it: using it with another one is a programming error and throws
 * before anything is sent, so a token never travels to a different origin or port (#94 SEC-13).
 */
@Suppress("TooManyFunctions") // One function per endpoint; splitting would split one HTTP boundary.
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
        return exchange(Route.REGISTER, body, session = null) { readSession(it, endpoint) }
    }

    /** `POST /auth/login` for [deviceId]. */
    suspend fun login(
        deviceId: String,
        challenge: ByteArray,
        signature: ByteArray,
    ): ApiResult<Session> {
        val request = LoginRequest(deviceId, Base64Url.encode(challenge), Base64Url.encode(signature))
        val body = ProtocolJson.encodeToString(LoginRequest.serializer(), request)
        return exchange(Route.LOGIN, body, session = null) { readSession(it, endpoint) }
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

    /**
     * `POST /key-packages`: uploads 1..[MAX_UPLOAD_BATCH] own KeyPackages of at most [MAX_KEY_PACKAGE_BYTES] bytes each,
     * all or none (ADR-0012). The caller has already persisted the private keys (a KeyPackage is never public without
     * them).
     */
    suspend fun uploadKeyPackages(
        session: Session,
        keyPackages: List<ByteArray>,
    ): ApiResult<KeyPackageUpload> {
        require(keyPackages.size in 1..MAX_UPLOAD_BATCH && keyPackages.all { it.size in 1..MAX_KEY_PACKAGE_BYTES }) { "batch out of range" }
        val body =
            ProtocolJson.encodeToString(
                UploadKeyPackagesRequest.serializer(),
                UploadKeyPackagesRequest(keyPackages.map(Base64Url::encode)),
            )
        return exchange(Route.UPLOAD_KEY_PACKAGES, body, session) { bytes ->
            decode(UploadKeyPackagesResponse.serializer(), bytes)
                ?.takeIf { it.accepted == keyPackages.size && it.available >= it.accepted && it.lowWaterMark >= 0 }
                ?.let { KeyPackageUpload(it.accepted, it.available, it.lowWaterMark) }
        }
    }

    /** `GET /key-packages/status`: how many unexpired KeyPackages this device has on the server. */
    suspend fun keyPackageStatus(session: Session): ApiResult<KeyPackageStatus> =
        exchange(Route.KEY_PACKAGE_STATUS, body = null, session) { bytes ->
            decode(KeyPackageStatusResponse.serializer(), bytes)
                ?.takeIf { it.available >= 0 && it.lowWaterMark >= 0 }
                ?.let { KeyPackageStatus(it.available, it.lowWaterMark) }
        }

    /**
     * `POST /key-packages/claim`: takes one KeyPackage per device of [username] out of the directory (ADR-0012).
     * [ciphersuites] is the wanted order, 1..[MAX_SUITES] distinct ids. A claim consumes what it returns, so this call
     * is **never repeated automatically**: a lost answer loses that KeyPackage, and the caller decides whether to ask
     * again. The username and suites are checked for shape here; the server is authoritative.
     */
    suspend fun claimKeyPackages(
        session: Session,
        username: String,
        ciphersuites: List<Int>,
    ): ApiResult<ClaimedKeyPackages> {
        require(
            USERNAME.matches(username) && ciphersuites.size in 1..MAX_SUITES && ciphersuites.distinct().size == ciphersuites.size &&
                ciphersuites.all { it in 1..MAX_SUITE_ID },
        ) { "claim out of range" }
        val body = ProtocolJson.encodeToString(ClaimKeyPackagesRequest.serializer(), ClaimKeyPackagesRequest(username, ciphersuites))
        return exchange(
            Route.CLAIM_KEY_PACKAGES,
            body,
            session,
        ) { bytes -> decode(ClaimKeyPackagesResponse.serializer(), bytes)?.let(::readClaimed) }
    }

    /**
     * `POST /invites`: a new invite of the session's account (ADR-0010). Only the id is checked here, so the caller can
     * revoke an invite whose other fields it refuses (`CreateInvite`); the link and time are checked there.
     */
    suspend fun createInvite(session: Session): ApiResult<CreatedInviteAnswer> =
        exchange(Route.CREATE_INVITE, body = null, session) { bytes ->
            decode(CreateInviteResponse.serializer(), bytes)
                ?.takeIf { UUID.matches(it.inviteId) }
                ?.let { CreatedInviteAnswer(it.inviteId, it.link, it.expiresAt) }
        }

    /**
     * `GET /members`: the account's invite neighbours (ADR-0014). The whole answer is refused, not trimmed, if any
     * part of it breaks the rules in [readMembers]: a partly believed list would let a server shape what the contact
     * book sees.
     */
    suspend fun listMembers(session: Session): ApiResult<MemberList> =
        exchange(Route.MEMBERS, body = null, session) { bytes -> decode(MembersResponse.serializer(), bytes)?.let(::readMembers) }

    /**
     * `GET /config/gif-provider`: the GIF provider's API key (ADR-0003, #40). The provider is not on the wire (the client
     * hard-codes it), and an answer whose key breaks the rule is refused whole. A 404 means the server has no key (or
     * is from before this endpoint): "no GIF search yet".
     */
    suspend fun gifProviderKey(session: Session): ApiResult<GifProviderKey> =
        exchange(Route.GIF_PROVIDER, body = null, session) { bytes ->
            decode(GifProviderResponse.serializer(), bytes)?.let { GifProviderKey.of(it.apiKey) }
        }

    /** `GET /invites`: the account's own invites. */
    suspend fun listInvites(session: Session): ApiResult<List<Invite>> =
        exchange(Route.LIST_INVITES, body = null, session) { bytes ->
            decode(InviteListResponse.serializer(), bytes)
                ?.invites
                ?.takeIf { list -> list.size <= MAX_LISTED_INVITES && list.all(::isValidInvite) }
                ?.map { Invite(it.inviteId, it.createdAt, it.expiresAt, stateOf(it.status), it.usedBy) }
        }

    /**
     * A canonical id, times that a date type can show (created before it expires), and a `usedBy` that is a username
     * (it is shown to the user) only on a used invite, where it may be null (deleted account), or on a status this build
     * doesn't know (ADR-0010).
     */
    private fun isValidInvite(info: InviteInfo): Boolean =
        UUID.matches(info.inviteId) &&
            info.createdAt in 1..info.expiresAt &&
            info.expiresAt <= MAX_TIME &&
            when (info.status) {
                // Null after the invitee's account was deleted (`used_by` is ON DELETE SET NULL); a status this build
                // doesn't know may carry one too (ADR-0006). Either way only a username.
                InviteStatus.USED, InviteStatus.UNKNOWN -> info.usedBy?.let(USERNAME::matches) != false

                else -> info.usedBy == null
            }

    private fun stateOf(status: InviteStatus): InviteState =
        when (status) {
            InviteStatus.OPEN -> InviteState.OPEN
            InviteStatus.USED -> InviteState.USED
            InviteStatus.EXPIRED -> InviteState.EXPIRED
            InviteStatus.REVOKED -> InviteState.REVOKED
            InviteStatus.UNKNOWN -> InviteState.UNKNOWN
        }

    /** `DELETE /invites/{id}`: revokes an open invite of the account; anything else is `NOT_FOUND` (ADR-0010). */
    suspend fun revokeInvite(
        session: Session,
        inviteId: String,
    ): ApiResult<Unit> {
        require(UUID.matches(inviteId)) { "invite ids are canonical UUIDs" }
        return exchange(Route.REVOKE_INVITE, body = null, session, pathSuffix = "/$inviteId") { }
    }

    /**
     * `POST /groups`: creates the group [groupId] (16..64 random bytes) with its first commit, epoch 0, which adds at
     * least one device (ADR-0015). Safe to repeat with the same envelope after a lost answer: the creator gets the
     * first answer back.
     */
    suspend fun createGroup(
        session: Session,
        groupId: ByteArray,
        commit: DeliveryEnvelope,
    ): ApiResult<PostedMessage> {
        require(groupId.size in MIN_GROUP_ID_BYTES..MAX_GROUP_ID_BYTES) { "group id out of range" }
        val body =
            ProtocolJson.encodeToString(
                CreateGroupRequest.serializer(),
                CreateGroupRequest(Base64Url.encode(groupId), commit.toWire()),
            )
        return exchange(Route.CREATE_GROUP, body, session) { bytes -> decode(PostMessageResponse.serializer(), bytes)?.let(::readPosted) }
    }

    /** `POST /groups/{id}/messages`: a commit or an application message; a repeat of the same envelope is safe. */
    suspend fun postMessage(
        session: Session,
        groupId: ByteArray,
        envelope: DeliveryEnvelope,
    ): ApiResult<PostedMessage> {
        val body = ProtocolJson.encodeToString(PostMessageRequest.serializer(), envelope.toWire())
        return exchange(Route.POST_MESSAGE, body, session, pathSuffix = groupPath(groupId, "/messages")) { bytes ->
            decode(PostMessageResponse.serializer(), bytes)?.let(::readPosted)
        }
    }

    /** `GET /groups/{id}/messages`: up to [limit] messages above [after] (and above this device's join point). */
    suspend fun fetchMessages(
        session: Session,
        groupId: ByteArray,
        after: Long,
        limit: Int = MAX_PAGE_MESSAGES,
    ): ApiResult<MessagePage> {
        require(after >= 0 && limit in 1..MAX_PAGE_MESSAGES) { "page out of range" }
        return exchange(
            Route.FETCH_MESSAGES,
            body = null,
            session,
            pathSuffix = groupPath(groupId, "/messages?after=$after&limit=$limit"),
        ) { bytes ->
            decode(FetchMessagesResponse.serializer(), bytes)?.let { readPage(it, after) }
        }
    }

    /** `GET /groups/{id}`: epoch, retained range and this device's cursors (the freshness anchor, ADR-0015). */
    suspend fun groupStatus(
        session: Session,
        groupId: ByteArray,
    ): ApiResult<GroupState> =
        exchange(Route.GROUP_STATUS, body = null, session, pathSuffix = groupPath(groupId, "")) { bytes ->
            decode(GroupStatusResponse.serializer(), bytes)?.let(::readState)
        }

    /** `POST /groups/{id}/ack`: this device has processed everything up to [seq]. */
    suspend fun ackMessages(
        session: Session,
        groupId: ByteArray,
        seq: Long,
    ): ApiResult<Unit> {
        require(seq >= 0) { "seq out of range" }
        val body = ProtocolJson.encodeToString(AckRequest.serializer(), AckRequest(seq))
        return exchange(Route.ACK_MESSAGES, body, session, pathSuffix = groupPath(groupId, "/ack")) { }
    }

    /** `POST /groups/{id}/leave`: drops this device's own membership without a commit (ADR-0015). */
    suspend fun leaveGroup(
        session: Session,
        groupId: ByteArray,
    ): ApiResult<Unit> = exchange(Route.LEAVE_GROUP, body = null, session, pathSuffix = groupPath(groupId, "/leave")) { }

    /** `GET /welcomes`: this device's pending welcomes, oldest first. */
    suspend fun pendingWelcomes(session: Session): ApiResult<List<PendingWelcomeEntry>> =
        exchange(Route.WELCOMES, body = null, session) { bytes -> decode(PendingWelcomesResponse.serializer(), bytes)?.let(::readWelcomes) }

    /** `POST /welcomes/{id}/ack`: deletes one pending welcome of this device. */
    suspend fun ackWelcome(
        session: Session,
        welcomeId: String,
    ): ApiResult<Unit> {
        require(UUID.matches(welcomeId)) { "welcome ids are canonical UUIDs" }
        return exchange(Route.ACK_WELCOME, body = null, session, pathSuffix = "/$welcomeId/ack") { }
    }

    private fun groupPath(
        groupId: ByteArray,
        rest: String,
    ): String {
        require(groupId.size in MIN_GROUP_ID_BYTES..MAX_GROUP_ID_BYTES) { "group id out of range" }
        return "/${Base64Url.encode(groupId)}$rest"
    }

    override fun close() = client.close()

    /** An endpoint: its method, path and the one status that means success. */
    private enum class Route(
        val method: HttpMethod,
        val path: String,
        val success: HttpStatusCode,
        val maxBody: Int = MAX_BODY_BYTES,
    ) {
        CHALLENGE(HttpMethod.Post, "/auth/challenge", HttpStatusCode.OK),
        REGISTER(HttpMethod.Post, "/accounts", HttpStatusCode.Created),
        LOGIN(HttpMethod.Post, "/auth/login", HttpStatusCode.OK),
        LOGOUT(HttpMethod.Post, "/auth/logout", HttpStatusCode.NoContent),
        DEVICES(HttpMethod.Get, "/devices", HttpStatusCode.OK),
        UPLOAD_KEY_PACKAGES(HttpMethod.Post, "/key-packages", HttpStatusCode.OK),
        KEY_PACKAGE_STATUS(HttpMethod.Get, "/key-packages/status", HttpStatusCode.OK),
        CLAIM_KEY_PACKAGES(HttpMethod.Post, "/key-packages/claim", HttpStatusCode.OK),
        CREATE_INVITE(HttpMethod.Post, "/invites", HttpStatusCode.Created),
        LIST_INVITES(HttpMethod.Get, "/invites", HttpStatusCode.OK),
        REVOKE_INVITE(HttpMethod.Delete, "/invites", HttpStatusCode.NoContent),
        MEMBERS(HttpMethod.Get, "/members", HttpStatusCode.OK, MAX_MEMBERS_BODY_BYTES),
        GIF_PROVIDER(HttpMethod.Get, "/config/gif-provider", HttpStatusCode.OK, MAX_GIF_PROVIDER_BODY_BYTES),
        CREATE_GROUP(HttpMethod.Post, "/groups", HttpStatusCode.OK),
        POST_MESSAGE(HttpMethod.Post, "/groups", HttpStatusCode.OK),
        FETCH_MESSAGES(HttpMethod.Get, "/groups", HttpStatusCode.OK, MAX_DELIVERY_BODY_BYTES),
        GROUP_STATUS(HttpMethod.Get, "/groups", HttpStatusCode.OK),
        ACK_MESSAGES(HttpMethod.Post, "/groups", HttpStatusCode.NoContent),
        LEAVE_GROUP(HttpMethod.Post, "/groups", HttpStatusCode.NoContent),
        WELCOMES(HttpMethod.Get, "/welcomes", HttpStatusCode.OK, MAX_DELIVERY_BODY_BYTES),
        ACK_WELCOME(HttpMethod.Post, "/welcomes", HttpStatusCode.NoContent),
    }

    /**
     * One request and its answer. [read] turns the body of a successful answer into a value, or null if it breaks the
     * protocol. The response is streamed (`execute`), so an oversized body is never buffered in full.
     */
    @Suppress("TooGenericExceptionCaught") // See the catch below.
    private suspend fun <T> exchange(
        route: Route,
        body: String?,
        session: Session?,
        pathSuffix: String = "",
        read: (ByteArray) -> T?,
    ): ApiResult<T> {
        require(session == null || session.endpoint.sameAs(endpoint)) { "session belongs to another endpoint" }
        return try {
            client
                .prepareRequest {
                    method = route.method
                    url(endpoint.baseUrl + route.path + pathSuffix)
                    header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION.toString())
                    session?.let { header(HttpHeaders.Authorization, "Bearer ${it.token}") }
                    body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
                }.execute { response -> answer(response, route.success, route.maxBody, read) }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // Engines fail with platform-specific types, and on the web Ktor's fetch engine reports every network
            // failure as kotlin.Error, not Exception (#94 CR-2). All of them mean NETWORK.
            ApiResult.Failed(ApiError.NETWORK)
        }
    }

    companion object {
        /** Cap on any response body; every answer of these endpoints is far smaller (#79 SEC-8). */
        const val MAX_BODY_BYTES = 64 * 1024

        /** `GET /members` may carry 200 members with 10 devices each (ADR-0014): about 240 KiB at worst. */
        internal const val MAX_MEMBERS_BODY_BYTES = 256 * 1024

        /** The answer is one 128-character key in a small object. */
        private const val MAX_GIF_PROVIDER_BODY_BYTES = 1024

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SOCKET_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L

        /** The crate signs challenges of 16..64 bytes (ADR-0009); the server sends 56. */
        private val CHALLENGE_SIZES = 16..64

        /** The server's caps (ADR-0012): 50 KeyPackages of at most 2,048 bytes per upload; 1..4 suites per claim. */
        const val MAX_UPLOAD_BATCH = 50
        const val MAX_KEY_PACKAGE_BYTES = 2048
        const val MAX_SUITES = 4
        private const val MAX_SUITE_ID = 65_535

        /** The server lists at most this many invites (ADR-0010). */
        private const val MAX_LISTED_INVITES = 50

        /** 9999-12-31T23:59:59Z: every time from the server is at most this, so formatting it never throws. */
        internal const val MAX_TIME = 253_402_300_799L

        /** A claim returns at most this many devices (ADR-0012). */
        internal const val MAX_CLAIMED_DEVICES = 10
        internal val USERNAME = Regex("^[a-z0-9_]{3,32}$")

        /** Device ids are lowercase canonical UUIDs (ADR-0009). */
        internal val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

private suspend fun <T> answer(
    response: HttpResponse,
    success: HttpStatusCode,
    maxBody: Int,
    read: (ByteArray) -> T?,
): ApiResult<T> {
    val bytes = response.readCapped(maxBody)
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

@Suppress("CyclomaticComplexMethod") // One branch per closed error code.
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
            ApiErrorCode.NOT_FOUND -> ApiError.NOT_FOUND
            ApiErrorCode.KEY_PACKAGE_INVALID -> ApiError.KEY_PACKAGE_INVALID
            ApiErrorCode.KEY_PACKAGE_LIMIT -> ApiError.KEY_PACKAGE_LIMIT
            ApiErrorCode.INVITE_LIMIT -> ApiError.INVITE_LIMIT
            ApiErrorCode.ENVELOPE_INVALID -> ApiError.ENVELOPE_INVALID
            ApiErrorCode.ENVELOPE_CONFLICT -> ApiError.ENVELOPE_CONFLICT
            ApiErrorCode.EPOCH_CONFLICT -> ApiError.EPOCH_CONFLICT
            ApiErrorCode.GROUP_FULL -> ApiError.GROUP_FULL
            ApiErrorCode.WELCOME_LIMIT -> ApiError.WELCOME_LIMIT
            ApiErrorCode.GROUP_LIMIT -> ApiError.GROUP_LIMIT
            ApiErrorCode.PAYLOAD_TOO_LARGE -> ApiError.PAYLOAD_TOO_LARGE
            else -> ApiError.SERVER_ERROR
        },
    )

/**
 * The account id is a canonical UUID, every device is listed once with a canonical id; a KeyPackage is canonical
 * base64url of 1..2,048 bytes, or null.
 */
private fun readClaimed(response: ClaimKeyPackagesResponse): ClaimedKeyPackages? {
    val devices = response.devices
    val decoded = devices.map { device -> device.keyPackage?.let { Base64Url.decode(it, 1..GifItUpApi.MAX_KEY_PACKAGE_BYTES) } }
    val valid =
        GifItUpApi.UUID.matches(response.accountId) &&
            devices.size <= GifItUpApi.MAX_CLAIMED_DEVICES &&
            devices.map { it.deviceId }.distinct().size == devices.size &&
            devices.indices.all { i ->
                GifItUpApi.UUID.matches(devices[i].deviceId) && (devices[i].keyPackage == null || decoded[i] != null)
            }
    return if (valid) {
        ClaimedKeyPackages(response.accountId, devices.indices.map { ClaimedKeyPackage(devices[it].deviceId, decoded[it]) })
    } else {
        null
    }
}

/** A session token is 32 random bytes (ADR-0009). */
private const val TOKEN_BYTES = 32

private fun readSession(
    bytes: ByteArray,
    endpoint: ApiEndpoint,
): Session? =
    decode(SessionResponse.serializer(), bytes)
        ?.takeIf {
            GifItUpApi.UUID.matches(it.deviceId) && Base64Url.decode(it.sessionToken, TOKEN_BYTES..TOKEN_BYTES) != null &&
                it.expiresAt > 0
        }?.let { Session(it.deviceId, it.sessionToken, it.expiresAt, endpoint) }

/** The body, or null if it is larger than [max] (by header or by content). */
private suspend fun HttpResponse.readCapped(max: Int): ByteArray? {
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
