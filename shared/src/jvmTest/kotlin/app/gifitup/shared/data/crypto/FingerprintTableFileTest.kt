package app.gifitup.shared.data.crypto

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The emoji table exists twice, in `crypto/fingerprint-emojis-v1.tsv` (whose SHA-256 the crate pins) and in
 * [FingerprintEmojis]. They must be the same list in the same order, so the table can't change without a change under
 * `crypto/`, a high-tier path (ADR-0014).
 */
class FingerprintTableFileTest {
    private val root: Path =
        generateSequence(Path.of("").toAbsolutePath()) {
            it.parent
        }.first { Files.exists(it.resolve("settings.gradle.kts")) }

    @Test
    fun theKotlinTableIsTheCratesFile() {
        val entries =
            Files
                .readAllLines(root.resolve("crypto").resolve("fingerprint-emojis-v1.tsv"))
                .filterNot { it.startsWith("#") }
                .map { it.split('\t') }
        assertEquals(FingerprintEmojis.TABLE_SIZE, entries.size)
        entries.forEachIndexed { index, (number, codePoint, name) ->
            assertEquals(index, number.toInt())
            val cell = FingerprintEmojis.table[index]
            assertEquals(codePoint.removePrefix("U+").toInt(16), cell.emoji.codePointAt(0), "code point of entry $index")
            assertEquals(name, cell.name, "name of entry $index")
        }
    }
}
