package app.gifitup.shared.domain.session

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.toAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The logged-in session of one app instance, in memory only (ADR-0009: 24 hours, no refresh; never stored, never in
 * saved UI state, never logged). Calls go through [withSession], or [withSessionResult] for a use case that answers
 * with an [AuthResult] (the same rules):
 * - The first call, and any call after the session ran out (with a safety margin for clock skew), logs in with a fresh
 *   challenge. Logins are single-flight and run in the holder's own [scope], so a cancelled caller does not cancel the
 *   login the others wait for, and the waiters get the same typed result instead of starting more logins.
 * - A `401` comes before routing, so the call did nothing: the session is dropped and the call is repeated **once**
 *   after a new login. Nothing else is ever repeated: a timeout or a lost answer may have changed state (claim,
 *   upload, create-invite), so the caller decides.
 * - A failed login is returned as it is and not repeated. A `401` or `429` on the login never discards keys or a
 *   pending registration (#73 SEC-42): this class touches no storage.
 * - [clear] after a wipe, logout or origin change; the next call then answers `NOT_REGISTERED` from the login. Callers
 *   that were already waiting on a login that started before the clear get `NOT_REGISTERED` too, never its session.
 * - A login that throws anything (a bug, a platform error) is a closed `UNEXPECTED` failure for that attempt only.
 * One holder per endpoint (a [Session] carries its endpoint).
 */
@Suppress("TooManyFunctions") // One small function per way to use the one session of the device.
class SessionHolder(
    private val logIn: suspend () -> AuthResult<Session>,
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long,
) {
    private val mutex = Mutex()
    private var session: Session? = null
    private var pending: Deferred<AuthResult<Session>>? = null

    /** Bumped by [clear]: a login that was started before it must not hand out its session afterwards. */
    private var generation = 0
    private val generationFlow = MutableStateFlow(0)

    /**
     * The same counter, observable: it changes when [clear] runs (a wipe, a logout, an origin change). A long-lived connection
     * that was made with a session closes itself when it changes (the live-updates socket, #27).
     */
    val generations: StateFlow<Int> = generationFlow.asStateFlow()

    suspend fun <T> withSession(call: suspend (Session) -> ApiResult<T>): AuthResult<T> = withSessionResult { call(it).toAuth() }

    /**
     * [withSession] for a use case that already answers with an [AuthResult] (create, list and revoke invite): the
     * same rules, and the repeat after a `401` is decided on the call's own answer only, never on the login's.
     */
    suspend fun <T> withSessionResult(call: suspend (Session) -> AuthResult<T>): AuthResult<T> {
        val first = attempt(call)
        return if (first.unauthorized) retryAfterLogin(first.used, call) else first.result
    }

    /**
     * The current session, logging in if there is none (or it is about to expire). For a caller that needs the session itself
     * and not a call made with it, like the live-updates socket (#27); the same single login as [withSession].
     */
    suspend fun session(): AuthResult<Session> = ensureSession()

    /**
     * Drops [rejected] if it is still the session in use, so the next [session] logs in again; a session that was already
     * replaced, or dropped by [clear], is left alone. Never a wipe or a logout: only for a session the server refused (the
     * socket's close code 4401). Every login counts against the device's budget (ADR-0009), so a caller does this at most once
     * per refusal.
     */
    suspend fun invalidate(rejected: Session) {
        mutex.withLock { if (session === rejected) session = null }
    }

    /** Marks "now" for [adopt]: take it before the work that yields the session (registration). */
    suspend fun mark(): Int = mutex.withLock { generation }

    /**
     * Keeps a session the caller just got (registration logs in as part of it), so the next call needs no second login.
     * Ignored if [clear] ran since [since] was taken: a wipe or an origin change in between must not be undone.
     */
    suspend fun adopt(
        fresh: Session,
        since: Int,
    ) {
        mutex.withLock { if (generation == since) session = fresh }
    }

    /** A login still in flight finishes into the void: its result is not kept. */
    suspend fun clear() {
        mutex.withLock {
            session = null
            pending = null
            generation++
            generationFlow.value = generation
        }
    }

    private class Attempt<T>(
        val result: AuthResult<T>,
        val used: Session?,
        val unauthorized: Boolean,
    )

    private suspend fun <T> attempt(call: suspend (Session) -> AuthResult<T>): Attempt<T> =
        when (val login = ensureSession()) {
            is AuthResult.Failed -> {
                Attempt(login, used = null, unauthorized = false)
            }

            is AuthResult.Ok -> {
                val result = call(login.value)
                val unauthorized = result is AuthResult.Failed && result.error == AuthError.Api(ApiError.UNAUTHORIZED)
                Attempt(result, login.value, unauthorized)
            }
        }

    private suspend fun <T> retryAfterLogin(
        rejected: Session?,
        call: suspend (Session) -> AuthResult<T>,
    ): AuthResult<T> {
        mutex.withLock { if (session === rejected) session = null }
        return attempt(call).result
    }

    private class Started(
        val login: Deferred<AuthResult<Session>>,
        val generation: Int,
    )

    private suspend fun ensureSession(): AuthResult<Session> {
        val started =
            mutex.withLock {
                val live = session?.takeIf { it.expiresAt - EXPIRY_MARGIN_SECONDS > nowEpochSeconds() }
                if (live != null) return AuthResult.Ok(live)
                Started(pending ?: scope.async { safeLogIn() }.also { pending = it }, generation)
            }
        val result = started.login.await()
        return mutex.withLock {
            when {
                // A clear (wipe, logout, origin change) came while this login ran: its session is not for anyone.
                started.generation != generation -> {
                    AuthResult.Failed(AuthError.Local.NOT_REGISTERED)
                }

                else -> {
                    if (pending === started.login) {
                        pending = null
                        if (result is AuthResult.Ok) session = result.value
                    }
                    result
                }
            }
        }
    }

    /**
     * The login, with anything it throws turned into a closed failure: an exception inside the shared [Deferred] would
     * stay in `pending` for every later call, and in a scope without a supervisor it would cancel the whole scope.
     */
    @Suppress("TooGenericExceptionCaught") // Anything but cancellation becomes `UNEXPECTED`; no message is kept.
    private suspend fun safeLogIn(): AuthResult<Session> =
        try {
            logIn()
        } catch (e: CancellationException) {
            // Our own cancellation (the holder's scope ended) goes on; a timeout raised inside the login is just a failure.
            if (currentCoroutineContext().isActive) AuthResult.Failed(AuthError.Local.UNEXPECTED) else throw e
        } catch (ignored: Throwable) {
            AuthResult.Failed(AuthError.Local.UNEXPECTED)
        }

    private companion object {
        /** Renew a little early: the phone's clock and the server's can differ by minutes. */
        const val EXPIRY_MARGIN_SECONDS = 120L
    }
}
