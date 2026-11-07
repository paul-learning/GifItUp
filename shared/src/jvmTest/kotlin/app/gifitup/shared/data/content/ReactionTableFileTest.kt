package app.gifitup.shared.data.content

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reaction table exists twice, in `shared/emoji-reactions-v1.tsv` (SHA-256 pinned here) and in [ReactionEmojis]. They
 * must be the same list in the same order, and the file can't change without this test changing, a high-tier path
 * (ADR-0006 B10). The web font's check script (`scripts/check-emoji-font.py`) reads the same file.
 */
class ReactionTableFileTest {
    private val file: Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
            .resolve("shared")
            .resolve("emoji-reactions-v1.tsv")

    @Test
    fun theFileIsThePinnedOne() {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
        assertEquals(PINNED_SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun theKotlinTableIsTheFile() {
        val entries = Files.readAllLines(file).filterNot { it.startsWith("#") }.map { it.split('\t') }
        assertEquals(ReactionEmojis.SIZE, entries.size)
        entries.forEachIndexed { index, (number, codePoints, _) ->
            assertEquals(index, number.toInt())
            val fromFile = codePoints.split(' ').joinToString("") { String(Character.toChars(it.toInt(16))) }
            assertEquals(fromFile, ReactionEmojis.all[index], "entry $index")
        }
    }

    private companion object {
        const val PINNED_SHA256 = "d1a2c050ba04c33ba104ed90849c8ff91fe7e4536403947fe29d2d5b061b5192"
    }
}
