package app.gifitup.shared.presentation.onboarding

import app.gifitup.protocol.ProtocolJson
import app.gifitup.protocol.RegisterRequest
import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.network.FakeAuthServer.Companion.dropped
import app.gifitup.shared.data.network.FakeAuthServer.Companion.error
import app.gifitup.shared.data.network.FakeAuthServer.Companion.json
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.StoredIdentity
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.CountingRandom
import app.gifitup.shared.domain.auth.DeviceLock
import app.gifitup.shared.domain.auth.InMemoryIdentityStore
import app.gifitup.shared.domain.auth.InMemoryKeyStore
import app.gifitup.shared.domain.auth.LogIn
import app.gifitup.shared.domain.auth.RegisterDevice
import app.gifitup.shared.domain.auth.unguarded
import app.gifitup.shared.domain.keypackages.RefillKeyPackages
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.domain.session.SessionHolder
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingStateHolderTest {
    private val server = FakeAuthServer()
    private val identities = InMemoryIdentityStore()
    private val keys = InMemoryKeyStore()
    private val random = CountingRandom()
    private val lock = DeviceLock()
    private val code = "AAAAAAAAAAAAAAAAAAAAAQ"

    private fun TestScope.holder(failingIdentities: Boolean = false): OnboardingStateHolder {
        val api = server.api()
        val store = if (failingIdentities) damagedIdentities() else identities
        return OnboardingStateHolder(
            scope = this,
            origin = ServerOriginSetting(InMemoryLocalStore(), store, lock, FakeAuthServer.ORIGIN),
            identities = store,
            register = RegisterDevice(api, unguarded(), identities, keys, random, lock) {},
            refill = RefillKeyPackages(api, unguarded(), identities, keys, lock),
            sessions = SessionHolder({ LogIn(api, unguarded(), identities, keys, lock)() }, this) { 0L },
        )
    }

    private fun damagedIdentities() =
        object : app.gifitup.shared.data.storage.IdentityStore {
            override suspend fun load(): StoredIdentity = throw LocalStateException(LocalStateException.Problem.DAMAGED)

            override suspend fun save(identity: StoredIdentity) = Unit

            override suspend fun clear() = Unit
        }

    private suspend fun TestScope.settle(action: () -> Unit) {
        action()
        advanceUntilIdle()
    }

    /** The registration runs on the HTTP client's own threads, so wait (in real time) until the flow leaves `Registering`. */
    private suspend fun TestScope.settleRegistration(
        holder: OnboardingStateHolder,
        action: () -> Unit,
    ) {
        settle(action)
        withContext(Dispatchers.Default) {
            withTimeout(REAL_TIMEOUT_MS) { holder.state.first { it !is OnboardingState.Registering } }
        }
    }

    private fun queueKeyPackages() {
        server.on("/key-packages/status", json("""{"available":0,"lowWaterMark":20}"""))
        server.on("/key-packages", json("""{"accepted":50,"available":50,"lowWaterMark":20}"""))
    }

    private fun registerRequests() = server.bodiesFor("/accounts").map { ProtocolJson.decodeFromString(RegisterRequest.serializer(), it) }

    /** Drives the flow to the server confirmation for [name]. */
    private suspend fun TestScope.toConfirm(
        holder: OnboardingStateHolder,
        name: String = "ann",
    ) {
        settle(holder::start)
        settle { holder.submitInvite("${FakeAuthServer.ORIGIN}/invite#$code") }
        settle { holder.changeUsername(name) }
        settle(holder::submitUsername)
    }

    @Test
    fun anEmptyDeviceStartsAtTheInviteWithTheConfiguredOrigin() =
        runTest {
            val holder = holder()
            settle(holder::start)
            val state = assertIs<OnboardingState.EnterInvite>(holder.state.value)
            assertEquals(FakeAuthServer.ORIGIN, state.origin.origin)
            assertFalse(state.origin.custom)
            assertNull(state.problem)
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun anInviteForAnotherServerOrJunkIsRefusedWithoutARequest() =
        runTest {
            val holder = holder()
            settle(holder::start)
            settle { holder.submitInvite("https://look-alike.example/invite#$code") }
            val other = assertIs<OnboardingState.EnterInvite>(holder.state.value)
            assertEquals(InviteProblem.OTHER_SERVER, other.problem)
            assertEquals("https://look-alike.example", other.otherOrigin)
            settle { holder.submitInvite("hello") }
            assertEquals(InviteProblem.NOT_AN_INVITE, assertIs<OnboardingState.EnterInvite>(holder.state.value).problem)
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun nothingIsSentAndNoKeyMadeUntilTheServerIsConfirmed() =
        runTest {
            val holder = holder()
            toConfirm(holder)
            val confirm = assertIs<OnboardingState.ConfirmServer>(holder.state.value)
            assertEquals(FakeAuthServer.ORIGIN, confirm.origin.origin)
            assertEquals("ann", confirm.username)
            assertEquals(emptyList(), server.requests, "typing and confirming-to-be asks nobody")
            assertEquals(emptyList(), random.sizes, "no key yet")
            assertNull(identities.identity)
        }

    @Test
    fun anInvalidUsernameStaysOnTheStepAndAsksNobody() =
        runTest {
            val holder = holder()
            settle(holder::start)
            settle { holder.submitInvite(code) }
            settle { holder.changeUsername("Ann!") }
            settle(holder::submitUsername)
            assertEquals(UsernameProblem.INVALID, assertIs<OnboardingState.ChooseUsername>(holder.state.value).problem)
            assertEquals(emptyList(), server.requests)
        }

    @Test
    fun confirmingRegistersUploadsKeyPackagesAndDropsTheCode() =
        runTest {
            queueKeyPackages()
            val holder = holder()
            toConfirm(holder)
            settleRegistration(holder, holder::confirmRegistration)

            val done = assertIs<OnboardingState.Done>(holder.state.value)
            assertEquals("ann", done.username)
            assertTrue(done.keyPackagesUploaded)
            assertEquals(listOf("/auth/challenge", "/accounts", "/key-packages/status", "/key-packages"), server.requests.map { it.path })
            assertEquals(code, registerRequests().single().inviteCode)
            settle(holder::confirmRegistration)
            assertEquals(1, registerRequests().size, "a second call from Done does nothing")
        }

    @Test
    fun aFailedKeyPackageUploadStillEndsRegisteredAndSaysSo() =
        runTest {
            server.on("/key-packages/status", error(HttpStatusCode.InternalServerError, "internal"))
            val holder = holder()
            toConfirm(holder)
            settleRegistration(holder, holder::confirmRegistration)
            assertFalse(assertIs<OnboardingState.Done>(holder.state.value).keyPackagesUploaded)
        }

    @Test
    fun confirmRegistrationOnlyActsFromTheConfirmation() =
        runTest {
            val holder = holder()
            settle(holder::start)
            settle(holder::confirmRegistration)
            settle { holder.submitInvite(code) }
            settle(holder::confirmRegistration)
            assertEquals(emptyList(), server.requests)
            assertIs<OnboardingState.ChooseUsername>(holder.state.value)
        }

    @Test
    fun aTakenUsernameKeepsTheInviteForAnotherName() =
        runTest {
            server.on("/accounts", error(HttpStatusCode.Conflict, "username_unavailable"))
            queueKeyPackages()
            val holder = holder()
            toConfirm(holder)
            settleRegistration(holder, holder::confirmRegistration)
            val taken = assertIs<OnboardingState.ChooseUsername>(holder.state.value)
            assertEquals(UsernameProblem.TAKEN, taken.problem)
            assertEquals("ann", taken.username)
            assertNull(identities.identity, "the refused first attempt left nothing behind")

            settle { holder.changeUsername("bea") }
            settle(holder::submitUsername)
            settleRegistration(holder, holder::confirmRegistration)
            assertEquals("bea", assertIs<OnboardingState.Done>(holder.state.value).username)
            assertEquals(listOf(code, code), registerRequests().map { it.inviteCode })
        }

    @Test
    fun aRefusedInviteSendsTheUserBackToTheInvite() =
        runTest {
            server.on("/accounts", error(HttpStatusCode.Forbidden, "invite_invalid"))
            val holder = holder()
            toConfirm(holder)
            settleRegistration(holder, holder::confirmRegistration)
            assertEquals(InviteProblem.REFUSED, assertIs<OnboardingState.EnterInvite>(holder.state.value).problem)
            settle(holder::confirmRegistration)
            assertEquals(1, registerRequests().size, "the code is gone: nothing can be sent again")
        }

    @Test
    fun aLostReplyLeavesAPendingRegistrationThatIsFinishedWithoutTheCode() =
        runTest {
            server.on("/accounts", dropped)
            queueKeyPackages()
            val holder = holder()
            toConfirm(holder)
            settleRegistration(holder, holder::confirmRegistration)
            val pending = assertIs<OnboardingState.FinishPending>(holder.state.value)
            assertEquals("ann", pending.username)
            assertEquals(AuthError.Api(ApiError.NETWORK), pending.lastError)

            settleRegistration(holder, holder::finishPending)
            assertEquals("ann", assertIs<OnboardingState.Done>(holder.state.value).username)
            assertEquals(listOf(code, RegisterDevice.RESUME_PLACEHOLDER), registerRequests().map { it.inviteCode })
        }

    @Test
    fun discardingNeedsTheConfirmation() =
        runTest {
            server.on("/accounts", dropped)
            val holder = holder()
            toConfirm(holder)
            settleRegistration(holder, holder::confirmRegistration)
            settle { holder.discardPending(confirmed = false) }
            assertIs<OnboardingState.FinishPending>(holder.state.value)
            assertTrue(identities.identity != null)

            settle { holder.discardPending(confirmed = true) }
            assertIs<OnboardingState.EnterInvite>(holder.state.value)
            assertNull(identities.identity)
            assertNull(keys.key)
        }

    @Test
    fun theFirstScreenFollowsWhatTheDeviceAlreadyHolds() =
        runTest {
            identities.identity = StoredIdentity(FakeAuthServer.ORIGIN, "ann", byteArrayOf(1), deviceId = null)
            val pending = holder()
            settle(pending::start)
            assertEquals("ann", assertIs<OnboardingState.FinishPending>(pending.state.value).username)

            identities.identity = StoredIdentity(FakeAuthServer.ORIGIN, "ann", byteArrayOf(1), deviceId = "d")
            val registered = holder()
            settle(registered::start)
            assertEquals("ann", assertIs<OnboardingState.Done>(registered.state.value).username)

            val damaged = holder(failingIdentities = true)
            settle(damaged::start)
            assertEquals(BlockReason.STATE_INVALID, assertIs<OnboardingState.Blocked>(damaged.state.value).reason)
            assertEquals(emptyList(), server.requests)
        }

    private companion object {
        const val REAL_TIMEOUT_MS = 10_000L
    }
}
