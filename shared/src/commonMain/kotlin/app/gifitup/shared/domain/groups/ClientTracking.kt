package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.EncryptedMessage
import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.PreparedCommit

/**
 * Whether the live client has changed in a way the stored snapshot does not hold yet (ADR-0017). It is set when a call
 * that changes the MLS state returns, and cleared only after the snapshot that holds the change was saved. An engine that
 * stops while this is set must not be used again: its next export would store the change, for example a used-up
 * generation of a message whose history record was never written, and the message would be lost for good.
 */
internal class AheadFlag {
    var ahead = false
        private set

    fun moved() {
        ahead = true
    }

    fun saved() {
        ahead = false
    }
}

/** The client the engine's parts work on: every call that changes the MLS state raises the [flag]. */
internal class TrackedClient(
    private val inner: MlsClient,
    private val flag: AheadFlag,
) : MlsClient by inner {
    override fun createGroup(): ByteArray = inner.createGroup().also { flag.moved() }

    override fun prepareAdd(
        groupId: ByteArray,
        keyPackages: List<ByteArray>,
    ): PreparedCommit = inner.prepareAdd(groupId, keyPackages).also { flag.moved() }

    override fun prepareRemove(
        groupId: ByteArray,
        leafIndexes: List<Int>,
    ): PreparedCommit = inner.prepareRemove(groupId, leafIndexes).also { flag.moved() }

    override fun prepareUpdate(groupId: ByteArray): PreparedCommit = inner.prepareUpdate(groupId).also { flag.moved() }

    override fun mergePending(groupId: ByteArray): Long = inner.mergePending(groupId).also { flag.moved() }

    override fun encrypt(
        groupId: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessage = inner.encrypt(groupId, plaintext).also { flag.moved() }

    override fun stageIncoming(
        groupId: ByteArray,
        message: ByteArray,
    ): IncomingMessage = inner.stageIncoming(groupId, message).also { flag.moved() }

    override fun mergeStaged(token: Int): Long = inner.mergeStaged(token).also { flag.moved() }

    override fun joinWelcome(token: Int): ByteArray = inner.joinWelcome(token).also { flag.moved() }

    override fun forgetGroup(groupId: ByteArray) {
        inner.forgetGroup(groupId)
        flag.moved()
    }
}
