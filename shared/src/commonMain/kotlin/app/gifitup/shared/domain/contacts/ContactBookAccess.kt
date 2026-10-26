package app.gifitup.shared.domain.contacts

import app.gifitup.shared.domain.contacts.ContactBook
import app.gifitup.shared.domain.contacts.ContactLoad
import app.gifitup.shared.domain.contacts.ContactStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

sealed interface BookUpdate<out T> {
    class Done<T>(
        val value: T,
    ) : BookUpdate<T>

    /** The book can't be read (ADR-0014); nothing was changed. */
    data object Unavailable : BookUpdate<Nothing>

    /** The new book could not be saved; nothing was changed. */
    data object NotSaved : BookUpdate<Nothing>
}

/**
 * The one way to read and change the [ContactBook]: every change loads, decides and saves under one lock, so two
 * callers (a list refresh and a claim) can't lose each other's pin. **The lock is never held across a request** (a slow
 * or malicious server would stall every other use): callers fetch first, then decide inside [update]. [clock] is the
 * time shown in the book only.
 */
class ContactBookAccess(
    private val store: ContactStore,
    private val clock: () -> Long,
) {
    private val lock = Mutex()

    suspend fun read(): ContactLoad = lock.withLock { store.load() }

    internal suspend fun <T> update(change: (book: ContactBook, now: Long) -> Pair<ContactBook, T>): BookUpdate<T> =
        lock.withLock {
            when (val loaded = store.load()) {
                ContactLoad.Unavailable -> {
                    BookUpdate.Unavailable
                }

                is ContactLoad.Book -> {
                    val (book, value) = change(loaded.book, clock())
                    if (saved(book)) BookUpdate.Done(value) else BookUpdate.NotSaved
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // A platform store fails with platform types; all of them mean "not saved".
    private suspend fun saved(book: ContactBook): Boolean =
        try {
            store.save(book)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            false
        }
}
