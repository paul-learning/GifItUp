package app.gifitup.shared.domain.groups

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestGuardSinkTest {
    private val requested = byteArrayOf(1)
    private val normal = byteArrayOf(2)
    private val delivered = mutableListOf<Int>()
    private val notices = mutableListOf<Int>()

    private val sink =
        RequestGuardSink(
            object : IncomingSink {
                override suspend fun deliver(message: DeliveredMessage) {
                    delivered += message.groupId[0].toInt()
                }

                override suspend fun membership(change: MembershipChange) {
                    notices += change.groupId[0].toInt()
                }
            },
        ) { it.contentEquals(requested) }

    private fun message(group: ByteArray) = DeliveredMessage(group, 1, "alice", ByteArray(32), ByteArray(32), ByteArray(4))

    @Test
    fun nothingOfARequestedGroupReachesTheInnerSinkButOtherGroupsDo() =
        runTest {
            sink.deliver(message(requested))
            sink.deliver(message(normal))
            sink.membership(MembershipChange(requested, 1, "alice", emptyList(), emptyList()))
            sink.membership(MembershipChange(normal, 1, "alice", emptyList(), emptyList()))
            assertEquals(listOf(2), delivered)
            assertEquals(listOf(2), notices)
        }

    @Test
    fun theQuestionIsAskedAtTheMomentOfEachCall() =
        runTest {
            var held = true
            val late =
                RequestGuardSink(
                    object : IncomingSink {
                        override suspend fun deliver(message: DeliveredMessage) {
                            delivered += 9
                        }
                    },
                ) { held }
            late.deliver(message(requested))
            held = false // accepted
            late.deliver(message(requested))
            assertEquals(listOf(9), delivered, "only what comes after the accept gets through; nothing is replayed")
        }
}
