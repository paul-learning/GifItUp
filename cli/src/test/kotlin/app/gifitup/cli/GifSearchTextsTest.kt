package app.gifitup.cli

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.gif.GifFailure
import app.gifitup.shared.domain.gif.GifResult
import app.gifitup.shared.domain.gif.GifTile
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GifSearchTextsTest {
    private val bytes = ByteArrayOutputStream()
    private val out = PrintStream(bytes)
    private val hex = "a".repeat(32)

    private fun ref(
        slug: String,
        size: String,
    ) = checkNotNull(GifReference.of("klipy", slug, "https://static.klipy.com/ii/$hex/ab/cd/$slug-$size.webp", 200, 100))

    @Test
    fun aPagePrintsOnlySlugAndPublicLinksAndAPlaceholderPrintsNothingOfTheAnswer() {
        val page = GifResult.Page(listOf(GifTile.Item(ref("one", "sm"), ref("one", "md")), GifTile.Placeholder), hasNext = true)
        assertEquals(EXIT_OK, printGifResult(page, out))
        assertEquals(
            listOf(
                "gif one preview https://static.klipy.com/ii/$hex/ab/cd/one-sm.webp " +
                    "send https://static.klipy.com/ii/$hex/ab/cd/one-md.webp",
                "placeholder",
                "2 tiles, 1 placeholders, next page: yes",
            ),
            bytes.toString().lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun everyFailureIsAFixedLineWithTheServerExitCode() {
        GifFailure.entries.forEach { reason ->
            val failure = assertFailsWith<CliError> { printGifResult(GifResult.Failed(reason, 7), out) }
            assertEquals(EXIT_SERVER, failure.exit)
            assertFalse("key" in failure.message.replace("no key, or it refused the key", ""), failure.message)
        }
        assertEquals("the GIF provider's limit is reached; try again in 7 seconds", failureText(GifFailure.RATE_LIMITED, 7))
    }

    @Test
    fun theUsageHasTheCommandAndAQueryIsOptional() {
        val base = listOf("--server", "https://x.invalid", "--state-dir", "/tmp/s")
        assertEquals(null, Invocation.parse(listOf("gif-search") + base).query)
        assertEquals("cats", Invocation.parse(listOf("gif-search", "--query", "cats") + base).query)
        assertFailsWith<CliError> { Invocation.parse(listOf("gif-provider", "--query", "cats") + base) }
    }
}
