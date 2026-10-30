@file:Suppress("TooManyFunctions") // One small js() helper per IndexedDB call; they belong together.

package app.gifitup.mls.storage

import app.gifitup.mls.toByteArray
import app.gifitup.mls.toUint8Array
import app.gifitup.shared.data.storage.RecordStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.data.storage.isRecordName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// IndexedDB through small JS helpers, in the style of WebDatabase.kt: each helper returns a promise and does all its
// work inside it, so a synchronous throw is a rejection too; [settled] turns a rejection into a value and here into the
// closed RecordStoreException, so no JS error and no message reaches Kotlin (security rule 4).

private const val DATABASE_NAME = "gifitup-history"
private const val RECORDS = "records"

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbOpenRecords(
    name: String,
    store: String,
): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const request = indexedDB.open(name, 1);
            request.onupgradeneeded = () => { request.result.createObjectStore(store); };
            request.onsuccess = () => {
                const db = request.result;
                db.onversionchange = () => { db.gifitupClosed = true; db.close(); };
                db.onclose = () => { db.gifitupClosed = true; };
                resolve(db);
            };
            request.onerror = () => reject(request.error);
            request.onblocked = () => reject(new Error('blocked'));
        })""",
    )

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun isClosedRecords(db: JsAny): Boolean = js("db.gifitupClosed === true")

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun closeRecords(db: JsAny): JsAny? = js("db.close()")

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbDeleteDatabase(name: String): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const request = indexedDB.deleteDatabase(name);
            request.onsuccess = () => resolve(null);
            request.onerror = () => reject(request.error);
            request.onblocked = () => reject(new Error('blocked'));
        })""",
    )

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbGetRecord(
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

/** One `readwrite` transaction with strict durability; resolves only on `complete`, so the write is durable when it resolves. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbPutRecord(
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

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbDeleteRecord(
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

/** Deletes every key of a folder (`"<folder>/"` up to, not including, `"<folder>0"`) in one transaction, foreign keys in that range too. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbDeleteFolder(
    db: JsAny,
    store: String,
    folder: String,
): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
            tx.oncomplete = () => resolve(null);
            tx.onabort = () => reject(tx.error || new Error('aborted'));
            try {
                tx.objectStore(store).delete(IDBKeyRange.bound(folder + '/', folder + '0', false, true));
            } catch (e) {
                tx.abort();
                reject(e);
            }
        })""",
    )

/**
 * The record keys (`<64 hex>/<64 hex>`, anything else is left out) of a folder, or of all folders if [folder] is empty,
 * as one string separated by line feeds (a record key has none).
 */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbRecordKeys(
    db: JsAny,
    store: String,
    folder: String,
): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const tx = db.transaction(store, 'readonly');
            const objects = tx.objectStore(store);
            const request = folder === ''
                ? objects.getAllKeys()
                : objects.getAllKeys(IDBKeyRange.bound(folder + '/', folder + '0', false, true));
            tx.oncomplete = () => resolve(
                request.result.filter((k) => typeof k === 'string' && /^[0-9a-f]{64}\/[0-9a-f]{64}$/.test(k)).join('\n'),
            );
            tx.onabort = () => reject(tx.error || new Error('aborted'));
        })""",
    )

/** How many keys the store holds, whatever they look like, as text. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbCountRecords(
    db: JsAny,
    store: String,
): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const tx = db.transaction(store, 'readonly');
            const request = tx.objectStore(store).count();
            tx.oncomplete = () => resolve(String(request.result));
            tx.onabort = () => reject(tx.error || new Error('aborted'));
        })""",
    )

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbClearRecords(
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

/**
 * The [RecordStore] in the browser's IndexedDB (ADR-0017): its own database `gifitup-history`, one object store, key
 * `"<folder>/<name>"` (both checked with [isRecordName] first), value `Uint8Array`. It never touches the `gifitup`
 * database of ADR-0011 or its versioning.
 *
 * A write is one `readwrite` transaction with strict durability that resolves on `complete`: as durable as the browser
 * makes it when it returns (`durability: 'strict'` is a request the browser may weaken, and the browser may evict the
 * whole origin's storage under pressure, as ADR-0011 says for the identity; the store loses its history together with it).
 * The adversary is a reader of the browser profile, never the server.
 * [isEmpty] is `true` only if the object store holds **no key at all**, malformed or foreign ones included, and an error
 * is thrown, never `true`. Every failure is a [RecordStoreException] with no message and no cause.
 *
 * It does not take the Web Lock itself: PR 4 opens it only after `claimWebDeviceStorage()` returned a non-null
 * `DeviceStorage`, so one tab at a time uses it. [databaseName] is a parameter for the tests only.
 */
class IndexedDbRecordStore internal constructor(
    private val databaseName: String,
) : RecordStore {
    internal constructor() : this(DATABASE_NAME)

    private val opening = Mutex()
    private var db: JsAny? = null
    private var deleted = false

    override suspend fun put(
        folder: String,
        name: String,
        bytes: ByteArray,
    ) {
        validate(folder, name)
        settle(idbPutRecord(connection(), RECORDS, "$folder/$name", bytes.toUint8Array()))
    }

    override suspend fun get(
        folder: String,
        name: String,
    ): ByteArray? {
        validate(folder, name)
        val value = settle(idbGetRecord(connection(), RECORDS, "$folder/$name")) ?: return null
        if (!isUint8Array(value)) throw RecordStoreException()
        return value.unsafeCast<Uint8Array>().toByteArray()
    }

    override suspend fun list(folder: String): List<String> {
        validate(folder)
        return keys(folder).map { it.substringAfter('/') }
    }

    override suspend fun delete(
        folder: String,
        name: String,
    ) {
        validate(folder, name)
        settle(idbDeleteRecord(connection(), RECORDS, "$folder/$name"))
    }

    override suspend fun deleteFolder(folder: String) {
        validate(folder)
        settle(idbDeleteFolder(connection(), RECORDS, folder))
    }

    override suspend fun folders(): List<String> = keys("").map { it.substringBefore('/') }.distinct()

    override suspend fun isEmpty(): Boolean = text(settle(idbCountRecords(connection(), RECORDS))) == "0"

    override suspend fun deleteAll() {
        settle(idbClearRecords(connection(), RECORDS))
    }

    /**
     * Deletes the whole database (the wipe): this connection is closed first, so it does not block its own delete; a
     * connection of another tab would, which is a [RecordStoreException]. This instance is finished afterwards: every later
     * call is a [RecordStoreException], so nothing recreates the database behind the wipe.
     */
    internal suspend fun deleteDatabase() {
        opening.withLock {
            deleted = true
            db?.let { closeRecords(it) }
            db = null
        }
        settle(idbDeleteDatabase(databaseName))
    }

    /** The record keys of [folder] (all folders if empty), as text lines turned into keys. */
    private suspend fun keys(folder: String): List<String> {
        val joined = text(settle(idbRecordKeys(connection(), RECORDS, folder)))
        return if (joined.isEmpty()) emptyList() else joined.split('\n')
    }

    /** A connection that closed itself on `versionchange` is opened again, like [WebDatabase]'s. */
    private suspend fun connection(): JsAny =
        opening.withLock {
            // After the wipe this instance is done: a late call must not recreate the empty database.
            if (deleted) throw RecordStoreException()
            val open = db
            if (open != null && !isClosedRecords(open)) {
                open
            } else {
                (settle(idbOpenRecords(databaseName, RECORDS)) ?: throw RecordStoreException()).also { db = it }
            }
        }

    private suspend fun settle(promise: Promise<JsAny?>): JsAny? = settled(promise) { RecordStoreException() }

    private fun text(value: JsAny?): String = value?.unsafeCast<JsString>()?.toString() ?: throw RecordStoreException()

    private fun validate(
        folder: String,
        name: String? = null,
    ) {
        if (!isRecordName(folder) || (name != null && !isRecordName(name))) throw RecordStoreException()
    }
}
