package app.gifitup.server.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GifProviderKeyTest {
    private val dir = Files.createTempDirectory("gif-key-")
    private val good = "AbCdEfGh01234567_-xyz"

    private fun file(bytes: ByteArray): Path = dir.resolve("key").also { Files.write(it, bytes) }

    private fun file(text: String) = file(text.toByteArray(Charsets.ISO_8859_1))

    private fun fails(path: Path): String = assertFailsWith<IllegalArgumentException> { GifProviderKey.read(path) }.message.orEmpty()

    @Test
    fun aKeyWithOneLineEndIsRead() {
        assertEquals(good, GifProviderKey.read(file(good)).reveal())
        assertEquals(good, GifProviderKey.read(file("$good\n")).reveal())
        assertEquals(good, GifProviderKey.read(file("$good\r\n")).reveal())
    }

    @Test
    fun nothingElseIsTrimmedOrAccepted() {
        listOf(
            "$good ",
            " $good",
            "$good\r",
            "$good\n\n",
            "$good\n$good",
            "",
            "\n",
            "short",
            "a".repeat(129),
            "$good/../x",
            "$good?x=1",
            "$good#",
            "$good%2F",
            "$good.",
            "$good:x",
            "$good@",
            "a b".repeat(8),
            "\u00EF\u00BB\u00BF$good",
            "${good}ü",
        ).forEach { text ->
            val message = fails(file(text))
            assertFalse(good in message, "the message never quotes the content")
        }
        assertTrue(GifProviderKey.read(file("a".repeat(128))).reveal().length == 128)
        assertTrue(GifProviderKey.read(file("a".repeat(16))).reveal().length == 16)
    }

    @Test
    fun aHugeOrBinaryFileIsRefusedWithoutBeingQuoted() {
        val message = fails(file(ByteArray(10_000_000) { 'A'.code.toByte() }))
        assertEquals("GIF_PROVIDER_KEY_FILE: the file is too big for a key", message)
        assertEquals("GIF_PROVIDER_KEY_FILE: the file does not hold a valid key", fails(file(byteArrayOf(0, 1, 2, -1, -2))))
    }

    @Test
    fun aMissingFileOrADirectoryNamesOnlyTheVariable() {
        val message = fails(dir.resolve("missing"))
        assertEquals("GIF_PROVIDER_KEY_FILE does not name a readable file", message)
        assertFalse(dir.toString() in message)
        assertEquals("GIF_PROVIDER_KEY_FILE does not name a readable file", fails(dir))
    }

    @Test
    fun anInvalidPathNeverReachesTheMessage() {
        val message =
            assertFailsWith<IllegalArgumentException> {
                GifProviderKey.fromEnv(
                    mapOf("GIF_PROVIDER_KEY_FILE" to "/x\u0000y"),
                )
            }.message
        assertEquals("GIF_PROVIDER_KEY_FILE does not name a readable file", message)
    }

    @Test
    fun theVariableUnsetMeansNoKeyAndSetButEmptyFailsStartup() {
        assertNull(GifProviderKey.fromEnv(emptyMap()))
        assertFailsWith<IllegalArgumentException> { GifProviderKey.fromEnv(mapOf("GIF_PROVIDER_KEY_FILE" to "")) }
        assertEquals(good, GifProviderKey.fromEnv(mapOf("GIF_PROVIDER_KEY_FILE" to "${file(good)}"))?.reveal())
    }

    @Test
    fun theKeyNeverPrintsFromTheHolderOrAnythingHoldingIt() {
        val key = GifProviderKey.read(file(good))
        assertEquals("GifProviderKey(redacted)", key.toString())
        val endpoint = GifProviderEndpoint.of(key, java.time.Clock.systemUTC())
        assertFalse(good in endpoint.key.toString())
    }
}
