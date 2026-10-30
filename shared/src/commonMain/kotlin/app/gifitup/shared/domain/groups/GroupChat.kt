package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult

/** The part of [ChatActions] that sending messages uses, so a sender can be tested without an MLS client. */
interface GroupChat {
    /**
     * Seals [text] (at most 896 bytes), stores and posts it; [observer] hears of it before the post and of an older
     * message settled first.
     */
    suspend fun send(
        session: Session,
        groupId: ByteArray,
        text: ByteArray,
        sink: IncomingSink,
        observer: SendObserver = SendObserver.None,
    ): AuthResult<SyncOutcome>

    suspend fun resume(
        session: Session,
        groupId: ByteArray,
        sink: IncomingSink,
        observer: SendObserver = SendObserver.None,
    ): AuthResult<SyncOutcome>
}
