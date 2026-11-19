package app.gifitup.shared.presentation.session

import app.gifitup.shared.data.crypto.FakeHistoryCipher
import app.gifitup.shared.data.crypto.FakeMlsClient
import app.gifitup.shared.data.crypto.HistoryCipher
import app.gifitup.shared.data.crypto.HistoryCipherFactory
import app.gifitup.shared.data.gif.ProviderAnswer
import app.gifitup.shared.data.gif.ProviderFetcher
import app.gifitup.shared.data.network.FakeAuthServer
import app.gifitup.shared.data.storage.InMemoryLocalStore
import app.gifitup.shared.data.storage.InMemoryRecordStore
import app.gifitup.shared.domain.auth.CountingRandom
import app.gifitup.shared.domain.auth.DeviceData
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.auth.InMemoryHistoryKeyStore
import app.gifitup.shared.domain.auth.InMemoryKeyStore
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.diagnostics.DiagnosticsLog
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.media.FetchResult
import app.gifitup.shared.domain.media.MediaFetcher
import app.gifitup.shared.domain.messages.CodePointCounter
import app.gifitup.shared.domain.messages.HistoryLimits
import app.gifitup.shared.presentation.invite.SensitiveClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The per-origin object graph (#160), built over fakes the way the platform builds it: it is wired, a conversation opens and
 * closes one at a time, and ending the session closes what the session made (the engine, an `AutoCloseable` fetcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OriginSessionTest {
    // `close()` ends the history on the main dispatcher, as the view model's `onCleared` did.
    @BeforeTest
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val server = FakeAuthServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var engines = 0
    private val media = ClosableMedia()
    private val storage =
        DeviceStorage(
            InMemoryLocalStore(),
            object : DeviceData {
                override suspend fun destroyWrappingKey() = Unit

                override suspend fun deleteAll() = Unit
            },
            InMemoryRecordStore(),
            { _, _ -> InMemoryKeyStore() },
            { InMemoryHistoryKeyStore() },
        )

    private class ClosableMedia :
        MediaFetcher,
        AutoCloseable {
        var closed = 0

        override suspend fun fetch(reference: app.gifitup.shared.data.content.GifReference): FetchResult = FetchResult.Failed

        override fun close() {
            closed++
        }
    }

    private val deps =
        OriginDeps(
            engine = {
                engines++
                server.engine
            },
            socketEngine = { server.engine },
            random = CountingRandom(),
            clipboard =
                object : SensitiveClipboard {
                    override val clearsCopied = true

                    override suspend fun copy(text: String) = true

                    override fun clearCopied() = Unit
                },
            mediaFetcher = { media },
            providerFetcher = { ProviderFetcher { _, _ -> ProviderAnswer.Failed } },
            graphemes = CodePointCounter,
            historyCiphers = { HistoryCipherFactory { _, _ -> FakeHistoryCipher() as HistoryCipher } },
            historyLimits = HistoryLimits(),
            diagnostics = DiagnosticsLog.None,
            platform = "test",
            version = "1.2.3",
            defaultOrigin = FakeAuthServer.ORIGIN,
        )

    private fun session() =
        OriginSession(
            scope,
            deps,
            storage,
            FakeMlsClient.Factory,
            FakeAuthServer.ORIGIN,
            storage.originSetting(FakeAuthServer.ORIGIN, true),
        )

    private fun group(n: Int) = GroupInfo(KeyBytes(ByteArray(16) { n.toByte() }), GroupStatus.ACTIVE, listOf("bob"))

    @Test
    fun itBuildsOneEngineAndStartsWithoutAnOpenConversation() =
        runTest {
            val session = session()
            assertEquals(1, engines)
            assertNull(session.conversation)
            assertFalse(session.liveConnected.value)
            session.close()
            scope.cancel()
        }

    @Test
    fun oneConversationIsOpenAtATimeAndClosingLeavesNone() =
        runTest {
            val session = session()
            val first = session.openConversation(group(1))
            assertSame(first, session.conversation)
            val second = session.openConversation(group(2))
            assertNotSame(first, second)
            assertSame(second, session.conversation)
            assertNotNull(session.newGroupInfo())
            session.closeConversation()
            assertNull(session.conversation)
            assertNull(session.newGroupInfo())
            session.close()
            scope.cancel()
        }

    @Test
    fun theForegroundSwitchBeforeTheAccountIsReadyOpensNoSocket() =
        runTest {
            val session = session()
            session.setForeground(true)
            session.setForeground(false)
            assertFalse(session.liveConnected.value)
            assertTrue(server.requests.isEmpty(), "no request, and so no socket, before the account is ready")
            session.close()
            scope.cancel()
        }

    @Test
    fun closingEndsTheEngineAndTheClosableFetcher() =
        runTest {
            val session = session()
            session.close()
            assertEquals(1, media.closed)
            assertTrue(server.engine.coroutineContext[Job]?.isCompleted == true)
            scope.cancel()
        }
}
