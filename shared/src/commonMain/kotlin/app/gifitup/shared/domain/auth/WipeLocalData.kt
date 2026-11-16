package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.network.Session
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** The platform side of [WipeLocalData] (ADR-0011): `:mls` implements it over the Keystore or WebCrypto and the store. */
interface DeviceData {
    /**
     * Deletes the platform key that wraps the snapshot key; a missing key is no error, so a retried wipe completes
     * (#108 SEC-18). After it returns, no stored or leftover wrapped key can be
     * opened again; on Android this is what erases the data, since copies of old files can remain on flash (#16 SEC-3).
     */
    suspend fun destroyWrappingKey()

    /**
     * Deletes every record (identity, wrapped key, counters) and the store itself where the platform allows; deleting
     * what's already gone is no error. Throws if it isn't done, for example while another connection keeps the web
     * database open (#16 SEC-8). A wipe that stops after the key is destroyed leaves records no key opens
     * (`KEY_UNAVAILABLE` for good), so the app offers the wipe again from there (#17).
     */
    suspend fun deleteAll()
}

/**
 * Closes the history the app has open (ADR-0017): its decrypted cache and the history key in memory go, and nothing is
 * stored or read through it afterwards. The wipe calls it before the wrapping key is destroyed.
 */
fun interface HistoryCloser {
    suspend fun close()

    companion object {
        val None: HistoryCloser = HistoryCloser { }
    }
}

/**
 * "Wipe all local data" (ADR-0011): this device forgets its account. It doesn't delete the account on the server (the
 * owner's admin command, ADR-0010); a wiped device registers again with a new invite.
 *
 * The order matters (#16 SEC-3, SEC-8; reversed for the network by #282, SEC-M1-4/SEC-M2-6): **erase first, end the session
 * last**. The history is closed first of all and outside the lock (its cache and the key in memory go, nothing is written
 * afterwards); then, under the device lock and the contact book's lock, every open client is closed so Rust and wasm drop the
 * key and the MLS state, the wrapping key is destroyed so nothing stored can be decrypted any more, and the records are deleted.
 * Only then does the session end on the server, best effort and bounded by [LOGOUT_TIMEOUT_MILLIS]: the logout needs nothing but
 * the in-memory token, so the erase never waits for the network and a stalling server can't delay it or hold the lock. A server
 * session that isn't ended this way (the logout failed, timed out or never left, as when a web page reloads) expires on its
 * own (ADR-0009); the token is stored nowhere. A wipe that stops part-way sends no logout (the device may still be usable) and a
 * retry runs everything again (#108 SEC-18). The caller drops the [Session] and, on the web, reloads the page; the web wipe is
 * best effort against what the browser leaves on disk. A caller that wants its screens signed out the moment the data is gone
 * calls [eraseLocal] and then [endSession] itself; [invoke] is both.
 */
class WipeLocalData(
    private val data: DeviceData,
    private val logOut: LogOut,
    private val lock: DeviceLock,
    private val history: HistoryCloser,
    /** The contact book's read-modify-write lock of this device (`DeviceStorage.contactBookLock`): see [eraseLocal]. */
    private val contactBook: Mutex = Mutex(),
) {
    suspend operator fun invoke(
        session: Session?,
        openClients: Collection<MlsClient>,
    ) {
        eraseLocal(openClients)
        endSession(session)
    }

    /**
     * Closes the history, the clients and deletes the keys and records; the network is not touched. The contact book's lock is
     * taken after the device lock (nothing takes them the other way round), so a book change that is saving can't write its
     * record after the records are gone (security plan review SEC-1).
     */
    suspend fun eraseLocal(openClients: Collection<MlsClient>) {
        // First, and **outside** the device lock: a history that is still being opened takes the device lock itself (the
        // history key), so waiting for it while holding the lock would deadlock. Closing is terminal and waits only for the
        // history's own running calls; whatever a running sync does afterwards through it fails with `CLOSED`. A close that
        // throws stops the wipe before the key is destroyed, and a retry completes (nothing here is half-done).
        history.close()
        lock.holding {
            contactBook.withLock {
                openClients.forEach { runCatching { it.close() } }
                data.destroyWrappingKey()
                data.deleteAll()
            }
        }
    }

    /**
     * Ends the server session with the token the caller still holds: best effort, at most [LOGOUT_TIMEOUT_MILLIS], every
     * failure swallowed and nothing logged (the token and any answer stay out of every message). Cancellation of the caller is not
     * swallowed. Outside every lock.
     */
    suspend fun endSession(session: Session?) {
        if (session == null) return
        try {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MILLIS) { logOut(session) }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // A failed logout leaves a token that expires on its own and isn't stored anywhere.
        }
    }

    companion object {
        /** Well under the API's request timeouts, and not configurable by anything the server says. */
        const val LOGOUT_TIMEOUT_MILLIS = 3_000L
    }
}
