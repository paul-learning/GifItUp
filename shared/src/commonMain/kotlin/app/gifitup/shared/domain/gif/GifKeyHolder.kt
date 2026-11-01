package app.gifitup.shared.domain.gif

import app.gifitup.shared.data.network.GifProviderKey
import kotlin.concurrent.Volatile

/**
 * Where the GIF provider's key lives while the app runs (ADR-0003, #23): **in memory only**, never persisted, never logged.
 * "Memory only" means never written: a string can't be wiped on the JVM or in JS. It exposes only [current]: nothing here,
 * and nothing that takes it, can fetch a key, so the picker can't make our server learn when someone is about to send a
 * GIF (the fetch is [GifKeyRefresher]'s, on its own schedule). [clear] (logout, wipe) also ends every call that started
 * before it: the provider drops what such a call brings back.
 */
class GifKeyHolder {
    @Volatile
    private var key: GifProviderKey? = null

    @Volatile
    private var epoch = 0

    fun current(): GifProviderKey? = key

    /** Changes with every [clear]; a call that sees another value than it started with is over. */
    fun epoch(): Int = epoch

    fun set(value: GifProviderKey) {
        key = value
    }

    fun clear() {
        epoch++
        key = null
    }

    override fun toString(): String = "GifKeyHolder"
}
