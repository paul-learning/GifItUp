package app.gifitup.shared.domain.contacts

/**
 * The contact use cases of one device and server, as one opaque bundle for the Friends screen's holder. The accept is
 * internal to `:shared`: the app modules get the bundle and can't reach it (ADR-0014).
 */
class ContactsUseCases internal constructor(
    val refresh: RefreshContacts,
    val access: ContactBookAccess,
    val fingerprints: ShowFingerprints,
    internal val accept: AcceptContact,
)
