@file:Suppress("TooManyFunctions")

package app.gifitup.mls.storage

import app.gifitup.mls.toByteArray
import app.gifitup.mls.toUint8Array
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.data.storage.isRecordName
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * A [RecordStore] in the browser's IndexedDB: its own database `gifitup-history`, one object store,
 * key `"<folder>/<name>"` (both checked with [isRecordName] first), value `Uint8Array`.
 *
 * Does **not** touch the `gifitup` database of ADR-0011 or its versioning.
 *
 * It does not take the Web Lock itself: PR 4 opens it only after `claimWebDeviceStorage()` returned
 * a non-null `DeviceStorage`.
 *
 * Every failure is a [RecordStoreException] with no message and no cause (security rule 4).
 */
class IndexedDbRecordStore(
    private val name: String = DATABASE_NAME,
) : RecordStore {
    private var db: JsAny? = null

    override suspend fun put(
        folder: String,
        name: String,
        bytes: ByteArray,
    ) {
        check(folder, name)
        val key = "$folder/$name"
        val value = bytes.toUint8Array()
        val db = ensureDb()
        settled(idbPut(db, STORE_NAME, key, value))
    }

    override suspend fun get(
        folder: String,
        name: String,
    ): ByteArray? {
        check(folder, name)
        val key = "$folder/$name"
        val db = ensureDb()
        val result = settled(idbGet(db, STORE_NAME, key))
        if (result !is Uint8Array) return null
        return result.toByteArray()
    }

    override suspend fun list(folder: String): List<String> {
        check(folder)
        val db = ensureDb()
        val jsKeys = settled(idbGetAllKeysInRange(db, STORE_NAME, "$folder/", "${folder}0"))
        return extractNames(jsKeys)
    }

    override suspend fun delete(
        folder: String,
        name: String,
    ) {
        check(folder, name)
        val key = "$folder/$name"
        val db = ensureDb()
        settled(idbDelete(db, STORE_NAME, key))
    }

    override suspend fun deleteFolder(folder: String) {
        check(folder)
        val db = ensureDb()
        val jsKeys = settled(idbGetAllKeysInRange(db, STORE_NAME, "$folder/", "${folder}0"))
        val keys = extractKeys(jsKeys)
        if (keys.isNotEmpty()) {
            settled(idbDeleteRange(db, STORE_NAME, keys))
        }
    }

    override suspend fun folders(): List<String> {
        val db = ensureDb()
        val jsKeys = settled(idbGetAllKeys(db, STORE_NAME))
        return extractFolders(jsKeys)
    }

    override suspend fun isEmpty(): Boolean {
        val db = ensureDb()
        val count = settled(idbCount(db, STORE_NAME))
        @Suppress("UNCHECKED_CAST")
        return (count as? Number)?.toLong() == 0L
    }

    override suspend fun deleteAll() {
        val db = ensureDb()
        settled(idbClear(db, STORE_NAME))
    }

    private suspend fun ensureDb(): JsAny {
        if (db != null) return db!!
        db = settled(idbOpen(name))
        return db!!
    }

    private fun check(
        folder: String,
        name: String? = null,
    ) {
        if (!isRecordName(folder) || (name != null && !isRecordName(name))) {
            throw RecordStoreException()
        }
    }

    private fun extractNames(jsKeys: JsAny?): List<String> {
        @Suppress("UNCHECKED_CAST")
        val keys = jsKeys as? JsArray<JsString> ?: return emptyList()
        val result = ArrayList<String>(keys.length)
        for (i in 0 until keys.length) {
            val jsKey = keys[i]
            @Suppress("UNCHECKED_CAST")
            val key = jsKey as String
            val slashIndex = key.lastIndexOf('/')
            if (slashIndex >= 0 && slashIndex < key.length - 1) {
                result.add(key.substring(slashIndex + 1))
            }
        }
        return result.filter { isRecordName(it) }
    }

    private fun extractKeys(jsKeys: JsAny?): List<String> {
        @Suppress("UNCHECKED_CAST")
        val keys = jsKeys as? JsArray<JsString> ?: return emptyList()
        val result = ArrayList<String>(keys.length)
        for (i in 0 until keys.length) {
            val jsKey = keys[i]
            @Suppress("UNCHECKED_CAST")
            result.add(jsKey as String)
        }
        return result
    }

    private fun extractFolders(jsKeys: JsAny?): List<String> {
        @Suppress("UNCHECKED_CAST")
        val keys = jsKeys as? JsArray<JsString> ?: return emptyList()
        val folders = mutableSetOf<String>()
        for (i in 0 until keys.length) {
            val jsKey = keys[i]
            @Suppress("UNCHECKED_CAST")
            val key = jsKey as String
            val slashIndex = key.indexOf('/')
            if (slashIndex >= 0) {
                folders.add(key.substring(0, slashIndex))
            }
        }
        return folders.filter { isRecordName(it) }.toList()
    }

    companion object {
        private const val DATABASE_NAME = "gifitup-history"
        private const val STORE_NAME = "records"

        @JsFun(
            """function(dbName) {
                return new Promise((resolve, reject) => {
                    const request = indexedDB.open(dbName, 1);
                    request.onupgradeneeded = () => {
                        request.result.createObjectStore('records', { keyPath: null });
                    };
                    request.onsuccess = () => {
                        const db = request.result;
                        db.onversionchange = () => { db.gifitupClosed = true; db.close(); };
                        resolve(db);
                    };
                    request.onerror = () => reject(request.error);
                    request.onblocked = () => reject(new Error('blocked'));
                });
            }""",
        )
        private external fun idbOpen(name: String): Promise<JsAny?>

        @JsFun(
            """function(db, store, key) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const request = tx.objectStore(store).get(key);
                    tx.oncomplete = () => resolve(request.result === undefined ? null : request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                });
            }""",
        )
        private external fun idbGet(
            db: JsAny,
            store: String,
            key: String,
        ): Promise<JsAny?>

        @JsFun(
            """function(db, store, key, value) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
                    tx.oncomplete = () => resolve(null);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                    try {
                        tx.objectStore(store).put(value, key);
                    } catch (e) {
                        tx.abort();
                        reject(e);
                    }
                });
            }""",
        )
        private external fun idbPut(
            db: JsAny,
            store: String,
            key: String,
            value: JsAny,
        ): Promise<JsAny?>

        @JsFun(
            """function(db, store, key) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
                    tx.oncomplete = () => resolve(null);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                    try {
                        tx.objectStore(store).delete(key);
                    } catch (e) {
                        tx.abort();
                        reject(e);
                    }
                });
            }""",
        )
        private external fun idbDelete(
            db: JsAny,
            store: String,
            key: String,
        ): Promise<JsAny?>

        @JsFun(
            """function(db, store, lower, upper) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const range = IDBKeyRange.bound(lower, upper, false, true);
                    const request = tx.objectStore(store).getAllKeys(range);
                    tx.oncomplete = () => resolve(request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                });
            }""",
        )
        private external fun idbGetAllKeysInRange(
            db: JsAny,
            store: String,
            lower: String,
            upper: String,
        ): Promise<JsAny?>

        @JsFun(
            """function(db, store, keys) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
                    tx.oncomplete = () => resolve(null);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                    try {
                        const objectStore = tx.objectStore(store);
                        for (const key of keys) {
                            objectStore.delete(key);
                        }
                    } catch (e) {
                        tx.abort();
                        reject(e);
                    }
                });
            }""",
        )
        private external fun idbDeleteRange(
            db: JsAny,
            store: String,
            keys: List<String>,
        ): Promise<JsAny?>

        @JsFun(
            """function(db, store) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const request = tx.objectStore(store).getAllKeys();
                    tx.oncomplete = () => resolve(request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                });
            }""",
        )
        private external fun idbGetAllKeys(
            db: JsAny,
            store: String,
        ): Promise<JsAny?>

        @JsFun(
            """function(db, store) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const request = tx.objectStore(store).count();
                    tx.oncomplete = () => resolve(request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                });
            }""",
        )
        private external fun idbCount(
            db: JsAny,
            store: String,
        ): Promise<JsAny?>

        @JsFun(
            """function(db, store) {
                return new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
                    tx.oncomplete = () => resolve(null);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                    try {
                        tx.objectStore(store).clear();
                    } catch (e) {
                        tx.abort();
                        reject(e);
                    }
                });
            }""",
        )
        private external fun idbClear(
            db: JsAny,
            store: String,
        ): Promise<JsAny?>

        internal suspend fun settled(promise: Promise<JsAny?>): JsAny? {
            val result = settle(promise).await<Settled>()
            if (result.failed) throw RecordStoreException()
            return result.value
        }

        internal external interface Settled : JsAny {
            val failed: Boolean
            val value: JsAny?
            val error: String?
        }

        @JsFun(
            """function(promise) {
                return promise.then(
                    (value) => ({ failed: false, value: value === undefined ? null : value }),
                    (e) => ({ failed: true, error: (e && typeof e.name === 'string') ? e.name : 'Error' }),
                );
            }""",
        )
        private external fun settle(promise: Promise<JsAny?>): Promise<Settled>

        @JsFun("function(value) { return value instanceof Uint8Array; }"
        )
        private external fun isUint8Array(value: JsAny?): Boolean
    }
}
