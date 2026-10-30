package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract test suite for [RecordStore] implementations.
 * Each implementation must pass these tests to be considered conformant.
 */
abstract class RecordStoreContractTest {
    protected abstract fun createStore(): RecordStore

    protected val validFolder: String = "ab".repeat(32) // 64 hex chars
    protected val validName: String = "cd".repeat(32) // 64 hex chars
    protected val validBytes: ByteArray = ByteArray(4096) { it.toByte() }

    @Test
    fun putAndGetRoundTrip() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            val result = store.get(validFolder, validName)
            assertContentEquals(validBytes, result)
        }

    @Test
    fun getMissingReturnsNull() =
        runTest {
            val store = createStore()
            val result = store.get(validFolder, validName)
            assertNull(result)
        }

    @Test
    fun overwrite() =
        runTest {
            val store = createStore()
            val newBytes = ByteArray(4096) { (it + 1).toByte() }
            store.put(validFolder, validName, validBytes)
            store.put(validFolder, validName, newBytes)
            val result = store.get(validFolder, validName)
            assertContentEquals(newBytes, result)
        }

    @Test
    fun listEmptyFolder() =
        runTest {
            val store = createStore()
            val result = store.list(validFolder)
            assertTrue(result.isEmpty())
        }

    @Test
    fun listFolderWithRecords() =
        runTest {
            val store = createStore()
            val name1 = "ab".repeat(32)
            val name2 = "cd".repeat(32)
            store.put(validFolder, name1, validBytes)
            store.put(validFolder, name2, validBytes)
            val result = store.list(validFolder)
            assertContains(result, name1)
            assertContains(result, name2)
            assertEquals(2, result.size)
        }

    @Test
    fun deleteRecord() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            store.delete(validFolder, validName)
            assertNull(store.get(validFolder, validName))
        }

    @Test
    fun deleteMissingRecordIsNoError() =
        runTest {
            val store = createStore()
            // Should not throw
            store.delete(validFolder, validName)
        }

    @Test
    fun deleteFolder() =
        runTest {
            val store = createStore()
            val name1 = "ab".repeat(32)
            val name2 = "cd".repeat(32)
            store.put(validFolder, name1, validBytes)
            store.put(validFolder, name2, validBytes)
            store.deleteFolder(validFolder)
            assertNull(store.get(validFolder, name1))
            assertNull(store.get(validFolder, name2))
            assertTrue(store.list(validFolder).isEmpty())
        }

    @Test
    fun deleteMissingFolderIsNoError() =
        runTest {
            val store = createStore()
            // Should not throw
            store.deleteFolder(validFolder)
        }

    @Test
    fun foldersEmpty() =
        runTest {
            val store = createStore()
            assertTrue(store.folders().isEmpty())
        }

    @Test
    fun foldersWithData() =
        runTest {
            val store = createStore()
            val folder1 = "ab".repeat(32)
            val folder2 = "cd".repeat(32)
            store.put(folder1, validName, validBytes)
            store.put(folder2, validName, validBytes)
            val result = store.folders()
            assertContains(result, folder1)
            assertContains(result, folder2)
        }

    @Test
    fun deleteAll() =
        runTest {
            val store = createStore()
            val folder1 = "ab".repeat(32)
            val folder2 = "cd".repeat(32)
            store.put(folder1, validName, validBytes)
            store.put(folder2, validName, validBytes)
            store.deleteAll()
            assertTrue(store.folders().isEmpty())
            assertTrue(store.list(folder1).isEmpty())
            assertTrue(store.list(folder2).isEmpty())
        }

    @Test
    fun isEmptyFreshStore() =
        runTest {
            val store = createStore()
            assertTrue(store.isEmpty())
        }

    @Test
    fun isEmptyAfterPut() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            assertFalse(store.isEmpty())
        }

    @Test
    fun isEmptyAfterDeleteAll() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            store.deleteAll()
            assertTrue(store.isEmpty())
        }

    // --- Name validation tests ---

    @Test
    fun uppercaseNameThrows() =
        runTest {
            val store = createStore()
            val badName = validName.uppercase()
            try {
                store.put(validFolder, badName, validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun shortNameThrows() =
        runTest {
            val store = createStore()
            val badName = "a".repeat(63)
            try {
                store.put(validFolder, badName, validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun longNameThrows() =
        runTest {
            val store = createStore()
            val badName = "a".repeat(65)
            try {
                store.put(validFolder, badName, validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun emptyNameThrows() =
        runTest {
            val store = createStore()
            try {
                store.put(validFolder, "", validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun dotDotNameThrows() =
        runTest {
            val store = createStore()
            try {
                store.put(validFolder, "..", validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun slashInNameThrows() =
        runTest {
            val store = createStore()
            try {
                store.put(validFolder, "a/b", validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun percentEncodedNameThrows() =
        runTest {
            val store = createStore()
            try {
                store.put(validFolder, "%2e%2e", validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun nulInNameThrows() =
        runTest {
            val store = createStore()
            try {
                store.put(validFolder, "a\u0000b", validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }

    @Test
    fun folderValidationUppercaseThrows() =
        runTest {
            val store = createStore()
            val badFolder = validFolder.uppercase()
            try {
                store.put(badFolder, validName, validBytes)
                fail("Expected RecordStoreException")
            } catch (e: RecordStoreException) {
                assertNull(e.message)
                assertNull(e.cause)
            }
        }
}
