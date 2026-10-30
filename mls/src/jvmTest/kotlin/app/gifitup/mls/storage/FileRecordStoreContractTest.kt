package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.RecordStoreContractTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [FileRecordStore].
 */
class FileRecordStoreContractTest : RecordStoreContractTest() {
    private lateinit var tempDir: File

    @BeforeTest
    fun setup() {
        tempDir = File.createTempFile("gifitup-test-", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    override fun createStore(): FileRecordStore = FileRecordStore(tempDir)

    @Test
    override fun isEmptyWithMalformedEntryIsNotEmpty() = runTest {
        val store = createStore()
        val malformedFile = File(tempDir, "malformed-name")
        malformedFile.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(store.isEmpty())
    }

    @Test
    override fun isEmptyWithTempFileIsNotEmpty() = runTest {
        val store = createStore()
        val tempFile = File(tempDir, ".tmp-someuuid")
        tempFile.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(store.isEmpty())
    }

    @Test
    fun tempFileInFolderIsIgnoredAndRemovedByList() = runTest {
        val store = createStore()
        val folderPath = File(tempDir, validFolder)
        folderPath.mkdirs()
        val tempFile = File(folderPath, ".tmp-someuuid")
        tempFile.writeBytes(byteArrayOf(1, 2, 3))
        val validFile = File(folderPath, validName)
        validFile.writeBytes(validBytes)

        val listed = store.list(validFolder)
        assertTrue(listed.contains(validName))
        assertFalse(listed.contains(".tmp-someuuid"))
        assertFalse(tempFile.exists())
    }

    @Test
    fun foreignFileInFolderIsNotRemoved() = runTest {
        val store = createStore()
        val folderPath = File(tempDir, validFolder)
        folderPath.mkdirs()
        val foreignFile = File(folderPath, "foreign.txt")
        foreignFile.writeBytes(byteArrayOf(1, 2, 3))
        val validFile = File(folderPath, validName)
        validFile.writeBytes(validBytes)

        val listed = store.list(validFolder)
        assertTrue(listed.contains(validName))
        assertFalse(listed.contains("foreign.txt"))
        assertTrue(foreignFile.exists())
    }

    @Test
    fun isEmptyWithForeignFolderIsNotEmpty() = runTest {
        val store = createStore()
        val foreignFolder = File(tempDir, "foreign-folder")
        foreignFolder.mkdirs()
        assertFalse(store.isEmpty())
    }

    @Test
    fun isEmptyWithMalformedFolderNameIsNotEmpty() = runTest {
        val store = createStore()
        val malformedFolder = File(tempDir, "not64hex")
        malformedFolder.mkdirs()
        assertFalse(store.isEmpty())
    }
}
