package app.gifitup.shared.data.gif

import app.gifitup.shared.data.network.GifProviderKey
import app.gifitup.shared.domain.gif.GifFailure
import app.gifitup.shared.domain.gif.GifKeyHolder
import app.gifitup.shared.domain.gif.GifResult
import app.gifitup.shared.domain.gif.GifTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [KlipyGifProvider] against a fake fetcher: the request it builds and what it makes of every kind of answer. */
class KlipyGifProviderTest {
    private val key = "KlipyTestKey_0123456789abcdef"
    private val keys = GifKeyHolder().also { it.set(checkNotNull(GifProviderKey.of(key))) }
    private var now = 1_000_000L
    private val urls = mutableListOf<String>()
    private var answer: () -> ProviderAnswer = { reply(page()) }
    private val fetcher =
        ProviderFetcher { url, _ ->
            urls += url
            answer()
        }
    private val provider = KlipyGifProvider(fetcher, keys) { now }

    private fun hex(c: Char) = c.toString().repeat(32)

    private fun media(
        slug: String,
        size: String,
        format: String = "webp",
        w: Int = 200,
        h: Int = 100,
    ) = """"$format":{"url":"https://static.klipy.com/ii/${hex('a')}/ab/cd/$slug-$size.$format","width":$w,"height":$h,"size":1}"""

    private fun item(
        slug: String,
        type: String? = "gif",
        sm: String = media(slug, "sm"),
        md: String = media(slug, "md"),
    ) = """{"id":1,"slug":"$slug",${type?.let { """"type":"$it",""" } ?: ""}"title":"T","file":{"sm":{$sm},"md":{$md}}}"""

    private fun page(
        vararg items: String,
        hasNext: Boolean = true,
    ) = """{"result":true,"data":{"data":[${items.joinToString(",")}],"current_page":1,"per_page":24,"has_next":$hasNext}}"""

    private fun reply(
        body: String,
        status: Int = 200,
        retryAfter: Int? = null,
    ): ProviderAnswer = ProviderAnswer.Reply(status, body.encodeToByteArray(), retryAfter)

    private fun failed(result: GifResult) = assertIs<GifResult.Failed>(result)

    private fun tiles(result: GifResult) = assertIs<GifResult.Page>(result).tiles

    @Test
    fun trendingBuildsOneFixedHostRequestWithTheKeyAsASegmentAndTheFilterLast() =
        runTest {
            provider.trending(1)
            assertEquals(
                "https://api.klipy.com/api/v1/$key/gifs/trending?page=1&per_page=24&format_filter=gif%2Cwebp&content_filter=medium",
                urls.single(),
            )
        }

    @Test
    fun searchEncodesTheQueryAndAnInjectionAttemptChangesNothing() =
        runTest {
            provider.search("a b&content_filter=off&customer_id=x#/../y?z", 2)
            val url = urls.single()
            assertTrue(url.startsWith("https://api.klipy.com/api/v1/$key/gifs/search?q="), url)
            assertEquals(1, Regex("content_filter=").findAll(url).count(), url)
            assertFalse("customer_id=x" in url.substringAfter("&"), url)
            assertFalse('#' in url, url)
            assertTrue(url.endsWith("&content_filter=medium"), url)
            assertEquals(1, Regex("q=").findAll(url).count(), url)
        }

    @Test
    fun aBlankQueryIsTrendingAndALongQueryIsCut() =
        runTest {
            provider.search("   ")
            now += 1000
            provider.search("x".repeat(500))
            assertTrue("/gifs/trending" in urls[0], urls[0])
            assertEquals(100, Regex("q=(x+)").find(urls[1])!!.groupValues[1].length)
        }

    @Test
    fun aPageOutsideTheRangeIsRefusedWithoutARequest() =
        runTest {
            assertEquals(GifFailure.MALFORMED, failed(provider.trending(0)).reason)
            assertEquals(GifFailure.MALFORMED, failed(provider.trending(KlipyGifProvider.MAX_PAGE + 1)).reason)
            assertTrue(urls.isEmpty())
        }

    @Test
    fun noKeyIsUnavailableWithoutARequest() =
        runTest {
            keys.clear()
            assertEquals(GifFailure.UNAVAILABLE, failed(provider.trending()).reason)
            assertTrue(urls.isEmpty())
        }

    @Test
    fun goodItemsKeepTheirOrderAndPreviewAndSendShareTheSlug() =
        runTest {
            answer = { reply(page(item("one"), item("two"), hasNext = false)) }
            val result = assertIs<GifResult.Page>(provider.trending())
            assertFalse(result.hasNext)
            val first = assertIs<GifTile.Item>(result.tiles[0])
            assertEquals("one", first.send.slug)
            assertEquals(first.send.slug, first.preview.slug)
            assertTrue(first.preview.url.endsWith("one-sm.webp"))
            assertTrue(first.send.url.endsWith("one-md.webp"))
            assertEquals("two", assertIs<GifTile.Item>(result.tiles[1]).send.slug)
        }

    @Test
    fun aGifRenditionIsTheFallbackWhenThereIsNoWebp() =
        runTest {
            answer = { reply(page(item("one", sm = media("one", "sm", "gif"), md = media("one", "md", "gif")))) }
            val tile = assertIs<GifTile.Item>(tiles(provider.trending()).single())
            assertTrue(tile.send.url.endsWith("one-md.gif"))
        }

    @Test
    fun adsAndUnknownTypesAndBrokenItemsArePlaceholdersInPlaceAndNothingIsDropped() =
        runTest {
            val bad =
                listOf(
                    item("ad1", type = "ad"),
                    item("ad2", type = "AD"),
                    item("new", type = "sticker"),
                    item("noslug").replace(""""slug":"noslug",""", ""),
                    item("x/y"),
                    item("bigsize", md = media("bigsize", "md", w = 5000)),
                    item("zerosize", sm = media("zerosize", "sm", h = 0)),
                    item("host", md = """"webp":{"url":"https://evil.example/a.webp","width":1,"height":1}"""),
                    item(
                        "query",
                        md = """"webp":{"url":"https://static.klipy.com/ii/${hex('a')}/ab/cd/q.webp?x=1","width":1,"height":1}""",
                    ),
                    item(
                        "strsize",
                        md = """"webp":{"url":"https://static.klipy.com/ii/${hex('a')}/ab/cd/q.webp","width":"1","height":1}""",
                    ),
                    "7",
                    "null",
                    """{"slug":"s","file":"nope"}""",
                )
            answer = { reply(page(item("first"), *bad.toTypedArray(), item("last"))) }
            val result = tiles(provider.trending())
            assertEquals(bad.size + 2, result.size)
            assertIs<GifTile.Item>(result.first())
            assertIs<GifTile.Item>(result.last())
            assertTrue(result.subList(1, result.size - 1).all { it === GifTile.Placeholder }, "$result")
        }

    @Test
    fun anItemWithoutATypeIsAGifAndNothingElseOfItIsRead() =
        runTest {
            answer =
                {
                    reply(
                        page(
                            item(
                                "plain",
                                type = null,
                            ).replace(""""title":"T",""", """"title":"x","url":"https://evil.example/","content":"y","""),
                        ),
                    )
                }
            val tile = assertIs<GifTile.Item>(tiles(provider.trending()).single())
            assertTrue("evil" !in tile.send.url && "evil" !in tile.preview.url)
        }

    @Test
    fun anyBrokenEnvelopeIsMalformed() =
        runTest {
            val bad =
                listOf(
                    "nope",
                    "[]",
                    "{}",
                    """{"data":[]}""",
                    """{"data":{"data":{}}}""",
                    """{"data":{"data":[],"has_next":"yes"}}""",
                    """{"data":{"data":[${List(51) { "{}" }.joinToString(",")}]}}""",
                    "[".repeat(40) + "]".repeat(40),
                    """{"a":""" + "[".repeat(50) + "]".repeat(50) + "}",
                )
            bad.forEach { body ->
                now += 1000
                answer = { reply(body) }
                assertEquals(GifFailure.MALFORMED, failed(provider.trending()).reason, body.take(30))
            }
        }

    @Test
    fun theStatusesAreMappedAndOnlyA429BlocksTheNextCalls() =
        runTest {
            answer = { reply("", status = 401) }
            assertEquals(GifFailure.UNAVAILABLE, failed(provider.trending()).reason)
            now += 1000
            answer = { reply("", status = 403) }
            assertEquals(GifFailure.UNAVAILABLE, failed(provider.trending()).reason)
            now += 1000
            answer = { reply("", status = 302) }
            assertEquals(GifFailure.UNAVAILABLE, failed(provider.trending()).reason)
            now += 1000
            answer = { reply("", status = 503) }
            assertEquals(GifFailure.UNAVAILABLE, failed(provider.trending()).reason)
            now += 1000
            answer = { ProviderAnswer.Failed }
            assertEquals(GifFailure.OFFLINE, failed(provider.trending()).reason)
            now += 1000
            answer = { ProviderAnswer.TooLarge }
            assertEquals(GifFailure.MALFORMED, failed(provider.trending()).reason)
        }

    @Test
    fun a429BlocksUntilRetryAfterWithoutAnyRequestAndThenGoesOn() =
        runTest {
            answer = { reply("", status = 429, retryAfter = 30) }
            val first = failed(provider.trending())
            assertEquals(GifFailure.RATE_LIMITED, first.reason)
            assertEquals(30, first.retryAfterSeconds)
            assertEquals(1, urls.size)
            now += 10_000
            val blocked = failed(provider.search("cats"))
            assertEquals(GifFailure.RATE_LIMITED, blocked.reason)
            assertEquals(20, blocked.retryAfterSeconds)
            assertEquals(1, urls.size, "no request while blocked")
            now += 20_000
            answer = { reply(page(item("one"))) }
            assertIs<GifResult.Page>(provider.search("cats"))
            assertEquals(2, urls.size)
        }

    @Test
    fun aMissingOrHugeRetryAfterIsBoundedAndCallsCloserThanTheMinimumIntervalAreRefused() =
        runTest {
            answer = { reply("", status = 429, retryAfter = null) }
            assertEquals(60, failed(provider.trending()).retryAfterSeconds)
            now += 61_000
            answer = { reply("", status = 429, retryAfter = 3600) }
            assertEquals(300, failed(provider.trending()).retryAfterSeconds)
            now += 301_000
            answer = { reply(page(item("one"))) }
            assertIs<GifResult.Page>(provider.trending())
            now += 100
            val tooSoon = failed(provider.trending())
            assertEquals(GifFailure.RATE_LIMITED, tooSoon.reason)
            assertEquals(3, urls.size, "the third call too soon made no request")
        }

    @Test
    fun aCallThatStartedBeforeLogoutBringsNothingBack() =
        runTest {
            answer = {
                keys.clear()
                reply(page(item("one")))
            }
            assertEquals(GifFailure.UNAVAILABLE, failed(provider.trending()).reason)
        }

    @Test
    fun aThrowingFetcherNeverCarriesTheKeyOut() =
        runTest {
            answer = { error("connect timeout url=https://api.klipy.com/api/v1/$key/gifs/trending") }
            val result = provider.trending()
            assertEquals(GifFailure.MALFORMED, failed(result).reason)
            assertFalse(key in result.toString())
            now += 1000
            answer = { throw AssertionError("platform error $key") }
            assertFalse(key in failed(provider.trending()).toString())
        }

    @Test
    fun aCancellationIsNotSwallowed() =
        runTest {
            answer = { throw CancellationException("stop") }
            assertFailsWith<CancellationException> { provider.trending() }
        }

    @Test
    fun aLongQueryIsNeverCutInsideASurrogatePair() =
        runTest {
            // 99 letters, then a 2-unit emoji straddling the 100-unit limit: the whole emoji goes.
            provider.search("a".repeat(99) + "\uD83D\uDE00")
            val encoded = Regex("q=([^&]*)").find(urls.single())!!.groupValues[1]
            assertEquals("a".repeat(99), encoded)
        }

    @Test
    fun theHolderNeverPrintsTheKey() {
        assertFalse(key in keys.toString())
    }
}
