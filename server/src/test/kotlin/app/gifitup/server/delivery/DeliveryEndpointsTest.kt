package app.gifitup.server.delivery

import app.gifitup.protocol.AckRequest
import app.gifitup.protocol.ProtocolJson
import app.gifitup.server.auth.KeyedRateLimiter
import app.gifitup.server.auth.TestClock
import app.gifitup.server.db.DeliveryCleanup
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Delivery Service endpoints against the real database (ADR-0015). */
class DeliveryEndpointsTest {
    private fun roomy(clock: TestClock) =
        DeliveryLimits(
            clock,
            posts = KeyedRateLimiter(clock, 1_000, Duration.ofSeconds(1)),
            commits = KeyedRateLimiter(clock, 1_000, Duration.ofSeconds(1)),
            reads = KeyedRateLimiter(clock, 1_000, Duration.ofSeconds(1)),
            creates = KeyedRateLimiter(clock, 1_000, Duration.ofSeconds(1)),
        )

    @Test
    fun aGroupIsCreatedWelcomedPostedToFetchedAndAcknowledged() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()

            val created = posted(create(ana, group, commit(0, added = listOf(ben))))
            assertEquals(1L, created.seq)
            assertEquals(1L, created.epoch)

            val welcome = welcomes(ben).welcomes.single()
            assertEquals(group, welcome.groupId)
            assertTrue(welcomes(ana).welcomes.isEmpty(), "only the target sees a welcome")
            assertEquals(HttpStatusCode.NotFound, json(ana, "/welcomes/${welcome.id}/ack", "").status, "not Ana's welcome")
            assertEquals(HttpStatusCode.NoContent, json(ben, "/welcomes/${welcome.id}/ack", "").status)
            assertTrue(welcomes(ben).welcomes.isEmpty())

            val sent = posted(post(ana, group, application()))
            assertEquals(2L, sent.seq)
            assertEquals(1L, sent.epoch)

            val fetched = fetch(ben, group)
            assertEquals(listOf(2L), fetched.messages.map { it.seq }, "Ben joined at seq 1 and sees only what came after")
            assertEquals(ana.deviceId, fetched.messages.single().senderDeviceId)
            assertEquals(2L, fetched.lastSeq)
            assertEquals(1L, fetched.oldestSeq, "the commit at seq 1 is retained too")
        }

    @Test
    fun theFreshnessAnchorAndCursorsAreExposed() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            val id = msgId()
            posted(post(ana, group, application(clientMsgId = id)))

            val status = status(ana, group)
            assertEquals(1L, status.epoch)
            assertEquals(2L, status.lastSeq)
            assertEquals(2L, status.lastSentSeq)
            assertEquals(id, status.lastClientMsgId)
            assertNull(status(ben, group).lastSentSeq)

            assertEquals(HttpStatusCode.NoContent, json(ben, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(2))).status)
            assertEquals(2L, status(ben, group).ackedSeq)
            assertEquals(HttpStatusCode.BadRequest, json(ben, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(9))).status)
        }

    @Test
    fun aRetryReturnsTheFirstAnswerAndADifferentEnvelopeWithTheSameIdIsAConflict() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            val first = commit(0, added = listOf(ben))
            val answer = posted(create(ana, group, first))
            // The same envelope again (a lost response): not a second group and not an epoch conflict.
            assertEquals(answer, posted(create(ana, group, first)), "the creator repeating its own create gets the first answer")
            val rival = member("rival")
            assertEquals(HttpStatusCode.BadRequest, create(rival, group, commit(0, added = listOf(ana))).status, "a taken id")

            val message = application()
            val one = posted(post(ana, group, message))
            val two = posted(post(ana, group, message))
            assertEquals(one, two)
            assertEquals(1, fetch(ben, group).messages.count { it.kind == "application" })

            val other = application(clientMsgId = message.clientMsgId, payload = bytes(50, 9))
            val clash = post(ana, group, other)
            assertEquals(HttpStatusCode.Conflict, clash.status)
            assertEquals("envelope_conflict", errorCode(clash))
            assertEquals(1L, answer.seq)
        }

    @Test
    fun onlyTheFirstCommitForAnEpochWins() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val carl = member("carl")
            val dina = member("dina")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))

            val winner = posted(post(ben, group, commit(1, added = listOf(carl))))
            assertEquals(2L, winner.epoch)
            val loser = post(ana, group, commit(1, added = listOf(dina)))
            assertEquals(HttpStatusCode.Conflict, loser.status)
            assertEquals("epoch_conflict", errorCode(loser))
            assertTrue(welcomes(dina).welcomes.isEmpty(), "a refused commit stores nothing")
            assertEquals(2L, status(ana, group).epoch)
        }

    @Test
    fun aNonMemberAndAnUnknownGroupGetTheSameAnswerAndNewMembersSeeNoHistory() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val carl = member("carl")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            posted(post(ana, group, application()))

            val outsider = get(carl, "/groups/$group/messages")
            val unknown = get(carl, "/groups/${newGroupId()}/messages")
            assertEquals(HttpStatusCode.NotFound, outsider.status)
            assertEquals(outsider.bodyAsText(), unknown.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, post(carl, group, application()).status)
            assertEquals(HttpStatusCode.NotFound, get(carl, "/groups/not-base64!").status)

            posted(post(ben, group, commit(1, added = listOf(carl))))
            posted(post(ana, group, application()))
            assertEquals(listOf(4L), fetch(carl, group).messages.map { it.seq }, "Carl sees nothing from before its commit")
        }

    @Test
    fun removalFollowsTheRulesAndGhostsAreRemovable() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val carl = member("carl")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben, carl))))

            val notAllowed = post(ben, group, commit(1, removed = listOf(carl.deviceId)))
            assertEquals(HttpStatusCode.BadRequest, notAllowed.status, "a non-creator may not remove another member")
            assertEquals("envelope_invalid", errorCode(notAllowed))
            val ghost =
                java.util.UUID
                    .randomUUID()
                    .toString()
            assertEquals(2L, posted(post(ben, group, commit(1, removed = listOf(ghost)))).epoch, "any member may name a ghost")
            assertEquals(3L, posted(post(ana, group, commit(2, removed = listOf(carl.deviceId)))).epoch, "the creator removes anyone")

            assertEquals(HttpStatusCode.NotFound, get(carl, "/groups/$group").status, "a removed device is not a member")
            assertEquals(4L, posted(post(ben, group, commit(3, removed = listOf(ben.deviceId)))).epoch, "a member removes itself")
            assertEquals(HttpStatusCode.NotFound, get(ben, "/groups/$group").status)
        }

    @Test
    fun malformedEnvelopesAreRefusedWithOneAnswerAndNothingIsStored() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            val ok = application()
            val bad =
                mapOf(
                    "unknown kind" to ok.copy(kind = "other"),
                    "application with an epoch" to ok.copy(epoch = 1),
                    "application with added" to ok.copy(added = listOf(ben.deviceId)),
                    "commit without an epoch" to commit(1, added = emptyList(), removed = listOf(ana.deviceId)).copy(epoch = null),
                    "commit that changes nothing" to commit(1),
                    "added without a welcome" to commit(1, added = listOf(member("carl"))).copy(welcomes = emptyList()),
                    "added a member" to commit(1, added = listOf(ben)),
                    "added an unknown device" to
                        commit(
                            1,
                            added = listOf(ben),
                        ).copy(
                            added =
                                listOf(
                                    java.util.UUID
                                        .randomUUID()
                                        .toString(),
                                ),
                        ),
                    "short client id" to ok.copy(clientMsgId = "AAAA"),
                    "payload with padding" to ok.copy(payload = ok.payload + "="),
                    "uppercase uuid" to
                        commit(
                            1,
                            removed =
                                listOf(
                                    java.util.UUID
                                        .randomUUID()
                                        .toString()
                                        .uppercase(),
                                ),
                        ),
                )
            val answers =
                bad.map { (why, request) ->
                    val response = post(ana, group, request)
                    assertEquals(HttpStatusCode.BadRequest, response.status, why)
                    response.bodyAsText()
                }
            assertEquals(1, answers.toSet().size, "one answer for every reason")
            assertEquals(1L, status(ana, group).lastSeq, "nothing was stored")
            assertEquals(HttpStatusCode.PayloadTooLarge, post(ana, group, ok.copy(payload = bytes(3_000))).status)
            assertEquals(HttpStatusCode.BadRequest, create(ana, "AAAA", commit(0, added = listOf(ben))).status, "group id under 16 bytes")
        }

    @Test
    fun leavingDropsTheMembershipAndAnEmptyGroupGoes() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            assertEquals(HttpStatusCode.NoContent, json(ben, "/groups/$group/leave", "").status)
            assertEquals(HttpStatusCode.NotFound, json(ben, "/groups/$group/leave", "").status)
            assertEquals(HttpStatusCode.NotFound, get(ben, "/groups/$group").status)
            assertEquals(HttpStatusCode.NoContent, json(ana, "/groups/$group/leave", "").status)
            assertEquals(HttpStatusCode.NotFound, get(ana, "/groups/$group").status)
            assertEquals(
                0,
                db.transaction { c ->
                    c.createStatement().executeQuery("SELECT count(*) FROM groups").use {
                        it.next()
                        it.getInt(1)
                    }
                },
            )
        }

    @Test
    fun theCapsRefuseWithTheirOwnCodes() =
        deliveryTest(limits = ::roomy, rules = DeliveryRules(maxMessagesPerGroup = 3)) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            posted(post(ana, group, application()))
            posted(post(ana, group, application()))
            val full = post(ana, group, application())
            assertEquals(HttpStatusCode.Conflict, full.status)
            assertEquals("group_full", errorCode(full))

            // Adding the same target a fourth time in a day is a group limit.
            val dina = member("dina")
            val other = listOf(newGroupId(), newGroupId(), newGroupId(), newGroupId())
            other.take(3).forEach { posted(create(ana, it, commit(0, added = listOf(dina)))) }
            val tooOften = create(ana, other[3], commit(0, added = listOf(dina)))
            assertEquals(HttpStatusCode.Conflict, tooOften.status)
            assertEquals("group_limit", errorCode(tooOften))
        }

    @Test
    fun threePendingWelcomesFromOneAdderToOneTargetAreTheWelcomeLimit() =
        deliveryTest(limits = ::roomy, rules = DeliveryRules(addWindow = Duration.ZERO)) {
            val ana = member("ana")
            val dina = member("dina")
            repeat(3) { posted(create(ana, newGroupId(), commit(0, added = listOf(dina)))) }
            val limited = create(ana, newGroupId(), commit(0, added = listOf(dina)))
            assertEquals(HttpStatusCode.Conflict, limited.status)
            assertEquals("welcome_limit", errorCode(limited))
            // Dina acknowledges one, and the next add goes through.
            val first = welcomes(dina).welcomes.first()
            assertEquals(HttpStatusCode.NoContent, json(dina, "/welcomes/${first.id}/ack", "").status)
            posted(create(ana, newGroupId(), commit(0, added = listOf(dina))))
        }

    @Test
    fun rateLimitsAnswer429() =
        deliveryTest(
            limits = {
                DeliveryLimits(
                    it,
                    posts = KeyedRateLimiter(it, 2, Duration.ofHours(1)),
                    commits = KeyedRateLimiter(it, 1_000, Duration.ofHours(1)),
                    reads = KeyedRateLimiter(it, 3, Duration.ofHours(1)),
                    creates = KeyedRateLimiter(it, 1_000, Duration.ofHours(1)),
                )
            },
        ) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben)))) // post token 1
            posted(post(ana, group, application())) // post token 2
            assertEquals(HttpStatusCode.TooManyRequests, post(ana, group, application()).status)
            repeat(3) { assertEquals(HttpStatusCode.OK, get(ana, "/groups/$group").status) }
            assertEquals(HttpStatusCode.TooManyRequests, get(ana, "/groups/$group").status)
            assertNotEquals(HttpStatusCode.TooManyRequests, get(ben, "/groups/$group").status, "limits are per device")
        }

    @Test
    fun cleanupDeletesAcknowledgedAndExpiredRowsButKeepsWhatAMemberStillNeeds() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            posted(post(ana, group, application()))
            posted(post(ana, group, application()))
            val cleanup = DeliveryCleanup(db, clock)

            json(ana, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(3)))
            assertEquals(1, cleanup.runOnce(), "Ben joined at seq 1, so only seq 1 is acknowledged by both")
            json(ben, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(2)))
            assertEquals(1, cleanup.runOnce(), "now seq 2 is acknowledged by both")
            assertEquals(3L, fetch(ben, group, after = 2).messages.single().seq)
            assertEquals(3L, fetch(ana, group).oldestSeq, "the first retained seq moved on")

            // A member silent for more than 7 days stops holding messages back (the cleanup's clock runs ahead; the
            // sessions are still valid on the server's).
            posted(post(ana, group, application()))
            clock.advance(Duration.ofHours(23))
            json(ana, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(4)))
            val later =
                TestClock(
                    clock.now
                        .minus(Duration.ofHours(23))
                        .plus(Duration.ofDays(7))
                        .plus(Duration.ofHours(1)),
                )
            assertEquals(2, DeliveryCleanup(db, later).runOnce(), "seq 3 and 4: Ben is silent, Ana has acknowledged them")
            // Messages expire after 30 days.
            posted(post(ana, group, application()))
            val muchLater = TestClock(clock.now.plus(Duration.ofDays(31)))
            assertTrue(DeliveryCleanup(db, muchLater).runOnce() >= 1)
            assertEquals(0, fetch(ana, group).messages.size)
        }

    @Test
    fun aRefusedCreateLeavesNothingBehindAndTheIdCanBeUsedAgain() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            val unknown =
                commit(0, added = listOf(ben)).copy(
                    added =
                        listOf(
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                        ),
                )
            assertEquals(HttpStatusCode.BadRequest, create(ana, group, unknown).status)
            assertEquals(
                0,
                db.transaction { c ->
                    c.createStatement().executeQuery("SELECT count(*) FROM groups").use {
                        it.next()
                        it.getInt(1)
                    }
                },
            )
            assertEquals(1L, posted(create(ana, group, commit(0, added = listOf(ben)))).seq, "the refused attempt did not burn the id")
        }

    @Test
    fun aFullLogStillTakesACommitThatRemovesTheOneWhoFilledIt() =
        deliveryTest(limits = ::roomy, rules = DeliveryRules(maxMessagesPerGroup = 3)) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            posted(post(ben, group, application()))
            posted(post(ben, group, application()))
            assertEquals("group_full", errorCode(post(ben, group, application())))
            val noOp = post(ana, group, commit(1, added = listOf(member("carl"))))
            assertEquals("group_full", errorCode(noOp), "a commit that removes nobody gets no headroom")
            val ghostOnly =
                post(
                    ana,
                    group,
                    commit(
                        1,
                        removed =
                            listOf(
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            ),
                    ),
                )
            assertEquals("group_full", errorCode(ghostOnly), "naming a ghost does not earn the headroom")
            assertEquals(2L, posted(post(ana, group, commit(1, removed = listOf(ben.deviceId)))).epoch, "headroom for a removal")
        }

    @Test
    fun aRepeatedAckDoesNotKeepAMemberActive() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            posted(post(ana, group, application()))
            json(ben, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(1)))
            clock.advance(Duration.ofHours(20))
            json(ben, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(1)))
            json(ana, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(2)))
            val later =
                TestClock(
                    clock.now
                        .minus(Duration.ofHours(20))
                        .plus(Duration.ofDays(7))
                        .plus(Duration.ofHours(1)),
                )
            // Ben's last advance was at the start, more than 7 days before "later"; the repeat at +20h did not count.
            assertEquals(2, DeliveryCleanup(db, later).runOnce())
        }

    @Test
    fun addAndRemoveCyclesDoNotResetTheDailyAddLimit() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            var epoch = 1L
            repeat(2) {
                epoch = posted(post(ana, group, commit(epoch, removed = listOf(ben.deviceId)))).epoch
                epoch = posted(post(ana, group, commit(epoch, added = listOf(ben)))).epoch
                json(ben, "/welcomes/${welcomes(ben).welcomes.last().id}/ack", "")
                // Everyone acknowledges and the retention runs between the adds: the add log is not messages.
                val last = status(ana, group).lastSeq
                json(ana, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(last)))
                DeliveryCleanup(db, clock).runOnce()
            }
            epoch = posted(post(ana, group, commit(epoch, removed = listOf(ben.deviceId)))).epoch
            val fourth = post(ana, group, commit(epoch, added = listOf(ben)))
            assertEquals("group_limit", errorCode(fourth), "three adds in a day, however many removals in between")
        }

    @Test
    fun aRetryOfACommitThatRemovedTheSenderStillGetsTheFirstAnswer() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            val leaving = commit(1, removed = listOf(ben.deviceId))
            val first = posted(post(ben, group, leaving))
            assertEquals(first, posted(post(ben, group, leaving)), "the response was lost and Ben retries")
        }

    @Test
    fun theFirstCommitCannotRemoveAnyone() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val withRemoval =
                commit(
                    0,
                    added = listOf(ben),
                    removed =
                        listOf(
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                        ),
                )
            assertEquals(HttpStatusCode.BadRequest, create(ana, newGroupId(), withRemoval).status)
        }

    @Test
    fun aCaughtUpCursorIsNotAGapOnceCleanupEmptiedTheLog() =
        deliveryTest(limits = ::roomy) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            json(ana, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(1)))
            json(ben, "/groups/$group/ack", ProtocolJson.encodeToString(AckRequest(1)))
            assertEquals(1, DeliveryCleanup(db, clock).runOnce())
            val status = status(ana, group)
            assertEquals(status.lastSeq + 1, status.oldestSeq, "an empty log: the next seq")
        }

    @Test
    fun aFetchPageStopsAtItsByteBudgetButAlwaysHoldsOneMessage() =
        deliveryTest(limits = ::roomy, rules = DeliveryRules(maxFetchBytes = 1_000)) {
            val ana = member("ana")
            val ben = member("ben")
            val group = newGroupId()
            posted(create(ana, group, commit(0, added = listOf(ben))))
            repeat(3) { posted(post(ana, group, application(payload = bytes(600)))) }
            val page = fetch(ben, group)
            assertEquals(listOf(2L), page.messages.map { it.seq }, "600 + 600 is over 1,000 bytes: one message")
            assertEquals(listOf(3L), fetch(ben, group, after = 2).messages.map { it.seq })
        }
}
