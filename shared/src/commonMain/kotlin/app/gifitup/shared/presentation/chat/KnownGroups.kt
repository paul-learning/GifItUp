package app.gifitup.shared.presentation.chat

import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The groups this device knows, for the live socket's "is this hint about one of my groups?" question (#27, #274 CR-2). The
 * answer comes from a snapshot, so a hint stream cannot make the device list its groups once per hint: a group in the
 * snapshot is known without any work, and the snapshot is rebuilt at most once per [minReloadMillis] for an id it does not
 * hold (a new group arrives with a Welcome, which calls [forget]).
 */
class KnownGroups(
    private val load: suspend () -> List<KeyBytes>,
    private val nowMillis: () -> Long,
    private val minReloadMillis: Long = MIN_RELOAD_MILLIS,
) {
    private val lock = Mutex()
    private var ids: Set<KeyBytes> = emptySet()
    private var loadedAt = NEVER

    suspend fun contains(id: KeyBytes): Boolean = lock.withLock { ids.contains(id) || (reloadIfDue() && ids.contains(id)) }

    /** Every id in the snapshot (reloaded if it is older than [minReloadMillis]). */
    suspend fun all(): List<KeyBytes> =
        lock.withLock {
            reloadIfDue()
            ids.toList()
        }

    /** Drops the snapshot's age: the next question reloads it (a Welcome joined a group). */
    suspend fun forget() = lock.withLock { loadedAt = NEVER }

    private suspend fun reloadIfDue(): Boolean {
        val now = nowMillis()
        if (loadedAt != NEVER && now - loadedAt < minReloadMillis) return false
        ids = load().toSet()
        loadedAt = now
        return true
    }

    companion object {
        const val MIN_RELOAD_MILLIS = 5_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
