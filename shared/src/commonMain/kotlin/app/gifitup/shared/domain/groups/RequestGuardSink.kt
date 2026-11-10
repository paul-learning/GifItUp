package app.gifitup.shared.domain.groups

/**
 * Keeps the content of a **requested** group (#279) away from every sink: a message, a roster notice, anything. Whether a group
 * is requested is asked at the moment of each call, from the stored record. A requested group is not read at all (see
 * `GroupSyncEngine.sync`), so this is the second line: if something still reached a sink while the mark is set, it is dropped
 * here and not replayed.
 */
internal class RequestGuardSink(
    private val inner: IncomingSink,
    private val isRequested: (ByteArray) -> Boolean,
) : IncomingSink {
    override suspend fun deliver(message: DeliveredMessage) {
        if (!isRequested(message.groupId)) inner.deliver(message)
    }

    override suspend fun membership(change: MembershipChange) {
        if (!isRequested(change.groupId)) inner.membership(change)
    }
}
