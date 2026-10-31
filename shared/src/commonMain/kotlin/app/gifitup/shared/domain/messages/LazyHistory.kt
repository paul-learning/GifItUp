package app.gifitup.shared.domain.messages

import app.gifitup.shared.data.crypto.MlsException
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.data.storage.RecordStoreException
import app.gifitup.shared.domain.chat.ReadMarkStore
import app.gifitup.shared.domain.contacts.KeyBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Builds the [EncryptedMessageStore] of the registered identity: the history key, the cipher, the records (ADR-0017). */
fun interface HistoryOpener {
    /** Throws what the pieces throw (`LocalStateException`, `MlsException`, `RecordStoreException`); [LazyHistory] sorts it. */
    suspend fun open(): EncryptedMessageStore
}

/**
 * The history the app uses (ADR-0017): a [MessageStore] and [ReadMarkStore] that open the [EncryptedMessageStore] on
 * first use and own its whole life. **Every call, and the open, the close and the reset, runs under one mutex**, so there
 * is one store over the records at a time (two would keep separate caches and write duplicate arrival numbers), and a
 * [close] or [reset] waits for the calls that are running and nothing runs after it.
 *
 * States, with the failure that leads to each:
 * - **Open** after a successful open, until [close] or [reset].
 * - **Failed sticky** for a history key that is missing, does not open or is damaged ([HistoryStoreException.Kind.KEY]) and
 *   for a dead cipher module ([HistoryStoreException.Kind.CIPHER], on the web until reload): every call fails with that
 *   kind at once until [retry] or [reset], so a broken key is not asked again on every refresh and "reset" is offered
 *   deliberately, never as a reflex to a transient error.
 * - **Cooling down** after a storage failure ([HistoryStoreException.Kind.STORAGE]): the next open is tried after
 *   [coolDownMillis] or on [retry]; calls in between fail with `STORAGE` without touching the disk.
 * - **Closed** after [close], for good: every call fails with [HistoryStoreException.Kind.CLOSED]; the store, its cache and
 *   the history key in memory are gone.
 *
 * One instance per `DeviceStorage`: the wipe closes this instance, and an open that another instance had begun before the wipe
 * could still save an empty wrapped history key after it (no data; the next new registration clears it).
 */
@Suppress("TooManyFunctions") // The two ports of the history plus its lifecycle; each is a one-line delegation.
class LazyHistory(
    private val opener: HistoryOpener,
    private val nowMillis: () -> Long,
    private val coolDownMillis: Long = DEFAULT_COOL_DOWN_MILLIS,
) : HistoryAccess,
    ReadMarkStore {
    private val mutex = Mutex()
    private var store: EncryptedMessageStore? = null
    private var sticky: HistoryStoreException.Kind? = null
    private var retryAt = 0L
    private var closed = false

    override suspend fun insert(message: ChatMessage): Boolean = gated { it.insert(message) }

    override suspend fun setState(
        groupId: KeyBytes,
        reference: KeyBytes,
        state: DeliveryState,
        seq: Long?,
    ) = gated { it.setState(groupId, reference, state, seq) }

    override suspend fun messages(groupId: KeyBytes): List<ChatMessage> = gated { it.messages(groupId) }

    override suspend fun forget(groupId: KeyBytes) = gated { it.forget(groupId) }

    override suspend fun newestSeen(group: KeyBytes): KeyBytes? = gated { it.newestSeen(group) }

    override suspend fun markSeen(
        group: KeyBytes,
        reference: KeyBytes,
    ) = gated { it.markSeen(group, reference) }

    /** How many records of the group could not be read (0 for a history that cannot be opened). */
    override suspend fun unreadable(groupId: KeyBytes): Int = gated { it.unreadable(groupId) }

    override suspend fun usable(): Boolean =
        mutex.withLock {
            try {
                storeLocked()
                true
            } catch (_: HistoryStoreException) {
                false
            }
        }

    /** The user's "try again": forgets a sticky failure and the cool-down; the next call opens again. */
    override suspend fun retry() {
        mutex.withLock {
            sticky = null
            retryAt = 0L
        }
    }

    /**
     * Ends the history for good (the wipe, the end of the session): waits for running calls, closes the store (its cache and
     * the history key in memory go), and every later call fails with `CLOSED`. Safe to call twice.
     */
    suspend fun close() {
        mutex.withLock {
            closed = true
            closeStore()
        }
    }

    /**
     * "Reset history" (ADR-0017): under the gate, the open store is closed first (its cache and key in memory go), then
     * [delete] removes the records and the key entry, in that order, and the next call opens a fresh history. A failing
     * [delete] throws and leaves the history closed-but-reopenable: the next call finds what is left (a missing key with
     * records is the sticky `KEY` again). A closed history cannot be reset. [delete] runs under the gate, so it must never take
     * the `DeviceLock`: the lock order is device lock, then gate, and the reverse would close a cycle.
     */
    suspend fun reset(delete: suspend () -> Unit) {
        mutex.withLock {
            if (closed) throw HistoryStoreException(HistoryStoreException.Kind.CLOSED)
            closeStore()
            sticky = null
            retryAt = 0L
            delete()
        }
    }

    private suspend fun <T> gated(block: suspend (EncryptedMessageStore) -> T): T = mutex.withLock { block(storeLocked()) }

    private suspend fun storeLocked(): EncryptedMessageStore {
        refusal()?.let { throw HistoryStoreException(it) }
        return store ?: openStore()
    }

    /** Why a call is refused without touching the disk, or `null` if it may go on (open, or ready to open). */
    private fun refusal(): HistoryStoreException.Kind? =
        when {
            closed -> HistoryStoreException.Kind.CLOSED
            store != null -> null
            sticky != null -> sticky
            nowMillis() < retryAt -> HistoryStoreException.Kind.STORAGE
            else -> null
        }

    @Suppress("TooGenericExceptionCaught") // Anything the pieces throw is sorted into the closed kinds; nothing leaks out.
    private suspend fun openStore(): EncryptedMessageStore {
        val kind =
            try {
                return opener.open().also { store = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LocalStateException) {
                if (e.problem == LocalStateException.Problem.STORAGE) HistoryStoreException.Kind.STORAGE else HistoryStoreException.Kind.KEY
            } catch (_: RecordStoreException) {
                HistoryStoreException.Kind.STORAGE
            } catch (_: MlsException) {
                HistoryStoreException.Kind.CIPHER
            } catch (_: IllegalStateException) {
                // Not a registered identity of this origin: a defect of the caller. Sticky like a dead cipher; only a
                // user's retry asks again.
                HistoryStoreException.Kind.CIPHER
            } catch (_: Throwable) {
                // Anything else the pieces throw, a failed module load on the web included (a JsException is a Throwable, not
                // an Exception): sorted as the cipher's, sticky until a retry. A cancellation has been rethrown above.
                HistoryStoreException.Kind.CIPHER
            }
        if (kind == HistoryStoreException.Kind.STORAGE) retryAt = nowMillis() + coolDownMillis else sticky = kind
        throw HistoryStoreException(kind)
    }

    private suspend fun closeStore() {
        // Dropped only after the close went through: a close that throws leaves the store to be closed by the retry, so the
        // history key never stays in memory behind a "closed" history.
        store?.close()
        store = null
    }

    private companion object {
        const val DEFAULT_COOL_DOWN_MILLIS = 5_000L
    }
}
