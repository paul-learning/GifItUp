package app.gifitup.shared.domain.contacts

/**
 * The contact use cases of one device and server, as one opaque bundle for the Friends screen's holder. Everything in
 * it is internal to `:shared`, and so are the book's `accept`, `claim` and `list` and the access object's `update`: an
 * app module can hold the bundle but can't change the book, so the only way to trust a contact is the holder's review
 * (ADR-0014).
 */
class ContactsUseCases internal constructor(
    internal val refresh: RefreshContacts,
    internal val access: ContactBookAccess,
    internal val fingerprints: ShowFingerprints,
    internal val accept: AcceptContact,
)
