package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.RecordStoreException
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun rawPut(
    name: String,
    key: String,
): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const open = indexedDB.open(name);
            open.onsuccess = () => {
                const db = open.result;
                const tx = db.transaction('records', 'readwrite');
                tx.objectStore('records').put(new Uint8Array([1]), key);
                tx.oncomplete = () => { db.close(); resolve(null); };
                tx.onabort = () => reject(tx.error);
            };
            open.onerror = () => reject(open.error);
        })""",
    )

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun dropDatabase(name: String): Promise<JsAny?> =
    js(
        """new Promise((resolve) => {
            const request = indexedDB.deleteDatabase(name);
            request.onsuccess = () => resolve(null);
            request.onerror = () => resolve(null);
            request.onblocked = () => resolve(null);
        })""",
    )

/** The contract of [IndexedDbRecordStore] on the real IndexedDB in headless Chrome; each test has its own database. */
class IndexedDbRecordStoreContractTest : RecordStoreContractTest() {
    private var databaseName = ""

    @BeforeTest
    fun setup() {
        databaseName = "gifitup-history-test-${Random.nextLong()}"
    }

    @AfterTest
    fun teardown() {
        // The connection of the store under test closes itself when the database is dropped (versionchange).
        dropDatabase(databaseName)
    }

    override fun createStore(): IndexedDbRecordStore = IndexedDbRecordStore(databaseName)

    @Test
    fun aMalformedKeyMakesTheStoreNotEmptyButIsNotAFolderOrARecord() =
        runTest {
            val store = createStore()
            assertTrue(store.isEmpty())
            rawPut(databaseName, "garbage").await<JsAny?>()
            assertFalse(store.isEmpty())
            assertTrue(store.folders().isEmpty())
            store.put(validFolder, validName, validBytes)
            assertEquals(listOf(validFolder), store.folders())
        }

    @Test
    fun aForeignKeyInsideAFolderIsNotARecordAndGoesWithTheFolder() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            rawPut(databaseName, "$validFolder/foreign").await<JsAny?>()
            assertEquals(listOf(validName), store.list(validFolder))
            store.deleteFolder(validFolder)
            assertTrue(store.isEmpty(), "the foreign key in the folder's range went with it")
        }

    @Test
    fun aKeyOutsideEveryFolderSurvivesDeleteFolderButNotDeleteAll() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            rawPut(databaseName, "garbage").await<JsAny?>()
            store.deleteFolder(validFolder)
            assertFalse(store.isEmpty())
            store.deleteAll()
            assertTrue(store.isEmpty())
        }

    @Test
    fun deletingTheDatabaseEmptiesItAndFinishesThatStore() =
        runTest {
            val store = createStore()
            store.put(validFolder, validName, validBytes)
            rawPut(databaseName, "garbage").await<JsAny?>()
            store.deleteDatabase()
            assertTrue(createStore().isEmpty(), "nothing survives the wipe, malformed keys included")
            assertFailsWith<RecordStoreException> { store.put(validFolder, validName, validBytes) }
            assertTrue(createStore().isEmpty(), "and a late write did not recreate anything")
        }
}
