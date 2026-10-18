package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.AuthPurpose
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.Device
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore

/**
 * Logs the registered device in (ADR-0009): a fresh challenge, signed for the configured origin and this device's id.
 * The session stays in memory with the caller; nothing about it is stored.
 */
class LogIn(
    private val api: GifItUpApi,
    private val mls: MlsClientFactory,
    private val identities: IdentityStore,
    private val keys: SnapshotKeyStore,
) {
    suspend operator fun invoke(): AuthResult<Session> =
        try {
            val origin = api.endpoint.origin
            val stored = identities.load()
            val deviceId = stored?.deviceId
            val key = if (stored?.origin == origin && deviceId != null) keys.load() else null
            when {
                stored == null || deviceId == null -> {
                    failed(AuthError.Local.NOT_REGISTERED)
                }

                stored.origin != origin -> {
                    failed(AuthError.Local.OTHER_ORIGIN)
                }

                key == null -> {
                    failed(AuthError.Local.STATE_INVALID)
                }

                else -> {
                    mls.restore(stored.snapshot, key).use { client ->
                        signedLogIn(deviceId) { client.signAuth(AuthPurpose.Login, it, origin, deviceId) }
                    }
                }
            }
        } catch (e: MlsException) {
            e.toAuthFailure()
        }

    private suspend fun signedLogIn(
        deviceId: String,
        sign: (ByteArray) -> ByteArray,
    ): AuthResult<Session> =
        api
            .challenge()
            .then { challenge -> api.login(deviceId, challenge, sign(challenge)) }
            .then { session ->
                // A session for another device is an answer outside the protocol.
                if (session.deviceId == deviceId) ApiResult.Ok(session) else ApiResult.Failed(ApiError.MALFORMED_RESPONSE)
            }.toAuth()
}

/** The account's own devices. */
class ListDevices(
    private val api: GifItUpApi,
) {
    suspend operator fun invoke(session: Session): AuthResult<List<Device>> = api.devices(session).toAuth()
}

/** Ends the session on the server. The caller drops its copy either way. */
class LogOut(
    private val api: GifItUpApi,
) {
    suspend operator fun invoke(session: Session): AuthResult<Unit> = api.logout(session).toAuth()
}
