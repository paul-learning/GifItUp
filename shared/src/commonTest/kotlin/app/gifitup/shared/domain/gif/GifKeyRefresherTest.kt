package app.gifitup.shared.domain.gif

import app.gifitup.shared.data.network.ApiError
import app.gifitup.shared.data.network.GifProviderKey
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class GifKeyRefresherTest {
    private val holder = GifKeyHolder()
    private var calls = 0
    private var outcome: () -> AuthResult<GifProviderKey> = {
        AuthResult.Ok(
            checkNotNull(GifProviderKey.of("KlipyTestKey_0123456789abcdef")),
        )
    }
    private val refresher = GifKeyRefresher(holder, { calls++.let { outcome() } })

    private val down = { AuthResult.Failed(AuthError.Api(ApiError.NETWORK)) }

    @Test
    fun theKeyIsFetchedAtOnceAndThenOnlyEverySixHours() =
        runTest {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { refresher.run() }
            runCurrent()
            assertEquals(1, calls)
            assertEquals("KlipyTestKey_0123456789abcdef", holder.current()?.reveal())
            advanceTimeBy(GifKeyRefresher.SIX_HOURS_MILLIS - 1)
            assertEquals(1, calls)
            advanceTimeBy(2)
            assertEquals(2, calls)
            job.cancel()
        }

    @Test
    fun aFailedFetchIsRetriedWithGrowingWaitsUpToTheCap() =
        runTest {
            outcome = down
            val job = launch(start = CoroutineStart.UNDISPATCHED) { refresher.run() }
            runCurrent()
            assertEquals(1, calls)
            assertNull(holder.current())
            advanceTimeBy(GifKeyRefresher.FIVE_MINUTES_MILLIS + 1)
            assertEquals(2, calls)
            advanceTimeBy(2 * GifKeyRefresher.FIVE_MINUTES_MILLIS + 1)
            assertEquals(3, calls)
            advanceTimeBy(4 * GifKeyRefresher.FIVE_MINUTES_MILLIS + 1)
            assertEquals(4, calls)
            advanceTimeBy(GifKeyRefresher.ONE_HOUR_MILLIS + 1)
            assertEquals(5, calls)
            advanceTimeBy(GifKeyRefresher.ONE_HOUR_MILLIS + 1)
            assertEquals(6, calls, "the wait stays at the cap")
            outcome = { AuthResult.Ok(checkNotNull(GifProviderKey.of("KlipyTestKey_0123456789abcdef"))) }
            advanceTimeBy(GifKeyRefresher.ONE_HOUR_MILLIS + 1)
            assertEquals(7, calls)
            assertEquals("KlipyTestKey_0123456789abcdef", holder.current()?.reveal())
            job.cancel()
        }

    @Test
    fun aThrowingFetchIsAFailedFetch() =
        runTest {
            outcome = { error("boom") }
            val job = launch(start = CoroutineStart.UNDISPATCHED) { refresher.run() }
            runCurrent()
            assertNull(holder.current())
            job.cancel()
        }

    @Test
    fun clearingTheHolderDropsTheKeyAndBumpsTheEpoch() {
        holder.set(checkNotNull(GifProviderKey.of("KlipyTestKey_0123456789abcdef")))
        val epoch = holder.epoch()
        holder.clear()
        assertNull(holder.current())
        assertEquals(epoch + 1, holder.epoch())
    }
}
