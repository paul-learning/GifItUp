package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.AuthPurpose
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.crypto.SecureRandomSource
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.SnapshotKeyStore
import app.gifitup.shared.data.storage.StoredIdentity
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Removes the history an earlier account or an interrupted wipe left behind (records first, then the key entry). */
fun interface HistoryLeftovers {
    suspend fun clear()

    companion object {
        val None: HistoryLeftovers = HistoryLeftovers { }
    }
}

/**
 * Registers this device's account with an invite (ADR-0009, ADR-0010), for the dev CLI now and onboarding (#17) later.
 *
 * - **Persist before register** (#79 SEC-5): a new snapshot key comes from the platform CSPRNG and is stored before
 *   the MLS client is created (which zeroes the caller's copy); the identity is stored before the first request. So if
 *   the reply is lost, calling this again with the same username resumes with the same key and a fresh challenge, and
 *   the server treats it as a retry instead of spending a second invite.
 * - **A resume can see transient refusals** (#73 CR-6): the first attempt may still be committing, so a `403` or `409`
 *   on a resumed attempt is retried once after [TRANSIENT_RETRY_DELAY].
 * - **A refused first attempt discards the pending identity** (#95 CR-2): the key was never sent before, so after a
 *   `400`, `403` or `409` no account can hold it, and identity and key are cleared so the user can try again (e.g. with
 *   another username). **A refused resume keeps them** (#95 CR-5, SEC-6): the lost attempt it resumes may still be
 *   committing on the server, and discarding its key would orphan that account. The user decides, through
 *   [discardPending]. Any other failure keeps them for a resume.
 * - Every attempt fetches a new challenge; the origin signed is the configured one, never one the server sent.
 * - The invite code is used for the request only and never stored.
 * - **One call at a time** (#95 CR-3, SEC-1, SEC-7; #108 SEC-15): registrations and [discardPending] hold the
 *   device's [DeviceLock], which login and wipe share, so two calls can't each create a key and leave a snapshot next to
 *   the wrong one, and a discard or a wipe can't clear what a registration is saving. A store shared between processes
 *   (the CLI's state dir) needs its own lock too, which also covers its discard.
 */

@Suppress("LongParameterList") // Constructor injection of the use case's collaborators (ADR-0004).
class RegisterDevice(
    private val api: GifItUpApi,
    private val mls: GuardedMlsFactory,
    private val identities: IdentityStore,
    private val keys: SnapshotKeyStore,
    private val random: SecureRandomSource,
    private val lock: DeviceLock,
    private val leftovers: HistoryLeftovers,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
) {
    /** The origin this use case sends to (`api.endpoint.origin`): the screens compare it with what the user confirmed. */
    val origin: String get() = api.endpoint.origin

    suspend operator fun invoke(
        username: String,
        inviteCode: String,
    ): AuthResult<Session> = guarded { registerWith(username, inviteCode) }

    /**
     * Finishes a pending registration without asking for the invite code again (#17 plan review, SEC-4): after a lost
     * reply, a reload or a restart the code is gone on purpose, but the server treats a request with the same key and
     * username as a login and needs no invite (ADR-0009, step 4). The request still has to be well formed, so it
     * carries [RESUME_PLACEHOLDER]: a syntactically valid code that is not a real invite. If the first attempt never
     * reached the server, the placeholder is refused as an unknown invite (`INVITE_INVALID`) and the identity stays for
     * the user to discard. Needs a pending identity: none is `NOT_REGISTERED`, a registered one `ALREADY_REGISTERED`.
     */
    suspend fun resume(): AuthResult<Session> =
        guarded {
            val stored = identities.load()
            when {
                stored == null -> failed(AuthError.Local.NOT_REGISTERED)
                stored.deviceId != null -> failed(AuthError.Local.ALREADY_REGISTERED)
                else -> registerWith(stored.username, RESUME_PLACEHOLDER)
            }
        }

    private suspend fun guarded(block: suspend () -> AuthResult<Session>): AuthResult<Session> =
        lock.holding {
            try {
                block()
            } catch (e: MlsException) {
                e.toAuthFailure()
            } catch (e: LocalStateException) {
                e.toAuthFailure()
            } catch (ignored: RestoreBlockedException) {
                failed(AuthError.Local.RESTORE_BLOCKED)
            }
        }

    private suspend fun registerWith(
        username: String,
        inviteCode: String,
    ): AuthResult<Session> =
        when (val start = prepare(username, inviteCode)) {
            is Start.Refused -> failed(start.error)
            is Start.Ready -> start.client.use { register(it, start.resumed, inviteCode) }
        }

    /**
     * Discards a pending registration on the user's explicit request (#95 CR-5, SEC-6): after a refused resume, the user
     * decides whether the earlier attempt is lost. If that attempt did create the account, it is orphaned (the owner can
     * delete it). A registered identity is never discarded: [AuthError.Local.ALREADY_REGISTERED]. Runs under the device
     * lock, so it can't interleave with a registration (#95 SEC-7).
     */
    suspend fun discardPending(): AuthResult<Unit> =
        lock.holding {
            try {
                if (identities.load()?.deviceId != null) {
                    failed(AuthError.Local.ALREADY_REGISTERED)
                } else {
                    identities.clear()
                    keys.clear()
                    AuthResult.Ok(Unit)
                }
            } catch (e: LocalStateException) {
                // A record that can't be read may be a registered one: only a wipe removes it (WipeLocalData).
                e.toAuthFailure()
            }
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
                // A brand-new registration starts with no history: what an interrupted wipe or another account left would
                // block the new key. Before anything is created or sent; a failure aborts the registration.
                leftovers.clear()
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
        val copy = key.copyOf()
        val saved = runCatching { keys.save(copy) }
        copy.fill(0)
        saved.onFailure { key.fill(0) }.getOrThrow()
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
        } else if (!resumed && result is ApiResult.Failed && result.error in DEFINITE_REFUSALS) {
            identities.clear()
            keys.clear()
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

        /** 22 canonical base64url characters, never a real invite (a real one is 16 random bytes): see [resume]. */
        const val RESUME_PLACEHOLDER = "AAAAAAAAAAAAAAAAAAAAAA"

        /** Answers to a first attempt after which the server holds no account for its new key; see the class doc. */
        private val DEFINITE_REFUSALS = setOf(ApiError.BAD_REQUEST, ApiError.INVITE_INVALID, ApiError.USERNAME_UNAVAILABLE)
    }
}
