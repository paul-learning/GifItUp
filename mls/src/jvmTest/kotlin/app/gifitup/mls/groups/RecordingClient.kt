package app.gifitup.mls.groups

import app.gifitup.shared.data.crypto.MlsClient

/** Records which groups the crate created and forgot, to check that a failed call leaves none behind. */
class RecordingClient(
    private val real: MlsClient,
) : MlsClient by real {
    val created = mutableListOf<List<Byte>>()
    val forgotten = mutableListOf<List<Byte>>()

    override fun createGroup(): ByteArray = real.createGroup().also { created += it.toList() }

    override fun forgetGroup(groupId: ByteArray) {
        forgotten += groupId.toList()
        real.forgetGroup(groupId)
    }
}
