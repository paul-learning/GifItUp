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
        val result = settled(idbGet(db, STORE_NAME, key)) ?: return null
        if (!isUint8Array(result)) return null
        @Suppress("UNCHECKED_CAST")
        return (result as Uint8Array).toByteArray()
    }

    override suspend fun list(folder: String): List<String> {
        check(folder)
        val db = ensureDb()
        val result = settled(idbGetAllKeysInRange(db, STORE_NAME, "$folder/", "${folder}0"))
        @Suppress("UNCHECKED_CAST")
        val jsKeys = result as? JsArray<JsString> ?: return emptyList()
        val keys = ArrayList<String>(jsKeys.length)
        for (i in 0 until jsKeys.length) {
            val jsKey = jsKeys[i]
            @Suppress("UNCHECKED_CAST")
            val key = jsKey as String
            val slashIndex = key.lastIndexOf('/')
            if (slashIndex >= 0 && slashIndex < key.length - 1) {
                keys.add(key.substring(slashIndex + 1))
            }
        }
        return keys.filter { isRecordName(it) }
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
        val result = settled(idbGetAllKeysInRange(db, STORE_NAME, "$folder/", "${folder}0"))
        @Suppress("UNCHECKED_CAST")
        val jsKeys = result as? JsArray<JsString> ?: return
        val keys = ArrayList<String>(jsKeys.length)
        for (i in 0 until jsKeys.length) {
            val jsKey = jsKeys[i]
            @Suppress("UNCHECKED_CAST")
            keys.add(jsKey as String)
        }
        if (keys.isNotEmpty()) {
            settled(idbDeleteRange(db, STORE_NAME, keys.toTypedArray()))
        }
    }

    override suspend fun folders(): List<String> {
        val db = ensureDb()
        val result = settled(idbGetAllKeys(db, STORE_NAME))
        @Suppress("UNCHECKED_CAST")
        val jsKeys = result as? JsArray<JsString> ?: return emptyList()
        val folders = mutableSetOf<String>()
        for (i in 0 until jsKeys.length) {
            val jsKey = jsKeys[i]
            @Suppress("UNCHECKED_CAST")
            val key = jsKey as String
            val slashIndex = key.indexOf('/')
            if (slashIndex >= 0) {
                folders.add(key.substring(0, slashIndex))
            }
        }
        return folders.filter { isRecordName(it) }.toList()
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

    companion object {
        private const val DATABASE_NAME = "gifitup-history"
        private const val STORE_NAME = "records"

        @Suppress("UnusedParameter")
        private fun idbOpen(name: String): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const request = indexedDB.open(name, 1);
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
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbGet(
            db: JsAny,
            store: String,
            key: String,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const request = tx.objectStore(store).get(key);
                    tx.oncomplete = () => resolve(request.result === undefined ? null : request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbPut(
            db: JsAny,
            store: String,
            key: String,
            value: JsAny,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
                    tx.oncomplete = () => resolve(null);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                    try {
                        tx.objectStore(store).put(value, key);
                    } catch (e) {
                        tx.abort();
                        reject(e);
                    }
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbDelete(
            db: JsAny,
            store: String,
            key: String,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
                    tx.oncomplete = () => resolve(null);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                    try {
                        tx.objectStore(store).delete(key);
                    } catch (e) {
                        tx.abort();
                        reject(e);
                    }
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbGetAllKeysInRange(
            db: JsAny,
            store: String,
            lower: String,
            upper: String,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const range = IDBKeyRange.bound(lower, upper, false, true);
                    const request = tx.objectStore(store).getAllKeys(range);
                    tx.oncomplete = () => resolve(request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbDeleteRange(
            db: JsAny,
            store: String,
            keys: Array<String>,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
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
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbGetAllKeys(
            db: JsAny,
            store: String,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const request = tx.objectStore(store).getAllKeys();
                    tx.oncomplete = () => resolve(request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbCount(
            db: JsAny,
            store: String,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readonly');
                    const request = tx.objectStore(store).count();
                    tx.oncomplete = () => resolve(request.result);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                })""",
            )

        @Suppress("UnusedParameter")
        private fun idbClear(
            db: JsAny,
            store: String,
        ): Promise<JsAny?> =
            js(
                """new Promise((resolve, reject) => {
                    const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
                    tx.oncomplete = () => resolve(null);
                    tx.onabort = () => reject(tx.error || new Error('aborted'));
                    try {
                        tx.objectStore(store).clear();
                    } catch (e) {
                        tx.abort();
                        reject(e);
                    }
                })""",
            )

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

        @Suppress("UnusedParameter")
        private fun settle(promise: Promise<JsAny?>): Promise<Settled> =
            js(
                """promise.then(
                    (value) => ({ failed: false, value: value === undefined ? null : value }),
                    (e) => ({ failed: true, error: (e && typeof e.name === 'string') ? e.name : 'Error' }),
                )""",
            )

        @Suppress("UnusedParameter")
        private fun isUint8Array(value: JsAny?): Boolean =
            js("value instanceof Uint8Array")
    }
}
