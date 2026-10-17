package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.AuthPurpose
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Registers this device's account with an invite (ADR-0009, ADR-0010), for the dev CLI now and onboarding (#17) later.
 *
 * - **Persist before register** (#79 SEC-5): a new snapshot key comes from the platform CSPRNG and is stored before
 *   the MLS client is created (which zeroes the caller's copy); the identity is stored before the first request. So if
 *   the reply is lost, calling this again with the same username resumes with the same key and a fresh challenge, and
 *   the server treats it as a retry instead of spending a second invite.
 * - **A resume can see transient refusals** (#73 CR-6): the first attempt may still be committing, so a `403` or `409`
 *   on a resumed attempt is retried once after [TRANSIENT_RETRY_DELAY].
 * - Every attempt fetches a new challenge; the origin signed is the configured one, never one the server sent.
 * - The invite code is used for the request only and never stored.
 */
class RegisterDevice(
    private val api: GifItUpApi,
    private val mls: MlsClientFactory,
    private val identities: IdentityStore,
    private val keys: SnapshotKeyStore,
    private val random: SecureRandomSource,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
) {
    suspend operator fun invoke(
        username: String,
        inviteCode: String,
    ): AuthResult<Session> =
        try {
            when (val start = prepare(username, inviteCode)) {
                is Start.Refused -> failed(start.error)
                is Start.Ready -> start.client.use { register(it, start.resumed, inviteCode) }
            }
        } catch (e: MlsException) {
            e.toAuthFailure()
        }

    private sealed interface Start {
        class Ready(
            val client: MlsClient,
            val resumed: Boolean,
        ) : Start

        class Refused(
            val error: AuthError.Local,
        ) : Start
    }

    private suspend fun prepare(
        username: String,
        inviteCode: String,
    ): Start {
        val stored = identities.load()
        val refusal =
            when {
                !InputRules.isValidUsername(username) -> AuthError.Local.INVALID_USERNAME
                !InputRules.isValidInviteCode(inviteCode) -> AuthError.Local.INVALID_INVITE_CODE
                stored == null -> null
                stored.origin != api.endpoint.origin -> AuthError.Local.OTHER_ORIGIN
                stored.deviceId != null -> AuthError.Local.ALREADY_REGISTERED
                stored.username != username -> AuthError.Local.USERNAME_MISMATCH
                else -> null
            }
        return when {
            refusal != null -> {
                Start.Refused(refusal)
            }

            stored == null -> {
                Start.Ready(create(username), resumed = false)
            }

            else -> {
                keys.load()?.let { Start.Ready(mls.restore(stored.snapshot, it), resumed = true) }
                    ?: Start.Refused(AuthError.Local.STATE_INVALID)
            }
        }
    }

    /** Key first, then the client, then the pending identity: nothing reaches the server before both are stored. */
    private suspend fun create(username: String): MlsClient {
        val key = random.nextBytes(SNAPSHOT_KEY_SIZE)
        keys.save(key.copyOf())
        val client = mls.create(username, key)
        runCatching { identities.save(StoredIdentity(api.endpoint.origin, username, client.exportState(), deviceId = null)) }
            .onFailure { client.close() }
            .getOrThrow()
        return client
    }

    private suspend fun register(
        client: MlsClient,
        resumed: Boolean,
        inviteCode: String,
    ): AuthResult<Session> {
        var result = attempt(client, inviteCode)
        if (resumed && result is ApiResult.Failed && result.error in TRANSIENT_ON_RESUME) {
            wait(TRANSIENT_RETRY_DELAY)
            result = attempt(client, inviteCode)
        }
        if (result is ApiResult.Ok) {
            val username = client.publicIdentity().username
            identities.save(StoredIdentity(api.endpoint.origin, username, client.exportState(), result.value.deviceId))
        }
        return result.toAuth()
    }

    private suspend fun attempt(
        client: MlsClient,
        inviteCode: String,
    ): ApiResult<Session> =
        api.challenge().then { challenge ->
            val identity = client.publicIdentity()
            val signature = client.signAuth(AuthPurpose.Register, challenge, api.endpoint.origin, identity.username)
            api.register(identity.username, inviteCode, identity.signaturePublicKey, challenge, signature)
        }

    internal companion object {
        /** "A few seconds" (ADR-0009): long enough for the first attempt's transaction to finish. */
        val TRANSIENT_RETRY_DELAY = 3.seconds
        private val TRANSIENT_ON_RESUME = setOf(ApiError.INVITE_INVALID, ApiError.USERNAME_UNAVAILABLE)
    }
}
