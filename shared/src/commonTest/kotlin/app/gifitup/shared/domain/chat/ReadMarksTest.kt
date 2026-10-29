package app.gifitup.shared.domain.chat

import app.gifitup.shared.data.content.GifReference
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.messages.ChatMessage
import app.gifitup.shared.domain.messages.HistoryStoreException
import app.gifitup.shared.domain.messages.MessageBody
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ReadMarksTest {
    private val gif =
        assertNotNull(
            GifReference.of("klipy", "cat", "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/cat-1.webp", 320, 240),
        )
    private val group = KeyBytes(ByteArray(16) { 1 })

    private fun ref(n: Int) = KeyBytes(ByteArray(32) { n.toByte() })

    private fun msg(
        n: Int,
        own: Boolean = false,
    ) = ChatMessage(group, ref(n), if (own) null else "bob", null, MessageBody.Gif(gif, null), null, 100L)

    private class Memory(
        var mark: KeyBytes? = null,
        var failRead: Boolean = false,
        var failWrite: Boolean = false,
    ) : ReadMarkStore {
        var writes = 0

        override suspend fun newestSeen(group: KeyBytes): KeyBytes? =
            if (failRead) throw HistoryStoreException(HistoryStoreException.Kind.STORAGE) else mark

        override suspend fun markSeen(
            group: KeyBytes,
            reference: KeyBytes,
        ) {
            if (failWrite) throw HistoryStoreException(HistoryStoreException.Kind.STORAGE)
            writes++
            mark = reference
        }
    }

    @Test
    fun everythingReceivedIsUnreadUntilShownAndOwnMessagesNeverCount() =
        runTest {
            val marks = ReadMarks()
            val list = listOf(msg(1), msg(2, own = true), msg(3))
            assertEquals(2, marks.unread(group, list))
            marks.markRead(group, list)
            assertEquals(0, marks.unread(group, list))
            assertEquals(1, marks.unread(group, list + msg(4)), "a message after the last view is unread")
        }

    @Test
    fun theMarkSurvivesARestartThroughTheStoreAndIsWrittenOnlyWhenItChanges() =
        runTest {
            val store = Memory()
            val first = ReadMarks(store)
            val list = listOf(msg(1), msg(2))
            first.markRead(group, list)
            first.markRead(group, list)
            assertEquals(1, store.writes)
            val restarted = ReadMarks(store)
            assertEquals(0, restarted.unread(group, list))
            assertEquals(1, restarted.unread(group, list + msg(3)))
        }

    @Test
    fun aMarkForAMessageThatIsGoneMeansEverythingIsUnread() =
        runTest {
            val marks = ReadMarks(Memory(mark = ref(9)))
            assertEquals(2, marks.unread(group, listOf(msg(1), msg(2))))
        }

    @Test
    fun aFailingStoreCostsOnlyTheMarkAcrossARestart() =
        runTest {
            val store = Memory(failRead = true, failWrite = true)
            val marks = ReadMarks(store)
            val list = listOf(msg(1), msg(2))
            assertEquals(2, marks.unread(group, list))
            marks.markRead(group, list)
            assertEquals(0, marks.unread(group, list), "still read for this session")
            assertEquals(2, ReadMarks(store).unread(group, list), "unread again after a restart")
        }

    @Test
    fun aSlowerScreenWithAnOlderListCannotMoveTheMarkBack() =
        runTest {
            val store = Memory()
            val marks = ReadMarks(store)
            val older = listOf(msg(1), msg(2))
            val newer = older + msg(3)
            marks.markRead(group, newer)
            marks.markRead(group, older)
            assertEquals(ref(3), store.mark)
            assertEquals(0, marks.unread(group, newer))
        }

    @Test
    fun aCipherFailureIsNotSwallowed() =
        runTest {
            val broken =
                object : ReadMarkStore {
                    override suspend fun newestSeen(group: KeyBytes): KeyBytes? =
                        throw HistoryStoreException(HistoryStoreException.Kind.CIPHER)

                    override suspend fun markSeen(
                        group: KeyBytes,
                        reference: KeyBytes,
                    ) = throw HistoryStoreException(HistoryStoreException.Kind.CIPHER)
                }
            assertFailsWith<HistoryStoreException> { ReadMarks(broken).unread(group, listOf(msg(1))) }
        }
}
