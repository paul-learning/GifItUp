package app.gifitup.mls.groups

import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.MlsErrorCode
import app.gifitup.shared.data.crypto.MlsException

/** A client that dies the moment a message is staged, like a panic in the crate (ADR-0007). */
class PoisonedOnStage(
    private val real: MlsClient,
) : MlsClient by real {
    override fun stageIncoming(
        groupId: ByteArray,
        message: ByteArray,
    ): IncomingMessage = throw MlsException(MlsErrorCode.Poisoned)
}
