package app.gifitup.cli

import app.gifitup.shared.data.storage.StoredIdentity
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StateDirTest {
    private val dir = Files.createTempDirectory("cli-state-").resolve("state")
    private val state = StateDir(dir)
    private val identity = StoredIdentity("https://e2e.invalid", "ann", byteArrayOf(1, 2, 3, -1), deviceId = null)

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
            assertEquals(listOf("identity", "lock", "snapshot-key"), dir.listDirectoryEntries().map { it.name }.sorted())
            if ("posix" in dir.fileSystem.supportedFileAttributeViews()) {
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
                dir.listDirectoryEntries().forEach {
                    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it)), it.name)
                }
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
}
