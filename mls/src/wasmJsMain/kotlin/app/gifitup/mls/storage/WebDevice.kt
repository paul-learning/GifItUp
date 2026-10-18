package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.domain.auth.DeviceStorage
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Asks for the exclusive Web Locks lock [name] without waiting (`ifAvailable`) and, if granted, holds it until the
 * page closes. Resolves to whether this page got it; no Web Locks support, or any error, is "no" (fail closed).
 */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
private fun requestPageLock(name: String): Promise<JsBoolean> =
    js(
        """new Promise((resolve) => {
            try {
                if (!(navigator.locks && typeof navigator.locks.request === 'function')) { resolve(false); return; }
                navigator.locks.request(name, { mode: 'exclusive', ifAvailable: true }, (lock) => {
                    if (lock === null) { resolve(false); return undefined; }
                    resolve(true);
                    return new Promise(() => {});
                }).catch(() => resolve(false));
            } catch (e) {
                resolve(false);
            }
        })""",
    )

/** `navigator.storage.persist()`; anything but a grant (no support, a refusal, an error) is `false`. */
private fun requestPersist(): Promise<JsBoolean> =
    js(
        """Promise.resolve()
            .then(() => (navigator.storage && typeof navigator.storage.persist === 'function') ? navigator.storage.persist() : false)
            .then((granted) => granted === true, () => false)""",
    )

/**
 * Claims this browser's device storage for this page (ADR-0011): only the page that gets the exclusive
 * `gifitup-device` lock opens the database; any other tab gets `null`, opens no storage and tells the user the app is
 * open elsewhere. Call it once, before building anything that touches storage.
 */
suspend fun claimWebDeviceStorage(): DeviceStorage? = claimWebDeviceStorage(LOCK_NAME, DATABASE_NAME)

internal suspend fun claimWebDeviceStorage(
    lockName: String,
    databaseName: String,
): DeviceStorage? {
    if (!requestPageLock(lockName).await<JsBoolean>().toBoolean()) return null
    val db = WebDatabase(databaseName)
    val aead = WebCryptoWrappingAead(db)
    val store = IndexedDbLocalStore(db)
    return DeviceStorage(store, WebDeviceData(db, aead)) { origin, identities ->
        EnvelopeSnapshotKeyStore(aead, store, identities, origin)
    }
}

/**
 * Asks the browser to keep this origin's storage under storage pressure (`navigator.storage.persist()`) and records
 * the answer in [store] under [PERSISTED_KEY] (1 granted, 0 not), so the app can tell the user that the browser may
 * evict the identity (ADR-0011, #108 SEC-19). Called at registration (#17), not at start: Firefox asks the user.
 */
suspend fun requestPersistentStorage(store: LocalStore): Boolean {
    val granted = requestPersist().await<JsBoolean>().toBoolean()
    store.putAll(mapOf(PERSISTED_KEY to byteArrayOf(if (granted) 1 else 0)))
    return granted
}

const val PERSISTED_KEY = "storage-persisted"
private const val LOCK_NAME = "gifitup-device"
private const val DATABASE_NAME = "gifitup"
