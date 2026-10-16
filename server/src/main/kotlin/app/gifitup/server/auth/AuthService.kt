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
 * - **register** (#68 round 0, SEC-3): global limit → input format → username rule and reserved names → challenge MAC
 *   and age → signature over the username with the submitted key → challenge consumed → in one transaction: invite
 *   redeemed → account (a taken name rolls back, so the invite stays unused) → device → session. A taken username is
 *   only ever revealed to someone holding a valid invite.
 * - **login:** global limit → input format → challenge MAC and age → the device exists → its per-device limit has room
 *   → signature over the device id → challenge consumed → per-device token spent (on success only, SEC-2) → session.
 *   An unknown device, a bad signature and a stale or reused challenge all fail as [AuthError.UNAUTHORIZED].
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
        if (!limits.authAttempts.spend(GLOBAL)) return AuthOutcome.Failed(AuthError.RATE_LIMITED)
        val subject = AuthSubject.Username.of(username)
        val key = Base64Url.decode(signaturePublicKey, SignatureVerifier.PUBLIC_KEY_SIZE)
        val challengeBytes = Base64Url.decode(challenge, Challenges.CHALLENGE_SIZE)
        val signatureBytes = Base64Url.decode(signature, SignatureVerifier.SIGNATURE_SIZE)
        val publicKey = key?.let(SignatureVerifier::decodePublicKey)
        return when {
            subject == null || !INVITE_CODE.matches(inviteCode) || challengeBytes == null || signatureBytes == null ||
                publicKey == null -> AuthOutcome.Failed(AuthError.BAD_REQUEST)

            UsernamePolicy.isReserved(username) -> AuthOutcome.Failed(AuthError.USERNAME_UNAVAILABLE)

            !verified(challengeBytes, subject, signatureBytes) { publicKey } -> AuthOutcome.Failed(AuthError.UNAUTHORIZED)

            else -> createAccount(subject, inviteCode, checkNotNull(key))
        }
    }

    fun login(
        deviceId: String,
        challenge: String,
        signature: String,
    ): AuthOutcome<IssuedSession> {
        if (!limits.authAttempts.spend(GLOBAL)) return AuthOutcome.Failed(AuthError.RATE_LIMITED)
        val id = deviceId.takeIf { CANONICAL_UUID.matches(it) }?.let(UUID::fromString)
        val challengeBytes = Base64Url.decode(challenge, Challenges.CHALLENGE_SIZE)
        val signatureBytes = Base64Url.decode(signature, SignatureVerifier.SIGNATURE_SIZE)
        // Cheap checks before the database: a forged or stale challenge costs one HMAC.
        val device =
            id
                ?.takeIf { challengeBytes != null && challenges.isValid(challengeBytes) }
                ?.let { db.transaction { c -> devices.find(c, it) } }
        return when {
            challengeBytes == null || signatureBytes == null -> {
                AuthOutcome.Failed(AuthError.BAD_REQUEST)
            }

            device == null -> {
                AuthOutcome.Failed(AuthError.UNAUTHORIZED)
            }

            !limits.deviceLogins.allows(device.id.toString()) -> {
                AuthOutcome.Failed(AuthError.RATE_LIMITED)
            }

            !verified(challengeBytes, AuthSubject.Device(device.id), signatureBytes) {
                SignatureVerifier.decodePublicKey(device.signatureKey)
            } -> {
                AuthOutcome.Failed(AuthError.UNAUTHORIZED)
            }

            else -> {
                limits.deviceLogins.spend(device.id.toString())
                AuthOutcome.Ok(db.transaction { c -> issueSession(c, device) })
            }
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

    private fun createAccount(
        subject: AuthSubject.Username,
        inviteCode: String,
        key: ByteArray,
    ): AuthOutcome<IssuedSession> {
        val now = clock.instant()
        return try {
            AuthOutcome.Ok(
                db.transaction { c ->
                    // Order matters (SEC-3): the invite first, so a taken username is revealed only with a valid invite.
                    rollbackUnless(invites.redeem(c, inviteCode, now), AuthError.INVITE_INVALID)
                    val accountId = UUID.randomUUID()
                    rollbackUnless(accounts.insert(c, accountId, subject.text, now), AuthError.USERNAME_UNAVAILABLE)
                    val device = DeviceRecord(UUID.randomUUID(), accountId, key, now, now)
                    rollbackUnless(devices.insert(c, device.id, accountId, key, now), AuthError.BAD_REQUEST)
                    issueSession(c, device)
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

    /** Aborts the registration transaction (so the invite stays unused) unless [ok]. */
    private fun rollbackUnless(
        ok: Boolean,
        error: AuthError,
    ) {
        if (!ok) throw Rollback(error)
    }

    /** Aborts the registration transaction with a domain error. Carries no data. */
    private class Rollback(
        val error: AuthError,
    ) : RuntimeException(null, null, false, false)

    companion object {
        const val TOKEN_SIZE = 32
        val SESSION_LIFETIME: Duration = Duration.ofHours(24)
        private const val GLOBAL = "global"
        private val INVITE_CODE = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/**
 * The auth rate limits (ADR-0009, SEC-1, SEC-2, SEC-M0-5): never keyed on IP.
 * - [challenges]: one global bucket for `POST /auth/challenge` (10/s, burst 60). Cheap and stateless, so generous.
 * - [authAttempts]: one global bucket for login and register (5/s, burst 30), spent before any work: the CPU guard.
 * - [deviceLogins]: per existing device, spent only on a successful login (10 per hour), so someone else's failed
 *   attempts can't lock a device out; buckets exist only for real devices (SEC-30).
 */
class AuthLimits(
    clock: Clock,
    val challenges: KeyedRateLimiter = KeyedRateLimiter(clock, CHALLENGE_BURST, Duration.ofMillis(CHALLENGE_REFILL_MS)),
    val authAttempts: KeyedRateLimiter = KeyedRateLimiter(clock, AUTH_BURST, Duration.ofMillis(AUTH_REFILL_MS)),
    val deviceLogins: KeyedRateLimiter = KeyedRateLimiter(clock, DEVICE_BURST, Duration.ofMinutes(DEVICE_REFILL_MINUTES)),
) {
    private companion object {
        const val CHALLENGE_BURST = 60
        const val CHALLENGE_REFILL_MS = 100L
        const val AUTH_BURST = 30
        const val AUTH_REFILL_MS = 200L
        const val DEVICE_BURST = 10
        const val DEVICE_REFILL_MINUTES = 6L
    }
}
