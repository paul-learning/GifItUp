package app.gifitup.cli

import app.gifitup.shared.data.contacts.Contact
import app.gifitup.shared.data.contacts.ContactBook
import app.gifitup.shared.data.contacts.ContactStatus
import app.gifitup.shared.data.contacts.KeyBytes
import app.gifitup.shared.data.storage.ContactLoad
import app.gifitup.shared.data.storage.StoredIdentity
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateDirTest {
    private val dir = Files.createTempDirectory("cli-state-").resolve("state")
    private val state = StateDir(dir)
    private val identity = StoredIdentity("https://e2e.invalid", "ann", byteArrayOf(1, 2, 3, -1), deviceId = null)
    private val pinned =
        Contact(
            ACCOUNT,
            "ben",
            ContactStatus.OK,
            mapOf(KeyBytes(ByteArray(32) { 5 }) to "00000000-0000-4000-8000-000000000002"),
            null,
            1L,
            1L,
        )

    @Test
    fun identityAndKeyRoundTripAndClear() =
        runBlocking {
            state.lock().use {
                assertNull(state.identities.load())
                state.identities.save(identity)
                state.keys.save(ByteArray(32) { 7 })
                val loaded = state.identities.load()!!
                assertEquals(identity.toString(), loaded.toString())
                assertContentEquals(identity.snapshot, loaded.snapshot)
                assertContentEquals(ByteArray(32) { 7 }, state.keys.load())
                state.identities.save(StoredIdentity(identity.origin, "ann", identity.snapshot, "0f8fad5b-d9cb-469f-a165-70867728950e"))
                assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", state.identities.load()?.deviceId)
                state.identities.clear()
                state.keys.clear()
                assertNull(state.identities.load())
                assertNull(state.keys.load())
            }
        }

    @Test
    fun filesAreOwnerOnlyAndNoTempFilesRemain() =
        runBlocking {
            state.lock().use {
                state.identities.save(identity)
                state.keys.save(ByteArray(32))
            }
            assertEquals(listOf("contacts", "identity", "lock", "snapshot-key"), dir.listDirectoryEntries().map { it.name }.sorted())
            if ("posix" in dir.fileSystem.supportedFileAttributeViews()) {
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                dir.listDirectoryEntries().forEach {
                    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it)), it.name)
                }
            }
        }

    @Test
    fun theContactBookIsCreatedWithTheIdentityKeptOnLaterSavesAndGoesWithClear() =
        runBlocking {
            state.lock().use {
                assertIs<ContactLoad.Unavailable>(state.contacts.load())
                state.identities.save(identity)
                val empty = assertIs<ContactLoad.Book>(state.contacts.load()).book
                assertEquals(0, empty.contacts.size)
                state.contacts.save(ContactBook(mapOf(ACCOUNT to pinned)))
                state.identities.save(StoredIdentity(identity.origin, "ann", byteArrayOf(2), identity.deviceId))
                assertEquals(1, assertIs<ContactLoad.Book>(state.contacts.load()).book.pinCount, "a later save keeps the book")
                state.identities.clear()
                assertIs<ContactLoad.Unavailable>(state.contacts.load())
                assertFalse(Files.exists(dir.resolve("contacts")))
            }
        }

    @Test
    fun aDamagedOrMissingContactFileIsUnavailableNeverAnEmptyBook() =
        runBlocking {
            state.lock().use {
                state.identities.save(identity)
                Files.delete(dir.resolve("contacts"))
                assertIs<ContactLoad.Unavailable>(state.contacts.load())
                Files.write(dir.resolve("contacts"), byteArrayOf(1, 2, 3))
                assertIs<ContactLoad.Unavailable>(state.contacts.load())
                state.identities.save(identity)
                assertContentEquals(
                    byteArrayOf(1, 2, 3),
                    Files.readAllBytes(dir.resolve("contacts")),
                    "a save never replaces a damaged book",
                )
            }
        }

    @Test
    fun oneProcessAtATime() {
        state.lock().use {
            val error = assertFailsWith<CliError> { state.lock() }
            assertEquals(EXIT_REFUSED, error.exit)
        }
        state.lock().close()
    }

    /** The lock is an OS lock, so a second process is refused too, not just a second caller in this JVM (#102 SEC-18). */
    @Test
    fun anotherProcessIsRefusedWhileTheLockIsHeld() {
        val javaBin =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow()
        val classpath = System.getProperty("java.class.path")
        val args = listOf("reset", "--server", "http://127.0.0.1:9", "--origin", "https://e2e.invalid", "--state-dir", "$dir")
        state.lock().use {
            val process =
                ProcessBuilder(listOf(javaBin, "-cp", classpath, "app.gifitup.cli.MainKt") + args)
                    .apply { environment().remove("JAVA_TOOL_OPTIONS") } // the JVM announces it on stderr
                    .start()
            process.outputStream.close()
            // A regression that waits for the lock fails here instead of hanging the build (#103 delta). The stderr
            // is a line or two, so it can't fill the pipe before the process exits.
            val exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            assertTrue(exited, "the second process didn't exit")
            val stderr = process.errorStream.bufferedReader().readText()
            assertEquals(EXIT_REFUSED, process.exitValue(), stderr)
            assertEquals("gifitup-cli: another gifitup-cli is using this state dir\n", stderr.replace("\r\n", "\n"))
        }
    }

    @Test
    fun damagedFilesAreRefusedNotGuessed() =
        runBlocking {
            state.lock().use {
                listOf(
                    "",
                    "origin=https://e2e.invalid\nusername=ann\n",
                    "origin=https://e2e.invalid\nusername=ann\nsnapshot=***\n",
                ).forEach {
                    dir.resolve("identity").writeText(it)
                    assertEquals(EXIT_REFUSED, assertFailsWith<CliError> { state.identities.load() }.exit)
                }
                Files.write(dir.resolve("snapshot-key"), ByteArray(31))
                assertEquals(EXIT_REFUSED, assertFailsWith<CliError> { state.keys.load() }.exit)
                assertEquals(EXIT_REFUSED, assertFailsWith<CliError> { state.keys.save(ByteArray(33)) }.exit)
                assertEquals(31, Files.size(dir.resolve("snapshot-key")))
            }
        }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 60L
    }
}

private const val ACCOUNT = "00000000-0000-4000-8000-000000000001"
