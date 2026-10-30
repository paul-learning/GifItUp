package app.gifitup.shared.domain.groups

import app.gifitup.shared.data.crypto.EncryptedMessage
import app.gifitup.shared.data.crypto.IncomingMessage
import app.gifitup.shared.data.crypto.MlsClient
import app.gifitup.shared.data.crypto.PreparedCommit
import app.gifitup.shared.data.crypto.WelcomeInfo

/**
 * Whether the live client may have changed in a way the stored snapshot does not hold yet (ADR-0017). It is raised by
 * every call that can change the MLS state, **whether the call returns or throws** (the crate does not roll back a
 * generation it used up before it failed), and lowered only after the snapshot that holds the change was saved. An
 * engine that stops while it is up must not be used again: its next export would store the change, for example a
 * used-up generation of a message whose history record was never written, and the message would be lost for good.
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

    /**
     * A step that was undone completely (a group made for a request that was then refused, a commit prepared and then
     * cleared) puts the flag back to what it was before the step. Never used for a step that was not undone.
     */
    fun undone(before: Boolean) {
        ahead = before
    }
}

/**
 * The client the engine's parts work on. Every method that can change the MLS state (or consume a KeyPackage) raises the
 * [flag] even when it throws; the reads and the key-package and export calls the engine does not use are delegated.
 * Calls that may use up a generation or a KeyPackage before they fail raise it on a throw too; the others (group
 * creation, preparing a commit, clearing, discarding, forgetting) raise it when they return, since a refused request
 * (bad input) changes nothing. A step the engine undoes completely puts the flag back with [AheadFlag.undone].
 */
@Suppress("TooManyFunctions") // One override per mutating MlsClient method.
internal class TrackedClient(
    private val inner: MlsClient,
    private val flag: AheadFlag,
) : MlsClient by inner {
    private inline fun <T> tracked(block: () -> T): T =
        try {
            block()
        } finally {
            flag.moved()
        }

    private inline fun <T> onReturn(block: () -> T): T = block().also { flag.moved() }

    override fun createGroup(): ByteArray = onReturn { inner.createGroup() }

    override fun prepareAdd(
        groupId: ByteArray,
        keyPackages: List<ByteArray>,
    ): PreparedCommit = onReturn { inner.prepareAdd(groupId, keyPackages) }

    override fun prepareRemove(
        groupId: ByteArray,
        leafIndexes: List<Int>,
    ): PreparedCommit = onReturn { inner.prepareRemove(groupId, leafIndexes) }

    override fun prepareUpdate(groupId: ByteArray): PreparedCommit = onReturn { inner.prepareUpdate(groupId) }

    override fun mergePending(groupId: ByteArray): Long = tracked { inner.mergePending(groupId) }

    override fun clearPending(groupId: ByteArray) = onReturn { inner.clearPending(groupId) }

    override fun encrypt(
        groupId: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessage = tracked { inner.encrypt(groupId, plaintext) }

    override fun stageIncoming(
        groupId: ByteArray,
        message: ByteArray,
    ): IncomingMessage = tracked { inner.stageIncoming(groupId, message) }

    override fun mergeStaged(token: Int): Long = tracked { inner.mergeStaged(token) }

    override fun discardStaged(token: Int) = onReturn { inner.discardStaged(token) }

    override fun inspectWelcome(
        welcome: ByteArray,
        expectedGroupId: ByteArray,
    ): WelcomeInfo = tracked { inner.inspectWelcome(welcome, expectedGroupId) }

    override fun joinWelcome(token: Int): ByteArray = tracked { inner.joinWelcome(token) }

    override fun discardWelcome(token: Int) = onReturn { inner.discardWelcome(token) }

    override fun forgetGroup(groupId: ByteArray) = onReturn { inner.forgetGroup(groupId) }
}
