package app.gifitup.shared.presentation.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.media.FetchResult
import app.gifitup.shared.domain.media.LoadGifMedia
import app.gifitup.shared.domain.media.MediaFetcher
import app.gifitup.shared.domain.media.UnavailableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The bubbles' media: every device loads on its own, one download per bubble, nothing kept after a bubble is gone. */
class GifTilesTest {
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"
    private val reference = assertNotNull(GifReference.of("klipy", "funny-cat", url, 16, 8))
    private val key = KeyBytes(byteArrayOf(1))
    private val other = KeyBytes(byteArrayOf(2))
    private val gif =
        "GIF89a".encodeToByteArray() + byteArrayOf(16, 0, 8, 0, 0x80.toByte(), 0, 0) + ByteArray(6) +
            byteArrayOf(0x2C, 0, 0, 0, 0, 16, 0, 8, 0, 0, 2, 3, 1, 2, 3, 0, 0x3B)

    private var fetches = 0
    private var answer: suspend () -> FetchResult = { FetchResult.Bytes(gif) }
    private val fetcher =
        MediaFetcher {
            fetches++
            answer()
        }

    private fun TestScope.tiles(parallel: Int = 3): GifTilesStateHolder = GifTilesStateHolder(this, LoadGifMedia(fetcher), parallel)

    @Test
    fun aBubbleLoadsOnItsOwn() =
        runTest {
            val holder = tiles()
            holder.show(key, reference)
            assertIs<GifTile.Loading>(holder.tiles.value[key])
            advanceUntilIdle()
            assertEquals(16, assertIs<GifTile.Ready>(holder.tiles.value[key]).info.width)
            holder.show(key, reference) // composing again does not fetch again
            advanceUntilIdle()
            assertEquals(1, fetches)
        }

    @Test
    fun aFailedBubbleIsAPlaceholderUntilTappedAgain() =
        runTest {
            val holder = tiles()
            answer = { FetchResult.Failed }
            holder.show(key, reference)
            advanceUntilIdle()
            assertEquals(UnavailableReason.FETCH_FAILED, assertIs<GifTile.Unavailable>(holder.tiles.value[key]).reason)
            holder.show(key, reference) // appearing again on its own does not retry
            advanceUntilIdle()
            assertEquals(1, fetches)
            answer = { FetchResult.Bytes(gif) }
            holder.tap(key, reference)
            advanceUntilIdle()
            assertIs<GifTile.Ready>(holder.tiles.value[key])
        }

    @Test
    fun aTileThatIsNotWaitingIgnoresATap() =
        runTest {
            val holder = tiles()
            holder.show(key, reference)
            advanceUntilIdle()
            holder.tap(key, reference)
            advanceUntilIdle()
            assertEquals(1, fetches)
        }

    @Test
    fun aBubbleThatLeavesDropsItsBytesAndAnAnswerStillOnTheWay() =
        runTest {
            val gate = CompletableDeferred<FetchResult>()
            answer = { gate.await() }
            val holder = tiles()
            holder.show(key, reference)
            advanceUntilIdle()
            holder.hide(key)
            gate.complete(FetchResult.Bytes(gif))
            advanceUntilIdle()
            assertTrue(holder.tiles.value.isEmpty())
            answer = { FetchResult.Bytes(gif) }
            holder.show(key, reference)
            advanceUntilIdle()
            holder.hide(key)
            assertTrue(holder.tiles.value.isEmpty())
        }

    @Test
    fun anAnswerThatCannotBeCancelledStillDoesNotComeBackToABubbleThatLeft() =
        runTest {
            val gate = CompletableDeferred<FetchResult>()
            answer = { withContext(NonCancellable) { gate.await() } }
            val holder = tiles()
            holder.show(key, reference)
            advanceUntilIdle()
            holder.hide(key)
            gate.complete(FetchResult.Bytes(gif))
            advanceUntilIdle()
            assertTrue(holder.tiles.value.isEmpty())
        }

    @Test
    fun aDispatcherThatRunsTheLoadAtOnceStillDeliversIt() =
        runTest {
            val holder =
                GifTilesStateHolder(
                    CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    LoadGifMedia(fetcher),
                )
            holder.show(key, reference)
            assertIs<GifTile.Ready>(holder.tiles.value[key])
        }

    @Test
    fun leavingTheScreenDropsEverything() =
        runTest {
            val holder = tiles()
            holder.show(key, reference)
            holder.show(other, reference)
            advanceUntilIdle()
            holder.leave()
            assertTrue(holder.tiles.value.isEmpty())
        }

    @Test
    fun onlyAFewDownloadsRunAtOnce() =
        runTest {
            val gate = CompletableDeferred<FetchResult>()
            answer = { gate.await() }
            val holder = tiles(parallel = 2)
            (1..5).forEach { holder.show(KeyBytes(byteArrayOf(it.toByte())), reference) }
            advanceUntilIdle()
            assertEquals(2, fetches)
            gate.complete(FetchResult.Bytes(gif))
            advanceUntilIdle()
            assertEquals(5, fetches)
        }
}
