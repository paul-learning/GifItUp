package app.gifitup.shared.domain.auth

import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.network.Session

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
 * "Wipe all local data" (ADR-0011): this device forgets its account. It doesn't delete the account on the server (the
 * owner's admin command, ADR-0010); a wiped device registers again with a new invite.
 *
 * The order matters (#16 SEC-3, SEC-8): end the session while it still works, close every client so Rust and wasm drop
 * the key and the MLS state, destroy the wrapping key so nothing stored can be decrypted any more, then delete the
 * records. The caller then drops the [Session] and, on the web, reloads the page; the web wipe is best effort against
 * what the browser leaves on disk.
 */
class WipeLocalData(
    private val data: DeviceData,
    private val logOut: LogOut,
    private val lock: DeviceLock,
) {
    suspend operator fun invoke(
        session: Session?,
        openClients: Collection<MlsClient>,
    ) = lock.holding {
        // Best effort: a failed logout leaves a token that expires on its own and isn't stored anywhere.
        if (session != null) runCatching { logOut(session) }
        openClients.forEach { runCatching { it.close() } }
        data.destroyWrappingKey()
        data.deleteAll()
    }
}
