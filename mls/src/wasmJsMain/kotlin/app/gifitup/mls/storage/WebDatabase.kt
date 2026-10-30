@file:Suppress("TooManyFunctions") // One small js() helper per IndexedDB call; they belong together.

package app.gifitup.mls.storage

import app.gifitup.mls.toByteArray
import app.gifitup.mls.toUint8Array
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStateException.Problem.DAMAGED
import app.gifitup.shared.data.storage.LocalStateException.Problem.STORAGE
import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.auth.DeviceData
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// IndexedDB through small JS helpers. Each helper returns a promise and does all its work inside it, so a
// synchronous throw (a closed connection, a value that can't be cloned) is a rejection too; [settled] turns a
// rejection into a value, so no JS error reaches Kotlin as an exception (ADR-0011, #108 SEC-13).

/** What [settle] resolves to: the promise's value, or the name of the error it rejected with. */
internal external interface Settled : JsAny {
    val failed: Boolean
    val value: JsAny?
    val error: String?
}

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun settle(promise: Promise<JsAny?>): Promise<Settled> =
    js(
        """promise.then(
            (value) => ({ failed: false, value: value === undefined ? null : value }),
            (e) => ({ failed: true, error: (e && typeof e.name === 'string') ? e.name : 'Error' }),
        )""",
    )

/** Awaits [promise]; a rejection becomes [onError] of the error's name, `STORAGE` by default. */
internal suspend fun settled(
    promise: Promise<JsAny?>,
    onError: (String) -> Throwable = { LocalStateException(STORAGE) },
): JsAny? {
    val result = settle(promise).await<Settled>()
    if (result.failed) throw onError(result.error ?: "Error")
    return result.value
}

/** Opens (and on first use creates) the database; the connection closes itself when another one wants to upgrade or delete it. */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbOpen(name: String): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const request = indexedDB.open(name, 1);
            request.onupgradeneeded = () => {
                request.result.createObjectStore('data');
                request.result.createObjectStore('keys');
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

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
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

/**
 * Writes all entries in one `readwrite` transaction with strict durability and resolves only on `complete`, so
 * the write is atomic and durable when it resolves. A `null` value deletes its key. If queueing any entry throws,
 * the transaction is aborted, so the entries queued before it are never committed.
 */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
internal fun idbPutAll(
    db: JsAny,
    store: String,
    keys: JsArray<JsString>,
    values: JsArray<JsAny?>,
): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const tx = db.transaction(store, 'readwrite', { durability: 'strict' });
            tx.oncomplete = () => resolve(null);
            tx.onabort = () => reject(tx.error || new Error('aborted'));
            try {
                const objects = tx.objectStore(store);
                for (let i = 0; i < keys.length; i++) {
                    if (values[i] === null) objects.delete(keys[i]); else objects.put(values[i], keys[i]);
                }
            } catch (e) {
                tx.abort();
                reject(e);
            }
        })""",
    )

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun isClosed(db: JsAny): Boolean = js("db.gifitupClosed === true")

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbClose(db: JsAny): JsAny? = js("db.close()")

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun idbDelete(name: String): Promise<JsAny?> =
    js(
        """new Promise((resolve, reject) => {
            const request = indexedDB.deleteDatabase(name);
            request.onsuccess = () => resolve(null);
            request.onerror = () => reject(request.error);
            request.onblocked = () => reject(new Error('blocked'));
        })""",
    )

private fun emptyArray(): JsArray<JsAny?> = js("[]")

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun push(
    array: JsArray<JsAny?>,
    value: JsAny?,
): Int = js("array.push(value)")

@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
internal fun isUint8Array(value: JsAny?): Boolean = js("value instanceof Uint8Array")

/**
 * The device's one IndexedDB database (ADR-0011): object store `data` for the [LocalStore] entries, `keys` for the
 * wrapping key. The connection opens on first use. Only the tab that holds the device lock builds one
 * ([claimWebDeviceStorage]).
 */
internal class WebDatabase(
    private val name: String,
) {
    private val opening = Mutex()
    private var db: JsAny? = null

    /** A connection that closed itself on `versionchange` (the database was deleted or upgraded) is opened again (#112 SEC-1). */
    private suspend fun connection(): JsAny = opening.withLock { db?.takeUnless(::isClosed) ?: settled(idbOpen(name))!!.also { db = it } }

    suspend fun get(
        store: String,
        key: String,
    ): JsAny? = settled(idbGet(connection(), store, key))

    suspend fun putAll(
        store: String,
        entries: Map<String, JsAny?>,
    ) {
        val keys = emptyArray()
        val values = emptyArray()
        entries.forEach { (key, value) ->
            push(keys, key.toJsString())
            push(values, value)
        }
        @Suppress("UNCHECKED_CAST") // Every element is a JsString.
        settled(idbPutAll(connection(), store, keys as JsArray<JsString>, values))
    }

    /** Closes this connection and deletes the database; `blocked` (another connection stays open) is `STORAGE`. */
    suspend fun delete() {
        opening.withLock {
            db?.let(::idbClose)
            db = null
        }
        settled(idbDelete(name))
    }

    companion object {
        const val DATA = "data"
        const val KEYS = "keys"
    }
}

/** [LocalStore] over the database's `data` object store; values are stored as `Uint8Array`. */
internal class IndexedDbLocalStore(
    private val db: WebDatabase,
) : LocalStore {
    override suspend fun get(key: String): ByteArray? {
        val value = db.get(WebDatabase.DATA, key) ?: return null
        if (!isUint8Array(value)) throw LocalStateException(DAMAGED)
        return value.unsafeCast<Uint8Array>().toByteArray()
    }

    override suspend fun putAll(entries: Map<String, ByteArray?>) =
        db.putAll(WebDatabase.DATA, entries.mapValues { it.value?.toUint8Array() })
}

/** The wipe's platform side: destroy the wrapping key, then delete the database (ADR-0011). */
internal class WebDeviceData(
    private val db: WebDatabase,
    private val aead: WrappingAead,
    private val records: IndexedDbRecordStore,
) : DeviceData {
    override suspend fun destroyWrappingKey() = aead.destroy()

    /**
     * The history database first, through the record store's own connection (which closes itself on `versionchange`), then
     * the device database: if the history cannot be deleted (another connection keeps it open) the identity stays and the
     * wipe is offered again. The web wipe is best effort (ADR-0011): the browser may keep remnants on disk.
     */
    override suspend fun deleteAll() {
        try {
            records.deleteDatabase()
        } catch (_: RecordStoreException) {
            throw LocalStateException(STORAGE)
        }
        db.delete()
    }
}
