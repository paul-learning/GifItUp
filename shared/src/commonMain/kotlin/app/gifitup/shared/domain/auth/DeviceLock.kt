package app.gifitup.shared.domain.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock for everything that reads or changes this device's identity: registration, discard, login and wipe
 * (#108 SEC-15). A wipe can't run while a registration is still saving its key and identity, and can't miss a client
 * a login is still holding. Build one per device and hand the same instance to every use case; across tabs and
 * processes the platform's single-instance rule applies (ADR-0011). Not reentrant: a use case never calls another one
 * while holding it.
 */
class DeviceLock {
    private val mutex = Mutex()

    internal suspend fun <T> holding(block: suspend () -> T): T = mutex.withLock { block() }
}
