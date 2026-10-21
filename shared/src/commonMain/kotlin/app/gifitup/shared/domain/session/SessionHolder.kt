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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The logged-in session of one app instance, in memory only (ADR-0009: 24 hours, no refresh; never stored, never in
 * saved UI state, never logged). Calls go through [withSession]:
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

    suspend fun <T> withSession(call: suspend (Session) -> ApiResult<T>): AuthResult<T> {
        val first = attempt(call)
        return if (first.unauthorized) retryAfterLogin(first.used, call) else first.result
    }

    /** A login still in flight finishes into the void: its result is not kept. */
    suspend fun clear() {
        mutex.withLock {
            session = null
            pending = null
            generation++
        }
    }

    private class Attempt<T>(
        val result: AuthResult<T>,
        val used: Session?,
        val unauthorized: Boolean,
    )

    private suspend fun <T> attempt(call: suspend (Session) -> ApiResult<T>): Attempt<T> =
        when (val login = ensureSession()) {
            is AuthResult.Failed -> {
                Attempt(login, used = null, unauthorized = false)
            }

            is AuthResult.Ok -> {
                val result = call(login.value)
                Attempt(result.toAuth(), login.value, unauthorized = result is ApiResult.Failed && result.error == ApiError.UNAUTHORIZED)
            }
        }

    private suspend fun <T> retryAfterLogin(
        rejected: Session?,
        call: suspend (Session) -> ApiResult<T>,
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
            throw e
        } catch (ignored: Throwable) {
            AuthResult.Failed(AuthError.Local.UNEXPECTED)
        }

    private companion object {
        /** Renew a little early: the phone's clock and the server's can differ by minutes. */
        const val EXPIRY_MARGIN_SECONDS = 120L
    }
}
