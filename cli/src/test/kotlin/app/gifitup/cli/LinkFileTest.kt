package app.gifitup.cli

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The invite link's file: new only, owner-only, the link and a newline. */
class LinkFileTest {
    private val dir = Files.createTempDirectory("cli-link-")

    @Test
    fun theLinkGoesToANewOwnerOnlyFile() {
        val file = dir.resolve("link")
        LinkFile.createEmpty(file).use { LinkFile.write(it, "https://e2e.invalid/invite#AAAAAAAAAAAAAAAAAAAAAA") }
        assertEquals("https://e2e.invalid/invite#AAAAAAAAAAAAAAAAAAAAAA\n", Files.readString(file))
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
    }

    @Test
    fun anExistingFileIsNeverOverwritten() {
        val file = Files.writeString(dir.resolve("link"), "keep")
        assertEquals(EXIT_REFUSED, assertFailsWith<CliError> { LinkFile.createEmpty(file) }.exit)
        assertEquals("keep", Files.readString(file))
        assertEquals(EXIT_REFUSED, assertFailsWith<CliError> { LinkFile.createEmpty(dir.resolve("missing/link")) }.exit)
    }
}
