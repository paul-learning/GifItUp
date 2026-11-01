package app.gifitup.shared.presentation.gif

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.gif.GifFailure
import app.gifitup.shared.domain.gif.GifProvider
import app.gifitup.shared.domain.gif.GifResult
import app.gifitup.shared.domain.gif.GifTile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GifPickerStateHolderTest {
    private val hex = "a".repeat(32)

    private fun item(slug: String): GifTile {
        val ref = checkNotNull(GifReference.of("klipy", slug, "https://static.klipy.com/ii/$hex/ab/cd/$slug.webp", 10, 10))
        return GifTile.Item(ref, ref)
    }

    private class Fake : GifProvider {
        val calls = mutableListOf<String>()
        var next: suspend (String, Int) -> GifResult = { _, _ -> GifResult.Page(emptyList(), false) }

        override suspend fun trending(page: Int): GifResult {
            calls += "trending $page"
            return next("", page)
        }

        override suspend fun search(
            query: String,
            page: Int,
        ): GifResult {
            calls += "search $query $page"
            return next(query, page)
        }
    }

    private val provider = Fake()

    private fun TestScope.holder() = GifPickerStateHolder(this, provider)

    @Test
    fun openLoadsTrendingOnceAndShowsTheTilesInOrder() =
        runTest {
            provider.next = { _, _ -> GifResult.Page(listOf(item("one"), GifTile.Placeholder, item("two")), hasNext = true) }
            val picker = holder()
            picker.open()
            picker.open()
            advanceUntilIdle()
            assertEquals(listOf("trending 1"), provider.calls)
            val state = picker.state.value
            assertEquals(3, state.tiles.size)
            assertTrue(state.tiles[1] === GifTile.Placeholder)
            assertTrue(state.hasNext && state.loaded && !state.loading)
        }

    @Test
    fun typingWaitsForAPauseAndOnlyTheLastTextIsSearched() =
        runTest {
            val picker = holder()
            picker.onQuery("c")
            advanceTimeBy(200)
            picker.onQuery("ca")
            advanceTimeBy(200)
            picker.onQuery("cat")
            advanceTimeBy(GifPickerStateHolder.DEBOUNCE_MILLIS - 1)
            runCurrent()
            assertTrue(provider.calls.isEmpty(), "no call before the pause: ${provider.calls}")
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf("search cat 1"), provider.calls)
        }

    @Test
    fun clearingTheFieldGoesToTrendingAtOnceAndANewSearchDropsTheOldAnswer() =
        runTest {
            val gate = CompletableDeferred<GifResult>()
            provider.next = { query, _ -> if (query == "slow") gate.await() else GifResult.Page(listOf(item("fresh")), false) }
            val picker = holder()
            picker.onQuery("slow")
            advanceTimeBy(GifPickerStateHolder.DEBOUNCE_MILLIS + 1)
            runCurrent()
            picker.onQuery("")
            runCurrent()
            gate.complete(GifResult.Page(listOf(item("stale")), false))
            advanceUntilIdle()
            assertEquals(listOf("search slow 1", "trending 1"), provider.calls)
            assertEquals(1, picker.state.value.tiles.size)
            assertEquals("", picker.state.value.query)
        }

    @Test
    fun loadMoreAppendsThePageAndOnlyWhenThereIsOne() =
        runTest {
            provider.next = { _, page -> GifResult.Page(listOf(item("p$page")), hasNext = page < 2) }
            val picker = holder()
            picker.open()
            advanceUntilIdle()
            picker.loadMore()
            advanceUntilIdle()
            picker.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("trending 1", "trending 2"), provider.calls)
            assertEquals(2, picker.state.value.tiles.size)
            assertTrue(!picker.state.value.hasNext)
        }

    @Test
    fun aFailureKeepsTheTilesNamesTheReasonAndNothingRetriesByItself() =
        runTest {
            provider.next =
                { _, page -> if (page == 1) GifResult.Page(listOf(item("one")), true) else GifResult.Failed(GifFailure.RATE_LIMITED, 30) }
            val picker = holder()
            picker.open()
            advanceUntilIdle()
            picker.loadMore()
            advanceUntilIdle()
            val failed = picker.state.value
            assertEquals(GifFailure.RATE_LIMITED, failed.failure)
            assertEquals(30, failed.retryAfterSeconds)
            assertEquals(1, failed.tiles.size)
            advanceTimeBy(10 * 60 * 1000)
            assertEquals(2, provider.calls.size, "no automatic retry")
            picker.loadMore()
            advanceUntilIdle()
            assertEquals(2, provider.calls.size, "loadMore does not push through a failure")
            provider.next = { _, page -> GifResult.Page(listOf(item("p$page")), false) }
            picker.retry()
            advanceUntilIdle()
            assertNull(picker.state.value.failure)
            assertEquals(2, picker.state.value.tiles.size)
        }

    @Test
    fun aFirstPageFailureShowsNoTilesAndRetryAsksAgain() =
        runTest {
            provider.next = { _, _ -> GifResult.Failed(GifFailure.UNAVAILABLE) }
            val picker = holder()
            picker.open()
            advanceUntilIdle()
            assertEquals(GifFailure.UNAVAILABLE, picker.state.value.failure)
            assertTrue(
                picker.state.value.tiles
                    .isEmpty() && picker.state.value.loaded,
            )
            provider.next = { _, _ -> GifResult.Page(listOf(item("ok")), false) }
            picker.retry()
            advanceUntilIdle()
            assertEquals(1, picker.state.value.tiles.size)
            assertEquals(listOf("trending 1", "trending 1"), provider.calls)
        }

    @Test
    fun closingStopsTheCallAndForgetsEverything() =
        runTest {
            val gate = CompletableDeferred<GifResult>()
            provider.next = { _, _ -> gate.await() }
            val picker = holder()
            picker.open()
            runCurrent()
            assertTrue(picker.state.value.loading)
            picker.close()
            gate.complete(GifResult.Page(listOf(item("late")), false))
            advanceUntilIdle()
            assertEquals(GifPickerState(), picker.state.value)
        }

    @Test
    fun aLongQueryIsCutAtTheLimit() =
        runTest {
            val picker = holder()
            picker.onQuery("x".repeat(500))
            assertEquals(GifPickerStateHolder.MAX_QUERY, picker.state.value.query.length)
        }
}
