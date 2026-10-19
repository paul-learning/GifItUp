package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android file store on the JVM: it is plain files (ADR-0011). */
class FileLocalStoreTest {
    private val dir = Files.createTempDirectory("gifitup-store").toFile().resolve("state")
    private val store = FileLocalStore(dir)
    private val file = File(dir, FileLocalStore.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        assertTrue(dir.parentFile.deleteRecursively(), "the test directory is removed")
    }

    private fun names() = dir.list().orEmpty().sorted()

    @Test
    fun roundTripsEntriesAndNullDeletes() =
        runTest {
            assertNull(store.get("a"))
            store.putAll(mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2, 3)))
            assertContentEquals(byteArrayOf(1), store.get("a"))
            store.putAll(mapOf("a" to null, "c" to byteArrayOf()))
            assertNull(store.get("a"))
            assertContentEquals(byteArrayOf(2, 3), store.get("b"))
            assertContentEquals(byteArrayOf(), store.get("c"))
            assertContentEquals(byteArrayOf(2, 3), FileLocalStore(dir).get("b"), "a new instance reads it from disk")
        }

    /** The store keeps no history: one file after any number of writes, and no temp file (ADR-0011, #16 SEC-3). */
    @Test
    fun keepsOneFileAndNoOldCopy() =
        runTest {
            repeat(3) { store.putAll(mapOf("a" to byteArrayOf(it.toByte()))) }
            assertEquals(listOf(FileLocalStore.FILE_NAME), names())
            assertEquals(FileLocalStore.encode(mapOf("a" to byteArrayOf(2))).toList(), file.readBytes().toList())
        }

    @Test
    fun aFailedWriteLeavesTheOldFileAndNoTempFile() =
        runTest {
            store.putAll(mapOf("a" to byteArrayOf(1)))
            val before = file.readBytes()
            // A directory where the temp file goes makes the write fail before the rename.
            assertTrue(File(dir, FileLocalStore.TEMP_NAME).mkdir())
            assertEquals(STORAGE, assertFailsWith<LocalStateException> { store.putAll(mapOf("a" to byteArrayOf(2))) }.problem)
            assertContentEquals(before, file.readBytes())
            assertContentEquals(byteArrayOf(1), store.get("a"))
        }

    @Test
    fun aRenameThatFailsLeavesTheOldFileAndNoTempFile() =
        runTest {
            store.putAll(mapOf("a" to byteArrayOf(1)))
            val before = file.readBytes()
            val failing = FileLocalStore(dir) { _, _ -> throw java.io.IOException("rename failed") }
            assertEquals(STORAGE, assertFailsWith<LocalStateException> { failing.putAll(mapOf("a" to byteArrayOf(2))) }.problem)
            assertEquals(listOf(FileLocalStore.FILE_NAME), names())
            assertContentEquals(before, file.readBytes())
        }

    @Test
    fun aFileThatIsNotOursIsDamagedNeverGuessed() =
        runTest {
            val good = FileLocalStore.encode(mapOf("a" to byteArrayOf(1, 2, 3)))
            val bad =
                listOf(
                    byteArrayOf(),
                    byteArrayOf(2) + good.copyOfRange(1, good.size),
                    good.copyOf(good.size - 1),
                    good + 0,
                    byteArrayOf(1, 0, 0, 0, 2, 0, 1, 97, 0, 0, 0, 0, 0, 1, 97, 0, 0, 0, 0),
                    byteArrayOf(1, -1, -1, -1, -1),
                    byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0),
                    byteArrayOf(1, 0, 0, 0, 1, 0, 1, -1, 0, 0, 0, 0),
                )
            dir.mkdirs()
            bad.forEach { content ->
                file.writeBytes(content)
                assertEquals(DAMAGED, assertFailsWith<LocalStateException> { store.get("a") }.problem, content.toList().toString())
            }
        }

    @Test
    fun anOversizedFileIsDamagedWithoutBeingRead() =
        runTest {
            dir.mkdirs()
            // Well formed, so only the size limit can refuse it.
            file.writeBytes(FileLocalStore.encode(mapOf("a" to ByteArray(17 * 1024 * 1024))))
            assertEquals(DAMAGED, assertFailsWith<LocalStateException> { store.get("a") }.problem)
        }

    @Test
    fun deleteAllRemovesTheFileAndACrashedTempFileAndIsIdempotent() =
        runTest {
            store.putAll(mapOf("a" to byteArrayOf(1)))
            File(dir, FileLocalStore.TEMP_NAME).writeBytes(byteArrayOf(9))
            store.deleteAll()
            assertEquals(emptyList(), names())
            store.deleteAll()
            assertNull(store.get("a"))
        }
}
