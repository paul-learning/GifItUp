package app.gifitup.mls.storage

import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.domain.auth.DeviceStorage
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlin.js.Promise

/**
 * Asks for the exclusive Web Locks lock [name] without waiting (`ifAvailable`) and, if granted, holds it until the
 * page closes. Resolves to whether this page got it; no Web Locks support, or any error, is "no" (fail closed).
 */
@Suppress("UnusedParameter") // Read by the js() body, which detekt can't see.
internal fun requestPageLock(name: String): Promise<JsBoolean> =
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
suspend fun claimWebDeviceStorage(): DeviceStorage? =
    claimWebDeviceStorage(DATABASE_NAME, CLAIM_ATTEMPTS) { requestPageLock(LOCK_NAME).await<JsBoolean>().toBoolean() }

/**
 * Asks [tryLock] up to [attempts] times, [RETRY_DELAY_MS] apart. A page that reloads quickly can ask before the
 * browser has released the old page's lock, so the first "no" isn't final (#112 SEC-2).
 */
internal suspend fun claimWebDeviceStorage(
    databaseName: String,
    attempts: Int,
    tryLock: suspend () -> Boolean,
): DeviceStorage? {
    var held = false
    for (attempt in 1..attempts) {
        held = tryLock()
        if (held) break
        if (attempt < attempts) delay(RETRY_DELAY_MS)
    }
    if (!held) return null
    val db = WebDatabase(databaseName)
    val aead = WebCryptoWrappingAead(db)
    val store = IndexedDbLocalStore(db)
    // Built here, after the page lock is held, and nowhere else: a tab that lost the lock never touches the history (ADR-0017).
    val records = IndexedDbRecordStore()
    return DeviceStorage(
        store,
        WebDeviceData(db, aead, records),
        records,
        { origin, identities -> EnvelopeSnapshotKeyStore(aead, store, identities, origin) },
        { origin -> EnvelopeHistoryKeyStore(aead, store, origin) },
    )
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
private const val CLAIM_ATTEMPTS = 10
internal const val RETRY_DELAY_MS = 200L
private const val DATABASE_NAME = "gifitup"
