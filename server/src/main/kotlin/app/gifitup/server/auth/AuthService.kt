package app.gifitup.server.auth

import app.gifitup.server.db.AccountRepository
import app.gifitup.server.db.DeviceRecord
import app.gifitup.server.db.DeviceRepository
import app.gifitup.server.db.SessionRecord
import app.gifitup.server.db.SessionRepository
import app.gifitup.server.db.TransactionRunner
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Why an auth request failed. Closed: the routes map each to one status and one [app.gifitup.protocol.ApiErrorCode]. */
enum class AuthError {
    BAD_REQUEST,
    UNAUTHORIZED,
    INVITE_INVALID,
    USERNAME_UNAVAILABLE,
    RATE_LIMITED,
}

sealed interface AuthOutcome<out T> {
    data class Ok<T>(
        val value: T,
    ) : AuthOutcome<T>

    data class Failed(
        val error: AuthError,
    ) : AuthOutcome<Nothing>
}

/** A freshly issued session. [token] goes to the client once and is never stored or logged. */
data class IssuedSession(
    val deviceId: UUID,
    val token: String,
    val expiresAt: Instant,
)

/** The session behind an authenticated request, and its token hash (for logout). */
class AuthenticatedSession(
    val record: SessionRecord,
    internal val tokenHash: ByteArray,
)

/**
 * Registration, login, logout and session lookup (ADR-0009). HTTP-agnostic: the routes parse JSON and map [AuthError]s.
 *
 * Order of checks, cheapest and least revealing first:
 * - **register** (#68 round 0, SEC-3): input format → username rule and reserved names → challenge MAC and age → global
 *   budget → signature over the username with the submitted key → challenge consumed → in one transaction: if this key
 *   is already registered under this username, a retry whose reply was lost (#73 CR-1) gets a new session for that
 *   device (per-device budget applies); otherwise invite redeemed → account (a taken name rolls back, so the invite
 *   stays unused) → device → session. A taken username is only ever revealed to someone holding a valid invite.
 * - **login:** input format → challenge MAC and age → global budget → the device exists → signature over the device id
 *   → challenge consumed → per-device budget spent (#73 SEC-36: only a caller whose signature checked out can see a
 *   429). An unknown device, a bad signature and a stale or reused challenge all fail as [AuthError.UNAUTHORIZED].
 *
 * The global budget is spent only after the cheap checks pass (#73 SEC-34): a request needs a fresh, genuine challenge
 * before it costs anything shared. The remaining lockout risk is accepted (threat model, "Accepted risks").
 *
 * The signed bytes are always rebuilt from the server's own [origin], never from the request (#70 SEC-25).
 */
@Suppress("LongParameterList") // Constructor injection of the use case's collaborators (ADR-0004).
class AuthService(
    private val db: TransactionRunner,
    private val origin: CanonicalOrigin,
    private val challenges: Challenges,
    private val invites: InviteRedeemer,
    private val clock: Clock,
    private val limits: AuthLimits = AuthLimits(clock),
    private val random: SecureRandom = SecureRandom(),
) {
    private val accounts = AccountRepository()
    private val devices = DeviceRepository()
    private val sessions = SessionRepository()

    fun challenge(): AuthOutcome<String> =
        if (!limits.challenges.spend(GLOBAL)) {
            AuthOutcome.Failed(AuthError.RATE_LIMITED)
        } else {
            AuthOutcome.Ok(Base64Url.encode(challenges.issue()))
        }

    fun register(
        username: String,
        inviteCode: String,
        signaturePublicKey: String,
        challenge: String,
        signature: String,
    ): AuthOutcome<IssuedSession> {
        val subject = AuthSubject.Username.of(username)
        val key = Base64Url.decode(signaturePublicKey, SignatureVerifier.PUBLIC_KEY_SIZE)
        val challengeBytes = Base64Url.decode(challenge, Challenges.CHALLENGE_SIZE)
        val signatureBytes = Base64Url.decode(signature, SignatureVerifier.SIGNATURE_SIZE)
        val publicKey = key?.let(SignatureVerifier::decodePublicKey)
        return when {
            subject == null || !INVITE_CODE.matches(inviteCode) || challengeBytes == null || signatureBytes == null ||
                publicKey == null -> AuthOutcome.Failed(AuthError.BAD_REQUEST)

            UsernamePolicy.isReserved(username) -> AuthOutcome.Failed(AuthError.USERNAME_UNAVAILABLE)

            !challenges.isValid(challengeBytes) -> AuthOutcome.Failed(AuthError.UNAUTHORIZED)

            !limits.authAttempts.spend(GLOBAL) -> AuthOutcome.Failed(AuthError.RATE_LIMITED)

            !verified(challengeBytes, subject, signatureBytes) { publicKey } -> AuthOutcome.Failed(AuthError.UNAUTHORIZED)

            else -> createOrResume(subject, inviteCode, checkNotNull(key))
        }
    }

    fun login(
        deviceId: String,
        challenge: String,
        signature: String,
    ): AuthOutcome<IssuedSession> {
        val id = deviceId.takeIf { CANONICAL_UUID.matches(it) }?.let(UUID::fromString)
        val challengeBytes = Base64Url.decode(challenge, Challenges.CHALLENGE_SIZE)
        val signatureBytes = Base64Url.decode(signature, SignatureVerifier.SIGNATURE_SIZE)
        return when {
            challengeBytes == null || signatureBytes == null -> AuthOutcome.Failed(AuthError.BAD_REQUEST)

            // Cheap checks before anything shared: a forged or stale challenge costs one HMAC.
            id == null || !challenges.isValid(challengeBytes) -> AuthOutcome.Failed(AuthError.UNAUTHORIZED)

            !limits.authAttempts.spend(GLOBAL) -> AuthOutcome.Failed(AuthError.RATE_LIMITED)

            else -> loginDevice(id, challengeBytes, signatureBytes)
        }
    }

    private fun loginDevice(
        id: UUID,
        challenge: ByteArray,
        signature: ByteArray,
    ): AuthOutcome<IssuedSession> {
        val device = db.transaction { c -> devices.find(c, id) }
        val ok =
            device != null &&
                verified(challenge, AuthSubject.Device(device.id), signature) {
                    SignatureVerifier.decodePublicKey(device.signatureKey)
                }
        return when {
            device == null || !ok -> AuthOutcome.Failed(AuthError.UNAUTHORIZED)

            // Spent only after the signature verified (SEC-2, SEC-36), in one atomic step (#73 CR-3, SEC-37).
            !limits.deviceLogins.spend(device.id.toString()) -> AuthOutcome.Failed(AuthError.RATE_LIMITED)

            else -> AuthOutcome.Ok(db.transaction { c -> issueSession(c, device) })
        }
    }

    /** The session for a bearer token, or null. Touches the device's `last_seen_at` (hourly resolution). */
    fun authenticate(token: String): AuthenticatedSession? {
        val bytes = Base64Url.decode(token, TOKEN_SIZE) ?: return null
        val hash = sha256(bytes)
        val now = clock.instant()
        return db
            .transaction { c ->
                sessions.findValid(c, hash, now)?.also { devices.touch(c, it.deviceId, now) }
            }?.let { AuthenticatedSession(it, hash) }
    }

    fun logout(session: AuthenticatedSession) {
        db.transaction { c -> sessions.delete(c, session.tokenHash) }
    }

    fun devices(session: AuthenticatedSession): List<DeviceRecord> =
        db.transaction { c -> devices.listForAccount(c, session.record.accountId) }

    /**
     * Challenge fresh → signature valid → challenge consumed. Consuming only after a valid signature keeps the used
     * set limited to successful requests (SEC-1); a `false` from [Challenges.consume] (reused or set full) fails.
     */
    private fun verified(
        challenge: ByteArray,
        subject: AuthSubject,
        signature: ByteArray,
        key: () -> java.security.PublicKey?,
    ): Boolean {
        val publicKey = if (challenges.isValid(challenge)) key() else null
        return publicKey != null &&
            SignatureVerifier.verify(publicKey, authSignContent(challenge, origin, subject), signature) &&
            challenges.consume(challenge)
    }

    /**
     * Creates the account, or resumes it (#73 CR-1, owner decision 2026-10-16): if this key is already registered under
     * this username, the caller is that device retrying a registration whose reply was lost, and gets a new session for
     * it. The signature over a fresh challenge proves it holds the key, so this is a login and costs no invite. The same
     * key under another username is refused.
     */
    private fun createOrResume(
        subject: AuthSubject.Username,
        inviteCode: String,
        key: ByteArray,
    ): AuthOutcome<IssuedSession> {
        val now = clock.instant()
        return try {
            AuthOutcome.Ok(
                db.transaction { c ->
                    val existing = devices.findByKey(c, key)
                    when {
                        existing == null -> {
                            // Order matters (SEC-3): the invite first, so a taken username is revealed only with a
                            // valid invite.
                            rollbackUnless(invites.redeem(c, inviteCode, now), AuthError.INVITE_INVALID)
                            val accountId = UUID.randomUUID()
                            rollbackUnless(accounts.insert(c, accountId, subject.text, now), AuthError.USERNAME_UNAVAILABLE)
                            val device = DeviceRecord(UUID.randomUUID(), accountId, key, now, now)
                            rollbackUnless(devices.insert(c, device.id, accountId, key, now), AuthError.BAD_REQUEST)
                            issueSession(c, device)
                        }

                        existing.second != subject.text -> {
                            throw Rollback(AuthError.BAD_REQUEST)
                        }

                        else -> {
                            rollbackUnless(limits.deviceLogins.spend(existing.first.id.toString()), AuthError.RATE_LIMITED)
                            issueSession(c, existing.first)
                        }
                    }
                },
            )
        } catch (e: Rollback) {
            AuthOutcome.Failed(e.error)
        }
    }

    private fun issueSession(
        connection: java.sql.Connection,
        device: DeviceRecord,
    ): IssuedSession {
        val now = clock.instant()
        val token = ByteArray(TOKEN_SIZE).also { random.nextBytes(it) }
        val expiresAt = now.plus(SESSION_LIFETIME)
        sessions.insert(connection, sha256(token), device.id, now, expiresAt)
        devices.touch(connection, device.id, now)
        return IssuedSession(device.id, Base64Url.encode(token), expiresAt)
    }

    companion object {
        const val TOKEN_SIZE = 32
        val SESSION_LIFETIME: Duration = Duration.ofHours(24)
        private const val GLOBAL = "global"
        private val INVITE_CODE = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** Aborts the registration transaction (so the invite stays unused) with a domain error. Carries no data. */
private class Rollback(
    val error: AuthError,
) : RuntimeException(null, null, false, false)

/** Aborts the registration transaction unless [ok]. */
private fun rollbackUnless(
    ok: Boolean,
    error: AuthError,
) {
    if (!ok) throw Rollback(error)
}

/**
 * The auth rate limits (ADR-0009, SEC-1, SEC-2, SEC-M0-5): never keyed on IP.
 * - [challenges]: one global bucket for `POST /auth/challenge` (100/s, burst 200). Cheap and stateless, so generous.
 * - [authAttempts]: one global bucket for login and register (50/s, burst 100), spent only once the request has a
 *   fresh, genuine challenge, just before the signature check and the database: the CPU guard (#73 SEC-34).
 * - [deviceLogins]: per existing device, spent only on a successful login (10 per hour), so someone else's failed
 *   attempts can't lock a device out; buckets exist only for real devices (SEC-30).
 */
class AuthLimits(
    clock: Clock,
    val challenges: KeyedRateLimiter = KeyedRateLimiter(clock, CHALLENGE_BURST, Duration.ofMillis(CHALLENGE_REFILL_MS)),
    val authAttempts: KeyedRateLimiter = KeyedRateLimiter(clock, AUTH_BURST, Duration.ofMillis(AUTH_REFILL_MS)),
    val deviceLogins: KeyedRateLimiter = KeyedRateLimiter(clock, DEVICE_BURST, Duration.ofMinutes(DEVICE_REFILL_MINUTES)),
) {
    // Sized to the work behind them (#73 SEC-34): a challenge is one HMAC (microseconds); a counted login or register
    // is one Ed25519 verification plus a small transaction. Generous on purpose: their only job is to cap CPU and
    // database load, and a stingy global bucket is easier to drain for everyone.
    private companion object {
        const val CHALLENGE_BURST = 200
        const val CHALLENGE_REFILL_MS = 10L
        const val AUTH_BURST = 100
        const val AUTH_REFILL_MS = 20L
        const val DEVICE_BURST = 10
        const val DEVICE_REFILL_MINUTES = 6L
    }
}
