package app.gifitup.shared.data.storage

import app.gifitup.shared.domain.contacts.ContactBook
import app.gifitup.shared.domain.contacts.ContactLoad
import app.gifitup.shared.domain.contacts.ContactStore
import kotlinx.coroutines.yield

/** A [ContactStore] that suspends in both calls, so a missing lock around load-decide-save shows up as a lost update. */
class InMemoryContactStore(
    var book: ContactBook? = ContactBook(),
) : ContactStore {
    var failSaves = false
    var saves = 0

    override suspend fun load(): ContactLoad {
        val current = book
        yield()
        return if (current == null) ContactLoad.Unavailable else ContactLoad.Book(current)
    }

    override suspend fun save(book: ContactBook) {
        yield()
        if (failSaves) throw LocalStateException(LocalStateException.Problem.STORAGE)
        this.book = book
        saves++
    }
}
