package app.gifitup.shared.domain.contacts

import app.gifitup.shared.data.storage.IdentityStore

/** What a store found: the book, or nothing usable (ADR-0014). Never "empty because it was missing or damaged". */
sealed interface ContactLoad {
    class Book(
        val book: ContactBook,
    ) : ContactLoad

    /** No record, a damaged or oversized one, one of another identity, or no identity at all: claims are refused. */
    data object Unavailable : ContactLoad
}

/**
 * The persisted [ContactBook]. A save is atomic and durable when it returns, and throws when it can't be (the caller
 * then treats the claim as blocked). The record is created empty together with the identity ([IdentityStore.save]),
 * so a missing record next to an identity is [ContactLoad.Unavailable], not a fresh book.
 */
interface ContactStore {
    suspend fun load(): ContactLoad

    suspend fun save(book: ContactBook)
}
