package app.gifitup.server.auth

import app.gifitup.keypackage.SignatureVerifier
import app.gifitup.server.db.AccountRepository
import app.gifitup.server.db.DeviceRecord
import app.gifitup.server.db.DeviceRepository
import app.gifitup.server.db.Joined
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
 * - **register** (#68 round 0, SEC-3): input format → username rule and reserved names → challenge checked and used up
 *   → global budget → signature over the username with the submitted key → in one transaction: if this key
 *   is already registered under this username, a retry whose reply was lost (#73 CR-1) gets a new session for that
 *   device (per-device budget applies); otherwise invite redeemed → account (a taken name rolls back, so the invite
 *   stays unused) → device → session. A taken username is only ever revealed to someone holding a valid invite.
 * - **login:** input format → challenge checked and used up → global budget → the device exists → signature over the
 *   device id → per-device budget spent (#73 SEC-36: only a caller whose signature checked out can see a 429). An
 *   unknown device, a bad signature and a stale or reused challenge all fail as [AuthError.UNAUTHORIZED].
 *
 * The global budget is spent only after the cheap checks pass (#73 SEC-34), and the challenge is used up **before** it
 * (#73 CR-7, SEC-39), whether the signature then verifies or not: every request that costs anything shared needs its
 * own fresh challenge from the challenge endpoint. The remaining lockout risk is accepted (threat model). Login and
 * the register retry both issue sessions; a later revocation check must cover both (#73 SEC-40).
 *
 * The signed bytes are always rebuilt from the server's own [origin], never from the request (#70 SEC-25).
 */
@Suppress("LongParameterList", "TooManyFunctions") // Constructor injection (ADR-0004); one function per use case.
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

    /** The per-account request budget, checked by the session interceptor (#76). */
    val requestBudget = AccountRequestBudget(limits.accountRequests)

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
            subject == null || !InviteCodes.isWellFormed(inviteCode) || challengeBytes == null || signatureBytes == null ||
                publicKey == null -> AuthOutcome.Failed(AuthError.BAD_REQUEST)

            UsernamePolicy.isReserved(username) -> AuthOutcome.Failed(AuthError.USERNAME_UNAVAILABLE)

            // Used up here, before any shared budget: a replayed challenge fails at this lookup (#73 CR-7, SEC-39).
            !challenges.consume(challengeBytes) -> AuthOutcome.Failed(AuthError.UNAUTHORIZED)

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
            // Used up here, before any shared budget: a replayed challenge fails at this lookup (#73 CR-7, SEC-39).
            id == null || !challenges.consume(challengeBytes) -> AuthOutcome.Failed(AuthError.UNAUTHORIZED)

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

    /**
     * True if [token] still names a live session. A read only: unlike [authenticate] it does not touch the device's
     * `last_seen_at`, so a socket's periodic re-check is not a write per ping. Throws if the database fails.
     */
    fun isLive(token: String): Boolean {
        val bytes = Base64Url.decode(token, TOKEN_SIZE) ?: return false
        val hash = sha256(bytes)
        return db.transaction { c -> sessions.findValid(c, hash, clock.instant()) != null }
    }

    fun logout(session: AuthenticatedSession) {
        db.transaction { c -> sessions.delete(c, session.tokenHash) }
    }

    fun devices(session: AuthenticatedSession): List<DeviceRecord> =
        db.transaction { c -> devices.listForAccount(c, session.record.accountId) }

    /** True if [signature] by the key from [key] covers [challenge], our [origin] and [subject]. */
    private fun verified(
        challenge: ByteArray,
        subject: AuthSubject,
        signature: ByteArray,
        key: () -> java.security.PublicKey?,
    ): Boolean {
        val publicKey = key()
        return publicKey != null &&
            SignatureVerifier.verify(publicKey, authSignContent(challenge, origin, subject), signature)
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
                            val invite = invites.redeem(c, inviteCode, now) ?: throw Rollback(AuthError.INVITE_INVALID)
                            val accountId = UUID.randomUUID()
                            val joined = invite.inviter?.let { Joined.InvitedBy(it) } ?: Joined.ViaAdmin
                            val created = accounts.insert(c, accountId, subject.text, now, joined)
                            rollbackUnless(created, AuthError.USERNAME_UNAVAILABLE)
                            // Not a client error: a failure here is a server bug, so it fails the request (500).
                            check(invites.recordUse(c, invite, accountId)) { "invite use not recorded" }
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
 * One bucket per account, shared by all its sessions and devices (#76). [spend] is false when the budget is used up.
 * Fails closed: if the limiter can't create a bucket (map full), [spend] is false too (#76 SEC-69); don't turn that
 * into an allow.
 */
class AccountRequestBudget(
    private val limiter: KeyedRateLimiter,
) {
    fun spend(session: AuthenticatedSession): Boolean = limiter.spend(session.record.accountId.toString())
}

/**
 * The auth rate limits (ADR-0009, SEC-1, SEC-2, SEC-M0-5): never keyed on IP.
 * - [challenges]: one global bucket for `POST /auth/challenge` (100/s, burst 200). Cheap and stateless, so generous.
 * - [authAttempts]: one global bucket for login and register (50/s, burst 100), spent only once the request has a
 *   fresh, genuine challenge, just before the signature check and the database: the CPU guard (#73 SEC-34).
 * - [deviceLogins]: per existing device, spent only on a successful login (10 per hour), so someone else's failed
 *   attempts can't lock a device out; buckets exist only for real devices (SEC-30).
 * - [accountRequests]: per account, every authenticated HTTP request except logout (#76; burst 120, 4/s). Keys exist
 *   only for accounts with a valid session.
 */
class AuthLimits(
    clock: Clock,
    val challenges: KeyedRateLimiter = KeyedRateLimiter(clock, CHALLENGE_BURST, Duration.ofMillis(CHALLENGE_REFILL_MS)),
    val authAttempts: KeyedRateLimiter = KeyedRateLimiter(clock, AUTH_BURST, Duration.ofMillis(AUTH_REFILL_MS)),
    val deviceLogins: KeyedRateLimiter = KeyedRateLimiter(clock, DEVICE_BURST, Duration.ofMinutes(DEVICE_REFILL_MINUTES)),
    val accountRequests: KeyedRateLimiter = KeyedRateLimiter(clock, ACCOUNT_BURST, Duration.ofMillis(ACCOUNT_REFILL_MS)),
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

        // Every authenticated request (#76): a session lookup plus a small query. 4/s sustained, bursts of 120;
        // normal app use never gets near it.
        const val ACCOUNT_BURST = 120
        const val ACCOUNT_REFILL_MS = 250L
    }
}
