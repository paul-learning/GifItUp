package app.gifitup.shared.domain.session

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.ApiResult
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SessionHolderTest {
    private var now = 1_000L
    private var logins = 0
    private var loginResult: (suspend () -> AuthResult<Session>)? = null

    private fun session(expiresAt: Long = now + 86_400) = Session("d", "t$logins", expiresAt, FakeAuthServer.ENDPOINT)

    private fun TestScope.holder() =
        SessionHolder(
            logIn = {
                logins++
                loginResult?.invoke() ?: AuthResult.Ok(session())
            },
            scope = backgroundScope,
            nowEpochSeconds = { now },
        )

    private fun ok(value: String = "ok") = ApiResult.Ok(value)

    @Test
    fun logsInOnceAndReusesTheSession() =
        runTest {
            val holder = holder()
            val used = mutableListOf<Session>()
            repeat(3) {
                holder.withSession {
                    used += it
                    ok()
                }
            }
            assertEquals(1, logins)
            assertSame(used[0], used[2])
        }

    @Test
    fun concurrentCallersShareOneLogin() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            loginResult = {
                gate.await()
                AuthResult.Ok(session())
            }
            val holder = holder()
            val calls = List(5) { async { holder.withSession { ok() } } }
            repeat(5) { yield() }
            gate.complete(Unit)
            calls.awaitAll().forEach { assertIs<AuthResult.Ok<String>>(it) }
            assertEquals(1, logins)
        }

    @Test
    fun aCancelledCallerDoesNotCancelTheLoginTheOthersWaitFor() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            loginResult = {
                gate.await()
                AuthResult.Ok(session())
            }
            val holder = holder()
            val first = launch { holder.withSession { ok() } }
            val second = async { holder.withSession { ok() } }
            repeat(5) { yield() }
            first.cancel()
            gate.complete(Unit)
            assertIs<AuthResult.Ok<String>>(second.await())
            assertEquals(1, logins)
        }

    @Test
    fun anExpiredSessionOrOneInsideTheMarginLogsInAgain() =
        runTest {
            loginResult = { AuthResult.Ok(session(expiresAt = now + 100)) } // inside the 120 s margin
            val holder = holder()
            holder.withSession { ok() }
            holder.withSession { ok() }
            assertEquals(2, logins, "a session about to run out is not used")
            loginResult = null
            holder.withSession { ok() }
            holder.withSession { ok() }
            assertEquals(3, logins)
            now += 86_400 // the 24 hours are over
            holder.withSession { ok() }
            assertEquals(4, logins)
        }

    @Test
    fun aRejectedSessionIsRenewedAndTheCallRepeatedExactlyOnce() =
        runTest {
            val holder = holder()
            var calls = 0
            val result =
                holder.withSession {
                    calls++
                    if (calls == 1) ApiResult.Failed(ApiError.UNAUTHORIZED) else ok()
                }
            assertIs<AuthResult.Ok<String>>(result)
            assertEquals(2, calls)
            assertEquals(2, logins, "a fresh login for the retry")

            calls = 0
            val twice =
                holder.withSession {
                    calls++
                    ApiResult.Failed(ApiError.UNAUTHORIZED)
                }
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.UNAUTHORIZED)), twice)
            assertEquals(2, calls, "never a third try")
        }

    @Test
    fun nothingButAnUnauthorizedAnswerIsRepeated() =
        runTest {
            val holder = holder()
            listOf(
                ApiError.NETWORK,
                ApiError.RATE_LIMITED,
                ApiError.SERVER_ERROR,
                ApiError.MALFORMED_RESPONSE,
                ApiError.KEY_PACKAGE_LIMIT,
            ).forEach { error ->
                var calls = 0
                val result =
                    holder.withSession<String> {
                        calls++
                        ApiResult.Failed(error)
                    }
                assertEquals(AuthResult.Failed(AuthError.Api(error)), result)
                assertEquals(1, calls, "$error is not repeated: the call may have changed state")
            }
            assertEquals(1, logins)
        }

    @Test
    fun aFailedLoginIsReturnedNotRepeatedAndNeverRunsTheCall() =
        runTest {
            loginResult = { AuthResult.Failed(AuthError.Api(ApiError.RATE_LIMITED)) }
            val holder = holder()
            var calls = 0
            val result =
                holder.withSession {
                    calls++
                    ok()
                }
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.RATE_LIMITED)), result)
            assertEquals(0, calls)
            assertEquals(1, logins, "no automatic retry against the login bucket")
            loginResult = null
            assertIs<AuthResult.Ok<String>>(holder.withSession { ok() })
            assertEquals(2, logins, "only the next explicit call tries again")
        }

    @Test
    fun anUnauthorizedAnswerFromTheLoginIsNotRetriedAndKeepsTheRestOut() =
        runTest {
            loginResult = { AuthResult.Failed(AuthError.Api(ApiError.UNAUTHORIZED)) }
            val result = holder().withSession { ok() }
            assertEquals(AuthResult.Failed(AuthError.Api(ApiError.UNAUTHORIZED)), result)
            assertEquals(1, logins)
        }

    @Test
    fun clearForgetsTheSessionAndAWipedDeviceCannotLogIn() =
        runTest {
            val holder = holder()
            holder.withSession { ok() }
            holder.clear()
            loginResult = { AuthResult.Failed(AuthError.Local.NOT_REGISTERED) }
            val after = holder.withSession { ok() }
            assertEquals(AuthResult.Failed(AuthError.Local.NOT_REGISTERED), after)
            assertEquals(2, logins)
        }

    @Test
    fun aLoginFinishingAfterClearIsNotKept() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            loginResult = {
                gate.await()
                AuthResult.Ok(session())
            }
            val holder = holder()
            val call = async { holder.withSession { ok() } }
            repeat(5) { yield() }
            holder.clear()
            gate.complete(Unit)
            call.await()
            loginResult = null
            holder.withSession { ok() }
            assertEquals(2, logins, "the old login's session was dropped by the clear")
        }

    @Test
    fun theSessionNeverPrintsItsToken() =
        runTest {
            val s = session()
            assertEquals(false, "t0" in s.toString())
        }
}
