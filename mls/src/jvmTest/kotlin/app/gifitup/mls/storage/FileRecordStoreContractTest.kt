package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.RecordStoreException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The contract of [FileRecordStore] on a real directory, and the parts that are specific to files (ADR-0017). */
class FileRecordStoreContractTest : RecordStoreContractTest() {
    private lateinit var root: File

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("gifitup-records-").toFile()
    }

    @AfterTest
    fun teardown() {
        root.deleteRecursively()
    }

    override fun createStore(): FileRecordStore = FileRecordStore(root, move, hostSync)

    private fun folderDir() = File(root, validFolder)

    @Test
    fun aMalformedEntryMakesTheStoreNotEmpty() =
        runTest {
            File(root, "malformed-name").writeBytes(byteArrayOf(1))
            assertFalse(createStore().isEmpty())
        }

    @Test
    fun aLeftoverTempFileMakesTheStoreNotEmpty() =
        runTest {
            File(root, ".tmp-leftover").writeBytes(byteArrayOf(1))
            assertFalse(createStore().isEmpty())
        }

    @Test
    fun aStrayFolderMakesTheStoreNotEmpty() =
        runTest {
            File(root, "not64hex").mkdirs()
            assertFalse(createStore().isEmpty())
            File(root, "not64hex").delete()
            assertTrue(createStore().isEmpty())
        }

    @Test
    fun aRootThatIsNotADirectoryIsNotEmpty() =
        runTest {
            root.deleteRecursively()
            root.writeBytes(byteArrayOf(1))
            assertFalse(createStore().isEmpty())
            root.delete()
            root.mkdirs()
        }

    @Test
    fun aMissingRootIsEmpty() =
        runTest {
            root.deleteRecursively()
            assertTrue(createStore().isEmpty())
            assertTrue(createStore().folders().isEmpty())
            assertTrue(createStore().list(validFolder).isEmpty())
        }

    @Test
    fun aTempFileInAFolderIsIgnoredAndRemovedByListAndAForeignFileIsKept() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            File(folderDir(), ".tmp-crash").writeBytes(byteArrayOf(1))
            File(folderDir(), "foreign.txt").writeBytes(byteArrayOf(2))
            assertEquals(listOf(validName), store.list(validFolder))
            assertFalse(File(folderDir(), ".tmp-crash").exists())
            assertTrue(File(folderDir(), "foreign.txt").exists())
        }

    @Test
    fun theOrderIsTempWriteThenMoveThenDirectorySyncAndNoTempFileStays() =
        runTest {
            val calls = mutableListOf<String>()
            val store =
                FileRecordStore(
                    root,
                    { from, to ->
                        calls += "move"
                        move(from, to)
                    },
                    { dir ->
                        calls += "sync:${dir.fileName}"
                        hostSync(dir)
                    },
                )
            store.put(validFolder, validName, validBytes)
            assertEquals(listOf("move", "sync:$validFolder", "sync:${root.name}"), calls)
            assertEquals(listOf(validName), folderDir().list().orEmpty().toList())
        }

    @Test
    fun aFailedMoveLeavesTheOldRecordAndNoTempFile() =
        runTest {
            createStore().put(validFolder, validName, validBytes)
            val failing = FileRecordStore(root, { _: Path, _: Path -> throw java.io.IOException("disk") }, hostSync)
            assertFailsWith<RecordStoreException> { failing.put(validFolder, validName, byteArrayOf(9)) }
            assertContentEquals(validBytes, createStore().get(validFolder, validName))
            assertEquals(listOf(validName), folderDir().list().orEmpty().toList())
        }

    @Test
    fun aFailingDirectorySyncIsAClosedFailure() =
        runTest {
            val failing = FileRecordStore(root, move) { throw java.io.IOException("sync") }
            val failure = assertFailsWith<RecordStoreException> { failing.put(validFolder, validName, validBytes) }
            assertNull(failure.message)
            assertNull(failure.cause)
        }

    @Test
    fun aWipeThatCannotDeleteSomethingFailsInsteadOfReportingSuccess() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            File(folderDir(), "nested").also { it.mkdirs() }
            File(File(folderDir(), "nested"), "file").writeBytes(byteArrayOf(1))
            assertFailsWith<RecordStoreException> { store.deleteAll() }
            assertFailsWith<RecordStoreException> { store.deleteFolder(validFolder) }
        }

    @Test
    fun aRootThatCannotBeExaminedIsAnErrorNeverEmptyAndNeverDeleted() =
        runTest {
            val file = File(root, "plain-file").also { it.writeBytes(byteArrayOf(1)) }
            val store = FileRecordStore(File(file, "under-a-file"), move, hostSync)
            assertFailsWith<RecordStoreException> { store.isEmpty() }
            assertFailsWith<RecordStoreException> { store.deleteAll() }
            assertFailsWith<RecordStoreException> { store.deleteFolder(validFolder) }
            assertFailsWith<RecordStoreException> { store.folders() }
            assertFailsWith<RecordStoreException> { store.list(validFolder) }
        }

    @Test
    fun aSymbolicLinkIsDeletedAsALinkAndNeverFollowed() =
        runTest {
            val outside = Files.createTempDirectory("gifitup-outside-").toFile()
            try {
                File(outside, "keep").writeBytes(byteArrayOf(1))
                Files.createSymbolicLink(File(root, validFolder).toPath(), outside.toPath())
                createStore().deleteAll()
                assertTrue(File(outside, "keep").exists(), "the target of a link is never touched")
                assertTrue(createStore().isEmpty())
            } finally {
                outside.deleteRecursively()
            }
        }
}
