package app.gifitup.shared.domain.origin

import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.storage.IdentityStore
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.LocalStore
import app.gifitup.shared.domain.auth.DeviceLock

/**
 * Which server the app talks to and signs for (ADR-0013). The origin is the app's own configuration and never a value
 * the server or a link sent. The owner chose to make it editable (Settings, #17 plan, 2026-10-21), so the rules are:
 * - **An identity decides.** With any identity record (pending, registered, or unreadable), the origin is the
 *   identity's own; the setting only applies while there is none. A lost or edited setting can never turn into
 *   `OTHER_ORIGIN` and a wipe offer (#17 plan review, SEC-2).
 * - **Changing needs no identity**, is checked again under the [DeviceLock] (so it cannot race a registration), and
 *   accepts only a canonical `https` origin after exactly one normalisation: trim ASCII whitespace, lowercase. Nothing
 *   is "fixed up".
 * - The setting is a [LocalStore] record, so the wipe's `deleteAll` resets it to the default (ADR-0011).
 * After a change the caller rebuilds everything that holds the old origin (API, `DeviceAuth`, session holder).
 *
 * Storage failures are not swallowed: [current] and [set] throw [LocalStateException] (`STORAGE`, or `KEY_UNAVAILABLE`
 * from the identity store). The screens map it with the same closed texts as the auth use cases (#17 PR 2).
 */
class ServerOriginSetting(
    private val store: LocalStore,
    private val identities: IdentityStore,
    private val lock: DeviceLock,
    val default: String,
) {
    init {
        require(ApiEndpoint.isCanonicalOrigin(default)) { "the default origin must be canonical" }
    }

    sealed interface Current {
        /** [custom] is true when the origin is not the build's default: the UI shows a banner on every screen. */
        class Origin(
            val origin: String,
            val custom: Boolean,
        ) : Current

        /** An identity record exists but can't be read: the origin is unknown, and only a wipe fixes it. */
        data object Unreadable : Current
    }

    sealed interface SetResult {
        class Changed(
            val origin: String,
        ) : SetResult

        /** Not a canonical `https` origin after trimming and lowercasing. */
        data object Invalid : SetResult

        /** A device with an identity stays on its origin; only a wipe frees it. */
        data object HasIdentity : SetResult
    }

    suspend fun current(): Current {
        val identity = runCatchingDamaged { identities.load() }
        return when {
            identity.damaged -> Current.Unreadable
            identity.value != null -> originOf(checkNotNull(identity.value).origin)
            else -> originOf(stored() ?: default)
        }
    }

    suspend fun set(raw: String): SetResult {
        val origin = raw.trim { it == ' ' || it in '\t'..'\r' }.lowercase()
        if (!ApiEndpoint.isCanonicalOrigin(origin)) return SetResult.Invalid
        return lock.holding {
            val identity = runCatchingDamaged { identities.load() }
            if (identity.damaged || identity.value != null) {
                SetResult.HasIdentity
            } else {
                store.putAll(mapOf(KEY to origin.takeUnless { it == default }?.encodeToByteArray()))
                SetResult.Changed(origin)
            }
        }
    }

    private fun originOf(origin: String) = Current.Origin(origin, custom = origin != default)

    /** A stored origin that is not canonical (a damaged record) counts as none. */
    private suspend fun stored(): String? = store.get(KEY)?.decodeToString()?.takeIf(ApiEndpoint::isCanonicalOrigin)

    private class Loaded<T>(
        val value: T?,
        val damaged: Boolean,
    )

    /** Only `DAMAGED` is a state the origin logic handles; a storage failure still propagates. */
    private suspend fun <T> runCatchingDamaged(load: suspend () -> T?): Loaded<T> =
        try {
            Loaded(load(), damaged = false)
        } catch (e: LocalStateException) {
            if (e.problem == LocalStateException.Problem.DAMAGED) Loaded(null, damaged = true) else throw e
        }

    private companion object {
        const val KEY = "server-origin"
    }
}
