package app.gifitup.shared.domain.groups

/**
 * Keeps the content of a **requested** group (#279) away from every sink: a message, a roster notice, anything. Whether a group
 * is requested is asked at the moment of each call, from the stored record, so accepting a request takes effect with the next
 * message and nothing that arrived before is ever replayed. A message read while the group is a request is acknowledged
 * and gone: the user decides on the sender's name alone, and the history of the chat starts at the acceptance.
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
