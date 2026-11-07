package app.gifitup.shared.domain.events

import app.gifitup.protocol.SocketCloseCodes
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.Base64Url
import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The loop of the push channel (#27, ADR-0015) over a scripted socket and virtual time: the rules of the plan review. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveUpdatesTest {
    private val endpoint = checkNotNull(ApiEndpoint.forOrigin("https://chat.example.test"))
    private val knownGroup = KeyBytes(ByteArray(16) { 1 })
    private val knownText = "{\"groupId\":\"AQEBAQEBAQEBAQEBAQEBAQ\",\"seq\":3,\"kind\":\"application\"}"
    private val strangerText = "{\"groupId\":\"AgICAgICAgICAgICAgICAg\",\"seq\":3,\"kind\":\"application\"}"

    private sealed interface Move {
        class Frame(
            val text: String,
        ) : Move

        class Pause(
            val millis: Long,
        ) : Move

        class Close(
            val code: Int?,
        ) : Move
    }

    private class Link(
        moves: List<Move>,
    ) : SocketLink {
        private val queue = ArrayDeque(moves)
        private val closedSignal = CompletableDeferred<Unit>()
        val closed: Boolean get() = closedSignal.isCompleted

        @Suppress("ReturnCount") // The scripted socket's exits, one per kind of move.
        override suspend fun receive(): SocketFrame {
            while (true) {
                if (closed) return SocketFrame.Closed(null)
                when (val move = queue.removeFirstOrNull()) {
                    null -> {
                        closedSignal.await()
                        return SocketFrame.Closed(null)
                    }

                    is Move.Pause -> {
                        withTimeoutOrNull(move.millis) { closedSignal.await() }
                    }

                    is Move.Frame -> {
                        return SocketFrame.Text(move.text)
                    }

                    is Move.Close -> {
                        return SocketFrame.Closed(move.code)
                    }
                }
            }
        }

        override suspend fun close() {
            closedSignal.complete(Unit)
        }
    }

    private class Connector(
        private val clock: () -> Long,
    ) : SocketConnector {
        val opens = mutableListOf<Long>()
        val hellos = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val links = mutableListOf<Link>()
        var script = ArrayDeque<List<Move>?>() // null: the connect fails
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun open(
            url: String,
            hello: String,
        ): SocketOpening {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            opens += clock()
            hellos += hello
            urls += url
            delay(1) // an attempt takes a moment, so an overlapping second one would show
            inFlight--
            val moves = script.removeFirstOrNull() ?: return SocketOpening.Failed
            return SocketOpening.Opened(Link(moves).also { links += it })
        }
    }

    private class Fixture(
        val scope: TestScope,
        val connector: Connector,
        val sessions: SessionHolder,
        val live: LiveUpdates,
        val logins: () -> Int,
    )

    private fun TestScope.fixture(
        script: List<List<Move>?>,
        isKnown: suspend (KeyBytes) -> Boolean = { it == knownGroup },
        login: (Int) -> AuthResult<Session> = { n -> AuthResult.Ok(Session("d", "SENTINEL-TOKEN-$n", Long.MAX_VALUE / 4, endpoint)) },
    ): Fixture {
        var logins = 0
        val clock = { testScheduler.currentTime }
        val connector = Connector(clock).also { it.script = ArrayDeque(script) }
        val sessions = SessionHolder({ login(++logins) }, backgroundScope) { 0L }
        val live = LiveUpdates(connector, sessions, backgroundScope, isKnown, clock, random = { 0.0 })
        return Fixture(this, connector, sessions, live) { logins }
    }

    @Test
    fun aHintForAKnownGroupBecomesOneWakeAndAStrangerCausesNone() =
        runTest {
            val f = fixture(listOf(listOf(Move.Frame(strangerText), Move.Frame(knownText))))
            f.live.start()
            val wake = f.live.nextWake()
            assertEquals(setOf(knownGroup), wake.groups)
            assertFalse(wake.everything)
            assertEquals(LiveState.CONNECTED, f.live.status.value.state)
            assertEquals("wss://chat.example.test/ws", f.connector.urls.single())
        }

    @Test
    fun aFloodOfHintsIsOneWakeAndOverTheCapItAsksAboutEverything() =
        runTest {
            val flood = List(500) { Move.Frame(knownText) } + List(100) { n -> Move.Frame(groupText(n)) }
            val f = fixture(listOf(flood), isKnown = { true })
            f.live.start()
            val first = f.live.nextWake()
            assertTrue(first.everything, "more than 64 groups waiting is one wake that asks about everything")
            assertTrue(first.groups.size <= 64, "the set of waiting groups is bounded")
            val second = launchWake(f)
            advanceTimeBy(5_000)
            assertFalse(second.isCompleted, "600 hints made one wake, not 600")
            second.cancel()
        }

    @Test
    fun wakesAreAtLeastOneSecondApartWhateverTheHintsDo() =
        runTest {
            val moves = listOf(Move.Frame(knownText), Move.Pause(100), Move.Frame(knownText), Move.Pause(100), Move.Frame(knownText))
            val f = fixture(listOf(moves))
            f.live.start()
            f.live.nextWake()
            val first = testScheduler.currentTime
            f.live.nextWake()
            assertTrue(testScheduler.currentTime - first >= 1_000, "the second wake waited out the gap")
        }

    @Test
    fun aWelcomeAndAResyncHintAreWakesToo() =
        runTest {
            val welcome = "{\"groupId\":\"AQEBAQEBAQEBAQEBAQEBAQ\",\"seq\":0,\"kind\":\"welcome\"}"
            val f = fixture(listOf(listOf(Move.Frame(welcome), Move.Pause(2_000), Move.Frame("{\"resync\":true}"))))
            f.live.start()
            assertTrue(f.live.nextWake().welcomes)
            assertTrue(f.live.nextWake().everything)
        }

    @Test
    fun outdatedClientAndBadHandshakeStopForGood() =
        runTest {
            for ((code, reason) in listOf(
                SocketCloseCodes.CLIENT_OUTDATED to StopReason.OUTDATED_CLIENT,
                SocketCloseCodes.BAD_HANDSHAKE to StopReason.PROTOCOL_ERROR,
            )) {
                val f = fixture(listOf(listOf(Move.Close(code)), listOf(Move.Close(code))))
                f.live.start()
                advanceTimeBy(10 * 60_000L)
                assertEquals(1, f.connector.opens.size, "no retry after $code")
                assertEquals(LiveState.STOPPED, f.live.status.value.state)
                assertEquals(reason, f.live.status.value.stopped)
            }
        }

    @Test
    fun aRefusedSessionIsReplacedOnceAndARepeatedRefusalStops() =
        runTest {
            val f =
                fixture(
                    listOf(
                        listOf(Move.Close(SocketCloseCodes.UNAUTHORIZED)),
                        listOf(Move.Close(SocketCloseCodes.UNAUTHORIZED)),
                        listOf(Move.Pause(1)),
                    ),
                )
            f.live.start()
            advanceTimeBy(60_000)
            assertEquals(2, f.connector.opens.size, "one fresh try, then it stops")
            assertEquals(2, f.logins(), "exactly one login per refusal chain: the first and the replacement")
            assertEquals(StopReason.SESSION_REFUSED, f.live.status.value.stopped)
            assertTrue(f.connector.hellos[0] != f.connector.hellos[1], "the second hello carries the new session")
        }

    @Test
    fun aRefusalThenAStableConnectionResetsTheRule() =
        runTest {
            val f =
                fixture(
                    listOf(
                        listOf(Move.Close(SocketCloseCodes.UNAUTHORIZED)),
                        listOf(Move.Pause(40_000), Move.Close(SocketCloseCodes.UNAUTHORIZED)),
                        listOf(Move.Pause(1)),
                    ),
                )
            f.live.start()
            advanceTimeBy(100_000)
            assertEquals(3, f.connector.opens.size, "a refusal after a connection that lived 30 s is a new refusal chain")
            assertNull(f.live.status.value.stopped)
        }

    @Test
    fun theWaitDependsOnTheCloseCode() =
        runTest {
            val f =
                fixture(
                    listOf(
                        listOf(Move.Close(1008)),
                        listOf(Move.Close(SocketCloseCodes.SERVER_OUTDATED)),
                        listOf(Move.Close(1001)),
                        listOf(Move.Pause(1)),
                    ),
                )
            f.live.start()
            advanceTimeBy(10 * 60_000L)
            val gaps = f.connector.opens.zipWithNext { a, b -> b - a }
            assertEquals(60_001L, gaps[0], "1008 (evicted by another socket) waits a minute")
            assertEquals(5 * 60_000L + 1, gaps[1], "4002 (server outdated) waits five minutes")
            assertEquals(5_001L, gaps[2], "a clean 1001 reconnects after five seconds")
        }

    @Test
    fun failuresBackOffAndAConnectionThatLivedLongEnoughResetsIt() =
        runTest {
            val f = fixture(listOf(null, null, null, listOf(Move.Pause(40_000), Move.Close(null)), null, listOf(Move.Pause(1))))
            f.live.start()
            advanceTimeBy(10 * 60_000L)
            val gaps = f.connector.opens.zipWithNext { a, b -> b - a }
            assertEquals(listOf(1_001L, 2_001L, 4_001L), gaps.take(3), "1 s, 2 s, 4 s (the attempt itself takes 1 ms)")
            assertEquals(40_000L + 1_000L + 1, gaps[3], "after a connection that lived 40 s the back-off starts again at 1 s")
        }

    @Test
    fun onlyOneConnectionAttemptIsEverInFlight() =
        runTest {
            val f = fixture(listOf(null, null, null, null, listOf(Move.Pause(1))))
            f.live.start()
            f.live.start()
            f.live.start()
            advanceTimeBy(5 * 60_000L)
            assertEquals(1, f.connector.maxInFlight)
        }

    @Test
    fun theTokenIsInTheHelloAndInNoOtherOutput() =
        runTest {
            val f = fixture(listOf(null, listOf(Move.Frame(knownText), Move.Close(SocketCloseCodes.UNAUTHORIZED)), listOf(Move.Pause(1))))
            f.live.start()
            val outputs = mutableListOf<String>()
            val wake = f.live.nextWake()
            outputs += wake.toString()
            advanceTimeBy(30_000)
            outputs +=
                f.live.status.value
                    .toString()
            assertTrue(f.connector.hellos.all { "SENTINEL-TOKEN" in it }, "the hello carries the token")
            assertTrue(f.connector.urls.none { "SENTINEL" in it }, "never in the URL")
            assertTrue(outputs.none { "SENTINEL" in it }, "never in what the loop shows: $outputs")
        }

    @Test
    fun stoppingClosesTheSocketAndNothingIsOpenedAfterAWipe() =
        runTest {
            val f = fixture(listOf(listOf(Move.Pause(100_000)), listOf(Move.Pause(100_000))))
            f.live.start()
            advanceTimeBy(1_000)
            f.live.stop()
            assertTrue(
                f.connector.links
                    .single()
                    .closed,
            )
            assertEquals(LiveState.STOPPED, f.live.status.value.state)
            advanceTimeBy(10 * 60_000L)
            assertEquals(1, f.connector.opens.size)
        }

    @Test
    fun afterAWipeTheLoopStopsInsteadOfLoggingInAgain() =
        runTest {
            val f =
                fixture(
                    listOf(listOf(Move.Pause(500), Move.Close(null)), listOf(Move.Pause(1))),
                    login = { n ->
                        if (n ==
                            1
                        ) {
                            AuthResult.Ok(Session("d", "SENTINEL-TOKEN-1", Long.MAX_VALUE / 4, endpoint))
                        } else {
                            AuthResult.Failed(AuthError.Local.NOT_REGISTERED)
                        }
                    },
                )
            f.live.start()
            advanceTimeBy(100)
            f.sessions.clear() // a wipe or a logout
            advanceTimeBy(60_000)
            assertEquals(StopReason.NOT_SIGNED_IN, f.live.status.value.stopped)
            assertEquals(1, f.connector.opens.size, "no hello after the wipe")
        }

    @Test
    fun reconnectDropsTheSocketAndConnectsAgainAfterASecondWithoutBackingOff() =
        runTest {
            val f = fixture(listOf(listOf(Move.Pause(100_000)), listOf(Move.Pause(100_000))))
            f.live.start()
            advanceTimeBy(500)
            f.live.reconnect()
            advanceTimeBy(1_500)
            assertEquals(2, f.connector.opens.size)
            assertTrue(f.connector.links[0].closed)
        }

    private fun groupText(n: Int): String {
        val id = Base64Url.encode(ByteArray(16) { (n + 2).toByte() })
        return "{\"groupId\":\"$id\",\"seq\":1,\"kind\":\"commit\"}"
    }

    private fun launchWake(f: Fixture) = f.scope.launch { f.live.nextWake() }
}
