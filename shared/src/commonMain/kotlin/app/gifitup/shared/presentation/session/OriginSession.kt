package app.gifitup.shared.presentation.session

import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.gif.KlipyGifProvider
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.data.socket.KtorSocketConnector
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.auth.DeviceAuth
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.auth.FetchGifProviderKey
import app.gifitup.shared.domain.auth.HistoryCloser
import app.gifitup.shared.domain.chat.ChatService
import app.gifitup.shared.domain.chat.ReadMarks
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.diagnostics.DiagInfo
import app.gifitup.shared.domain.diagnostics.DiagnosedChatPort
import app.gifitup.shared.domain.events.LiveUpdates
import app.gifitup.shared.domain.gif.GifKeyHolder
import app.gifitup.shared.domain.gif.GifKeyRefresher
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.groups.GroupStatus
import app.gifitup.shared.domain.invite.CreateInvite
import app.gifitup.shared.domain.invite.ListInvites
import app.gifitup.shared.domain.invite.RevokeInvite
import app.gifitup.shared.domain.media.LoadGifMedia
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.domain.messages.DeviceHistoryOpener
import app.gifitup.shared.domain.messages.DeviceHistoryResetter
import app.gifitup.shared.domain.messages.LazyHistory
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.domain.session.SessionHolder
import app.gifitup.shared.presentation.chat.ChatListStateHolder
import app.gifitup.shared.presentation.chat.ConversationStateHolder
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.GroupInfoStateHolder
import app.gifitup.shared.presentation.chat.KnownGroups
import app.gifitup.shared.presentation.chat.LiveRefresh
import app.gifitup.shared.presentation.chat.LiveSwitch
import app.gifitup.shared.presentation.chat.NewChatStateHolder
import app.gifitup.shared.presentation.contacts.ContactsStateHolder
import app.gifitup.shared.presentation.diagnostics.DiagnosticsObserver
import app.gifitup.shared.presentation.diagnostics.DiagnosticsStateHolder
import app.gifitup.shared.presentation.gif.GifPickerStateHolder
import app.gifitup.shared.presentation.invite.CreateInviteStateHolder
import app.gifitup.shared.presentation.onboarding.OnboardingState
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The onboarding, and after it every screen, for one server origin (ADR-0013): everything bound to that origin, the HTTP
 * engine, the API, the identity use cases, the session, the socket and all state holders, so they live exactly as long as
 * the flow that uses them. The platform shell (`OriginViewModel` in `composeApp`) owns one per origin and calls [close]
 * when it ends (#131 SEC-15). It keeps nothing in saved state: the invite code lives in the holder's memory only, and a
 * process death starts the flow again (a pending registration is finished without it). The invite link of the create-invite
 * screen is in memory only too.
 *
 * It is the one place that builds the data layer for a screen (#160): the UI package gets holders, never the API, the engine
 * or the socket. Moved out of the view model unchanged (the same objects in the same order); [scope] is the view model's
 * scope, so everything launched here ends with it.
 */
@Suppress("TooManyFunctions") // The per-origin session scope (ARCH-M2-2 of the M2 review splits it); one small function per hand-off.
class OriginSession(
    private val scope: CoroutineScope,
    private val deps: OriginDeps,
    storage: DeviceStorage,
    mls: MlsClientFactory,
    private val origin: String,
    setting: ServerOriginSetting,
) {
    // The durable history (ADR-0017): sealed records on this device, opened on first use. One instance per session; the
    // wipe closes it (and its key in memory) before anything else, and so does the end of this session.
    private val history =
        LazyHistory(
            DeviceHistoryOpener(storage, origin, deps.random, deps.historyCiphers, deps.historyLimits),
            { Clock.System.now().toEpochMilliseconds() },
            resetter = DeviceHistoryResetter(storage, origin),
        )
    private val marks = ReadMarks(history)

    private val engine = deps.engine()
    private val api = GifItUpApi(engine, checkNotNull(ApiEndpoint.forOrigin(origin)) { "the origin setting only holds canonical origins" })

    // The GIF picker (#23): the provider key is fetched from our server only by the refresher below (when the account is
    // ready, then every 6 hours), kept in memory only, and cleared with this session. Searches go to the provider directly.
    private val gifKeys = GifKeyHolder()
    private val providerFetcher = deps.providerFetcher()
    private var gifKeyJob: Job? = null
    private val gifProvider = KlipyGifProvider(providerFetcher, gifKeys) { Clock.System.now().toEpochMilliseconds() }

    private val auth =
        DeviceAuth(
            storage,
            api,
            mls,
            deps.random,
            HistoryCloser {
                // A wipe ends the GIF session too: the refresh stops and the key leaves memory before the history closes.
                endGifSession()
                // The diagnostics log ends before the store is deleted, so no event recreates it afterwards (SEC-13).
                deps.diagnostics.stop()
                history.close()
            },
        )

    private val sessions = SessionHolder({ auth.logIn() }, scope) { Clock.System.now().epochSeconds }

    val holder =
        OnboardingStateHolder(
            scope = scope,
            origin = setting,
            identities = storage.identities,
            register = auth.register,
            refill = auth.refillKeyPackages,
            sessions = sessions,
        )

    /** The create-invite screen's logic, over the same session, API and engine (closed with them). */
    val invites =
        CreateInviteStateHolder(scope, sessions, CreateInvite(api), ListInvites(api), RevokeInvite(api), deps.clipboard)

    /** The chat use cases over the same session, API and engine, with a note in the diagnostics log for failures (DiagnosedChatPort). */
    private val chatService = DiagnosedChatPort(ChatService(auth.chat, history) { Clock.System.now().epochSeconds }, deps.diagnostics)

    /** The Friends screen's logic, over the same session, API and engine (ADR-0014). */
    val contacts = ContactsStateHolder(scope, sessions, auth.contacts, chatService)

    /** The chat list's logic (#25), over the same session, API and engine. */
    val chats = ChatListStateHolder(scope, sessions, chatService, history, marks)

    // Live updates (#27, ADR-0015, ADR-0020): one socket while the app is in the foreground. A hint only makes this device ask,
    // through the same reads the timers use; the screens' timers stay as the fallback.
    private val socket = KtorSocketConnector(deps.socketEngine())
    val live =
        LiveUpdates(socket, sessions, scope, ::isKnownGroup, { Clock.System.now().toEpochMilliseconds() })

    /** Whether the socket is connected: the screens' timers slow down while it is. */
    val liveConnected: StateFlow<Boolean> = live.status.map { it.connected }.stateIn(scope, SharingStarted.Eagerly, false)
    private val liveRefresh = LiveRefresh(scope, live::nextWake, LiveTarget())

    // What the diagnostics log hears of the screens' own problem states (#154 follow-up); it never sees content.
    private val diagnostics = DiagnosticsObserver(deps.diagnostics, scope)

    /** The diagnostics screen's logic; the report says only fixed words and numbers (DiagnosticsReport). */
    val diagnosticsScreen =
        DiagnosticsStateHolder(
            scope,
            deps.diagnostics,
            {
                DiagInfo(
                    version = deps.version,
                    platform = deps.platform,
                    customServer = origin != deps.defaultOrigin,
                    chats = DiagInfo.bucket(chats.state.value.conversations.size),
                    liveConnected = liveConnected.value,
                )
            },
            { Clock.System.now().epochSeconds },
        )

    init {
        diagnostics.watchSocket(liveConnected)
        diagnostics.watchLive(live.status)
        diagnostics.watchList(chats.state.map { it.problem })
    }

    private suspend fun knownGroups(): List<GroupInfo> =
        (sessions.withSessionResult { chatService.groups(it) } as? AuthResult.Ok)?.value?.groups.orEmpty()

    // A snapshot, not a listing per hint (#274 CR-2): one listing per 5 s at most, whatever the socket says.
    private val known =
        KnownGroups(
            { knownGroups().filter { it.status == GroupStatus.ACTIVE }.map { it.id } },
            { Clock.System.now().toEpochMilliseconds() },
        )

    private suspend fun isKnownGroup(id: KeyBytes): Boolean = known.contains(id)

    /** What a hint refreshes: the open conversation through its own read, the list only while it is on screen. */
    private inner class LiveTarget : LiveRefresh.Target {
        override suspend fun knownGroups() = known.all()

        override fun openGroup() = conversation?.id

        override suspend fun refreshOpen() {
            conversation?.refreshAnnounced()
            chats.refreshOnly(emptySet())
        }

        override suspend fun refreshGroup(group: KeyBytes) = chats.refreshOnly(setOf(group))

        override suspend fun refreshWelcomes() {
            known.forget() // a Welcome may have joined a group the next hint names
            chats.refreshOnly(emptySet(), welcomes = true)
        }
    }

    private var foreground = false

    // Start and stop go through one queue, in the order the app asked (#274 SEC-1).
    private val liveSwitch =
        LiveSwitch(
            scope,
            open = {
                liveRefresh.start()
                live.start()
            },
            close = {
                live.stop()
                liveRefresh.stop()
            },
        )

    /**
     * Ties the socket to the app's own lifecycle: it opens when the app is in the foreground and the account is ready, and
     * closes when the app leaves the foreground, so no socket runs in the background (ADR-0015). The shell calls it from its
     * lifecycle observer.
     */
    fun setForeground(on: Boolean) {
        foreground = on
        if (!on) {
            liveSwitch.set(false)
            return
        }
        scope.launch {
            holder.state.first { it is OnboardingState.Done }
            if (foreground) liveSwitch.set(true)
        }
    }

    /** The new-chat flow's logic: the friends are the contact book's saved contacts. */
    val newChat = NewChatStateHolder(scope, sessions, chatService, auth.friendNames, history)

    // Fetches GIFs straight from the provider (ADR-0003), apart from the server's API client.
    private val mediaFetcher = deps.mediaFetcher()

    /** The GIFs on the open conversation's screen: loaded on their own as they come into view. */
    val tiles =
        GifTilesStateHolder(
            scope,
            LoadGifMedia(mediaFetcher),
        )
    private val captions = CaptionInput(deps.graphemes)

    init {
        diagnostics.watchMedia(tiles.tiles)
    }

    /** The picker's logic; its results' previews load through [previews], like the bubbles. */
    val picker = GifPickerStateHolder(scope, gifProvider)
    val previews =
        GifTilesStateHolder(
            scope,
            LoadGifMedia(mediaFetcher),
        )

    /** The info screen's logic for the open conversation, with the members it shows now; `null` if none is open. */
    fun newGroupInfo(): GroupInfoStateHolder? {
        val open = conversation ?: return null
        return GroupInfoStateHolder(
            scope,
            sessions,
            chatService,
            auth.friendNames,
            history,
            GroupInfo(open.id, open.info.status, open.state.value.members),
        )
    }

    private var sendWatch: Job? = null

    /**
     * The conversation the list opened, `null` if none. One at a time. A plain property: the platform shell mirrors it into the
     * observable state its screens read and must set it only through [openConversation] and [closeConversation].
     */
    var conversation: ConversationStateHolder? = null
        private set

    /** Closes the open conversation (it was left or deleted): nothing of it stays open. */
    fun closeConversation() {
        conversation?.leave()
        tiles.leave()
        conversation = null
    }

    /** Opens the conversation [info] describes and returns its holder; the previous one, if any, is left. */
    fun openConversation(info: GroupInfo): ConversationStateHolder {
        conversation?.leave()
        tiles.leave()
        val opened =
            ConversationStateHolder(scope, sessions, chatService, history, marks, info, captions).also {
                sendWatch?.cancel()
                sendWatch = diagnostics.watchSend(it.state.map { s -> s.sendProblem })
                // A read the timer made found messages no hint announced: the socket is quiet but not working (rate limited inside).
                it.onUnannouncedNews = { scope.launch { live.reconnect() } }
            }
        conversation = opened
        return opened
    }

    init {
        holder.start()
        // The key's refresh starts when the account is ready, with the login that fetches it, and ends with this session.
        val refresher = GifKeyRefresher(gifKeys, { sessions.withSessionResult { FetchGifProviderKey(api)(it) } })
        gifKeyJob =
            scope.launch {
                holder.state.first { it is OnboardingState.Done }
                refresher.run()
            }
    }

    /** The end of the signed-in session (a wipe, the end of this session): no more refresh, and the key leaves memory. */
    private fun endGifSession() {
        gifKeyJob?.cancel()
        gifKeys.clear()
    }

    /** Ends everything this session holds, in the order the view model's `onCleared` had (#131 SEC-15). */
    fun close() {
        invites.leave() // the link leaves memory and the clipboard before the API closes
        contacts.leave()
        chats.leave()
        newChat.leave()
        conversation?.leave()
        tiles.leave()
        picker.close()
        previews.leave()
        endGifSession()
        foreground = false
        // The socket closes with this session; the scope that runs its loop ends with the view model as well.
        socket.close()
        (providerFetcher as? AutoCloseable)?.close()
        (mediaFetcher as? AutoCloseable)?.close()
        // The history key and the decrypted cache leave memory with the session (the wipe has closed it already).
        MainScope().launch { runCatching { history.close() } }
        api.close()
        engine.close()
    }
}
