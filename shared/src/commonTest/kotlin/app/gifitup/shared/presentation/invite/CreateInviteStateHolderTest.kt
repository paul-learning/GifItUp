package app.gifitup.shared.presentation.invite

import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import app.gifitup.shared.data.network.InviteState
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.invite.CreateInvite
import app.gifitup.shared.domain.invite.ListInvites
import app.gifitup.shared.domain.invite.RevokeInvite
import app.gifitup.shared.domain.session.SessionHolder
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateInviteStateHolderTest {
    private val server = FakeAuthServer()
    private val api = server.api()
    private val code = "AAAAAAAAAAAAAAAAAAAAAQ"
    private val link = "${FakeAuthServer.ORIGIN}/invite#$code"
    private val id = "11111111-2222-4333-8444-555555555555"
    private val clipboard = FakeClipboard()

    private class FakeClipboard : SensitiveClipboard {
        val copies = mutableListOf<String>()
        var clears = 0
        var accepts = true
        var throws = false

        override suspend fun copy(text: String): Boolean {
            if (throws) error("the platform failed")
            if (accepts) copies += text
            return accepts
        }

        override fun clearCopied() {
            clears++
        }
    }

    private fun TestScope.holder() =
        CreateInviteStateHolder(
            scope = this,
            sessions =
                SessionHolder(
                    logIn = { AuthResult.Ok(Session("d", "t", NOW + 86_400, FakeAuthServer.ENDPOINT)) },
                    scope = this,
                    nowEpochSeconds = { NOW },
                ),
            createInvite = CreateInvite(api),
            listInvites = ListInvites(api),
            revokeInvite = RevokeInvite(api),
            clipboard = clipboard,
        )

    private fun created(link: String = this.link) =
        json("""{"inviteId":"$id","link":"$link","expiresAt":1700000000}""", HttpStatusCode.Created)

    private fun listed(vararg status: String) =
        json(
            """{"invites":[${status.joinToString(
                ",",
            ) { """{"inviteId":"$id","createdAt":1,"expiresAt":2,"status":"$it","usedBy":null}""" }}]}""",
        )

    /** The requests run on the HTTP client's own threads, so wait (in real time) until the action is done. */
    private suspend fun TestScope.settle(
        holder: CreateInviteStateHolder,
        action: () -> Unit,
    ) {
        action()
        advanceUntilIdle()
        withContext(Dispatchers.Default) { withTimeout(REAL_TIMEOUT_MS) { holder.state.first { !it.busy } } }
    }

    @Test
    fun theFirstRefreshListsTheInvites() =
        runTest {
            server.on("/invites", listed("open", "used"))
            val holder = holder()
            settle(holder, holder::refresh)
            assertEquals(
                listOf(InviteState.OPEN, InviteState.USED),
                holder.state.value.invites
                    ?.map { it.status },
            )
            assertNull(holder.state.value.problem)
        }

    @Test
    fun aCreatedInviteShowsTheClientBuiltLinkAndTheListFollows() =
        runTest {
            server.on("/invites", created(), listed("open"))
            val holder = holder()
            settle(holder, holder::create)
            val shown = assertNotNull(holder.state.value.shown)
            assertEquals(link, shown.link)
            assertEquals(
                1,
                holder.state.value.invites
                    ?.size,
            )
            assertFalse(code in holder.state.value.toString(), "the link never prints")
            assertFalse(code in shown.toString())
            assertTrue(clipboard.copies.isEmpty(), "nothing is copied unless the user asks")
        }

    @Test
    fun copyingPutsTheLinkOnTheClipboardAndLeavingTakesItOff() =
        runTest {
            server.on("/invites", created(), listed("open"))
            val holder = holder()
            settle(holder, holder::create)
            holder.copy()
            advanceUntilIdle()
            assertEquals(listOf(link), clipboard.copies)
            assertTrue(holder.state.value.copied)

            holder.leave()
            assertNull(holder.state.value.shown)
            assertFalse(holder.state.value.copied)
            assertEquals(1, clipboard.clears)
        }

    @Test
    fun leavingWithoutCopyingLeavesTheClipboardAlone() =
        runTest {
            server.on("/invites", created(), listed("open"))
            val holder = holder()
            settle(holder, holder::create)
            holder.leave()
            assertNull(holder.state.value.shown)
            assertEquals(0, clipboard.clears)
        }

    @Test
    fun aRefusedCopyIsAProblemAndNotCopied() =
        runTest {
            server.on("/invites", created(), listed("open"))
            val holder = holder()
            settle(holder, holder::create)
            clipboard.accepts = false
            holder.copy()
            advanceUntilIdle()
            assertFalse(holder.state.value.copied)
            assertEquals(InviteScreenProblem.COPY_FAILED, holder.state.value.problem)
            holder.leave()
            assertEquals(0, clipboard.clears, "nothing of ours is on the clipboard")
        }

    @Test
    fun aSecondInviteDropsTheFirstLinkAndItsClipboardEntry() =
        runTest {
            val other = "${FakeAuthServer.ORIGIN}/invite#AAAAAAAAAAAAAAAAAAAAAA"
            server.on("/invites", created(), listed("open"), created(other), listed("open", "open"))
            val holder = holder()
            settle(holder, holder::create)
            holder.copy()
            advanceUntilIdle()
            settle(holder, holder::create)
            assertEquals(
                other,
                holder.state.value.shown
                    ?.link,
            )
            assertEquals(1, clipboard.clears)
            assertFalse(holder.state.value.copied)
        }

    @Test
    fun theOpenLimitAndTheDailyLimitAreTheirOwnProblems() =
        runTest {
            val holder = holder()
            server.on("/invites", error(HttpStatusCode.Conflict, "invite_limit"), listed("open"))
            settle(holder, holder::create)
            assertEquals(InviteScreenProblem.OPEN_LIMIT, holder.state.value.problem)
            assertNull(holder.state.value.shown)
            assertEquals(
                1,
                holder.state.value.invites
                    ?.size,
                "the list still loads",
            )

            server.on("/invites", error(HttpStatusCode.TooManyRequests, "rate_limited"), listed("open"))
            settle(holder, holder::create)
            assertEquals(InviteScreenProblem.RATE_LIMITED, holder.state.value.problem)
        }

    @Test
    fun aLostAnswerIsOfflineAndTheListShowsWhatMayHaveBeenCreated() =
        runTest {
            server.on("/invites", FakeAuthServer.dropped, listed("open"))
            val holder = holder()
            settle(holder, holder::create)
            assertEquals(InviteScreenProblem.OFFLINE, holder.state.value.problem)
            assertNull(holder.state.value.shown)
            assertEquals(
                1,
                holder.state.value.invites
                    ?.size,
            )
            assertEquals(1, server.requests.count { it.data.method == HttpMethod.Post }, "create is never repeated on its own")
        }

    @Test
    fun aLinkForAnotherServerIsNeverShownAndTheInviteIsRevoked() =
        runTest {
            server.on("/invites", created("https://look-alike.example/invite#$code"), listed("revoked"))
            server.on("/invites/$id", { _ -> respond("", HttpStatusCode.NoContent) })
            val holder = holder()
            settle(holder, holder::create)
            assertNull(holder.state.value.shown)
            assertEquals(InviteScreenProblem.FAILED, holder.state.value.problem)
            assertTrue(server.requests.any { it.data.method == HttpMethod.Delete && it.path == "/invites/$id" })
        }

    @Test
    fun revokingTheShownInviteDropsItsLinkAndRefreshes() =
        runTest {
            server.on("/invites", created(), listed("open"), listed("revoked"))
            server.on("/invites/$id", { _ -> respond("", HttpStatusCode.NoContent) })
            val holder = holder()
            settle(holder, holder::create)
            holder.copy()
            advanceUntilIdle()
            settle(holder) { holder.revoke(id) }
            assertNull(holder.state.value.shown)
            assertEquals(1, clipboard.clears)
            assertEquals(
                InviteState.REVOKED,
                holder.state.value.invites
                    ?.single()
                    ?.status,
            )
            assertNull(holder.state.value.problem)
        }

    @Test
    fun anInviteThatIsGoneAlreadyIsNotAProblem() =
        runTest {
            server.on("/invites", listed("used"))
            server.on("/invites/$id", error(HttpStatusCode.NotFound, "not_found"))
            val holder = holder()
            settle(holder) { holder.revoke(id) }
            assertNull(holder.state.value.problem)
            assertEquals(
                InviteState.USED,
                holder.state.value.invites
                    ?.single()
                    ?.status,
            )
        }

    @Test
    fun aFailedRevokeKeepsTheShownLinkAndSaysSo() =
        runTest {
            server.on("/invites", created(), listed("open"), listed("open"))
            server.on("/invites/$id", error(HttpStatusCode.InternalServerError, "server_error"))
            val holder = holder()
            settle(holder, holder::create)
            settle(holder) { holder.revoke(id) }
            assertEquals(
                link,
                holder.state.value.shown
                    ?.link,
                "the invite is still open, so its link stays",
            )
            assertEquals(InviteScreenProblem.FAILED, holder.state.value.problem)
        }

    @Test
    fun aFailedRefreshIsAProblemAndKeepsTheOldList() =
        runTest {
            server.on("/invites", listed("open"))
            val holder = holder()
            settle(holder, holder::refresh)
            server.on("/invites", FakeAuthServer.dropped)
            settle(holder, holder::refresh)
            assertEquals(InviteScreenProblem.OFFLINE, holder.state.value.problem)
            assertEquals(
                1,
                holder.state.value.invites
                    ?.size,
            )
        }

    @Test
    fun aClipboardThatThrowsIsACopyProblemNotACrash() =
        runTest {
            server.on("/invites", created(), listed("open"))
            val holder = holder()
            settle(holder, holder::create)
            clipboard.throws = true
            holder.copy()
            advanceUntilIdle()
            assertEquals(InviteScreenProblem.COPY_FAILED, holder.state.value.problem)
            assertFalse(holder.state.value.copied)
        }

    @Test
    fun anAnswerThatArrivesAfterTheScreenWasLeftIsDropped() =
        runTest {
            server.on("/invites", created(), listed("open"))
            val holder = holder()
            holder.create()
            holder.leave()
            advanceUntilIdle()
            withContext(Dispatchers.Default) { withTimeout(REAL_TIMEOUT_MS) { holder.state.first { !it.busy } } }
            assertNull(holder.state.value.shown, "nobody is looking at the link")
            assertEquals(
                1,
                holder.state.value.invites
                    ?.size,
                "but the invite shows in the list, open",
            )
        }

    @Test
    fun onlyOneActionRunsAtATime() =
        runTest {
            server.on("/invites", created(), listed("open"))
            val holder = holder()
            holder.create()
            holder.create()
            holder.refresh()
            advanceUntilIdle()
            withContext(Dispatchers.Default) { withTimeout(REAL_TIMEOUT_MS) { holder.state.first { !it.busy } } }
            assertEquals(1, server.requests.count { it.data.method == HttpMethod.Post })
        }

    private companion object {
        const val NOW = 1_000L
        const val REAL_TIMEOUT_MS = 10_000L
    }
}
