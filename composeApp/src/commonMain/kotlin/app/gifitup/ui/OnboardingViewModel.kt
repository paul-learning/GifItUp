package app.gifitup.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gifitup.AppGraph
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
import app.gifitup.shared.presentation.gif.GifPickerStateHolder
import app.gifitup.shared.presentation.invite.CreateInviteStateHolder
import app.gifitup.shared.presentation.onboarding.OnboardingState
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder
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
 * The onboarding, and after it the invite screen, for one server origin (ADR-0013). It owns everything bound to that
 * origin, the HTTP engine, the API, the identity use cases and the session, so they live exactly as long as the flow
 * that uses them: a rotation keeps them, and [onCleared] closes them (#131 SEC-15). It keeps nothing in saved state:
 * the invite code lives in the holder's memory only, and a process death starts the flow again (a pending
 * registration is finished without it). The invite link of the create-invite screen is in memory only too.
 */
internal class OnboardingViewModel(
    graph: AppGraph,
    storage: DeviceStorage,
    mls: MlsClientFactory,
    origin: String,
    setting: ServerOriginSetting,
) : ViewModel() {
    // The durable history (ADR-0017): sealed records on this device, opened on first use. One instance per view model; the
    // wipe closes it (and its key in memory) before anything else, and so does the end of this view model.
    private val history =
        LazyHistory(
            DeviceHistoryOpener(storage, origin, graph.random, graph.historyCiphers, graph.historyLimits),
            { Clock.System.now().toEpochMilliseconds() },
            resetter = DeviceHistoryResetter(storage, origin),
        )
    private val marks = ReadMarks(history)

    private val engine = graph.engine()
    private val api = GifItUpApi(engine, checkNotNull(ApiEndpoint.forOrigin(origin)) { "the origin setting only holds canonical origins" })

    // The GIF picker (#23): the provider key is fetched from our server only by the refresher below (when the account is
    // ready, then every 6 hours), kept in memory only, and cleared with this view model. Searches go to the provider directly.
    private val gifKeys = GifKeyHolder()
    private val providerFetcher = graph.conversations.providerFetcher()
    private var gifKeyJob: Job? = null
    private val gifProvider = KlipyGifProvider(providerFetcher, gifKeys) { Clock.System.now().toEpochMilliseconds() }

    private val auth =
        DeviceAuth(
            storage,
            api,
            mls,
            graph.random,
            HistoryCloser {
                // A wipe ends the GIF session too: the refresh stops and the key leaves memory before the history closes.
                endGifSession()
                history.close()
            },
        )

    private val sessions = SessionHolder({ auth.logIn() }, viewModelScope) { Clock.System.now().epochSeconds }

    val holder =
        OnboardingStateHolder(
            scope = viewModelScope,
            origin = setting,
            identities = storage.identities,
            register = auth.register,
            refill = auth.refillKeyPackages,
            sessions = sessions,
        )

    /** The create-invite screen's logic, over the same session, API and engine (closed with them). */
    val invites =
        CreateInviteStateHolder(viewModelScope, sessions, CreateInvite(api), ListInvites(api), RevokeInvite(api), graph.clipboard)

    /** The Friends screen's logic, over the same session, API and engine (ADR-0014). */
    private val chatService = ChatService(auth.chat, history) { Clock.System.now().epochSeconds }

    val contacts = ContactsStateHolder(viewModelScope, sessions, auth.contacts, chatService)

    /** The chat list's logic (#25), over the same session, API and engine. */
    val chats = ChatListStateHolder(viewModelScope, sessions, chatService, history, marks)

    // Live updates (#27, ADR-0015, ADR-0020): one socket while the app is in the foreground. A hint only makes this device ask,
    // through the same reads the timers use; the screens' timers stay as the fallback.
    private val socket = KtorSocketConnector(graph.socketEngine())
    val live =
        LiveUpdates(socket, sessions, viewModelScope, ::isKnownGroup, { Clock.System.now().toEpochMilliseconds() })

    /** Whether the socket is connected: the screens' timers slow down while it is. */
    val liveConnected: StateFlow<Boolean> = live.status.map { it.connected }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val liveRefresh = LiveRefresh(viewModelScope, live::nextWake, LiveTarget())

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
            conversation?.refresh()
            chats.refreshOnly(emptySet())
        }

        override suspend fun refreshGroup(group: KeyBytes) = chats.refreshOnly(setOf(group))

        override suspend fun refreshWelcomes() {
            known.forget() // a Welcome may have joined a group the next hint names
            chats.refreshOnly(emptySet(), welcomes = true)
        }
    }

    private var foreground = false
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var observed: Lifecycle? = null

    /**
     * Ties the socket to the app's own lifecycle (once): it opens when the app is in the foreground and the account is ready,
     * and closes when the app leaves the foreground, so no socket runs in the background (ADR-0015).
     */
    fun bindLifecycle(lifecycle: Lifecycle) {
        if (observed != null) return
        observed = lifecycle
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> liveStart()
                    Lifecycle.Event.ON_STOP -> liveStop()
                    else -> Unit
                }
            }
        lifecycleObserver = observer
        lifecycle.addObserver(observer)
    }

    // Start and stop go through one queue, in the order the lifecycle asked (#274 SEC-1).
    private val liveSwitch =
        LiveSwitch(
            viewModelScope,
            open = {
                liveRefresh.start()
                live.start()
            },
            close = {
                live.stop()
                liveRefresh.stop()
            },
        )

    private fun liveStart() {
        foreground = true
        viewModelScope.launch {
            holder.state.first { it is OnboardingState.Done }
            if (foreground) liveSwitch.set(true)
        }
    }

    private fun liveStop() {
        foreground = false
        liveSwitch.set(false)
    }

    /** The new-chat flow's logic: the friends are the contact book's saved contacts. */
    val newChat = NewChatStateHolder(viewModelScope, sessions, chatService, auth.friendNames, history)

    // Fetches GIFs straight from the provider (ADR-0003), apart from the server's API client.
    private val mediaFetcher = graph.conversations.mediaFetcher()

    /** The GIFs on the open conversation's screen: loaded on their own as they come into view. */
    val tiles =
        GifTilesStateHolder(
            viewModelScope,
            LoadGifMedia(mediaFetcher),
        )
    private val captions = CaptionInput(graph.conversations.graphemes)

    /** The picker's logic; its results' previews load through [previews], like the bubbles. */
    val picker = GifPickerStateHolder(viewModelScope, gifProvider)
    val previews =
        GifTilesStateHolder(
            viewModelScope,
            LoadGifMedia(mediaFetcher),
        )

    /** The contact whose profile is open (their username), `null` if none; set before navigating, never a route argument. */
    var contactProfile: String? by mutableStateOf(null)
        private set

    fun openProfile(username: String) {
        contactProfile = username
    }

    /** The info screen of the open group, `null` if none; set before navigating, never a route argument (#217). */
    var groupInfo: GroupInfoStateHolder? by mutableStateOf(null)
        private set

    /** Opens the info of the open conversation, with the members it shows now. */
    fun openGroupInfo() {
        val open = conversation ?: return
        groupInfo?.leave()
        groupInfo =
            GroupInfoStateHolder(
                viewModelScope,
                sessions,
                chatService,
                auth.friendNames,
                history,
                GroupInfo(open.id, open.info.status, open.state.value.members),
            )
    }

    /** The conversation the list opened, `null` if none (the screen then goes back). One at a time. */
    var conversation: ConversationStateHolder? by mutableStateOf(null)
        private set

    /** Opens the conversation [info] describes; the previous one, if any, is left. */
    fun openConversation(info: GroupInfo) {
        conversation?.leave()
        tiles.leave()
        conversation = ConversationStateHolder(viewModelScope, sessions, chatService, history, marks, info, captions)
    }

    init {
        holder.start()
        // The key's refresh starts when the account is ready, with the login that fetches it, and ends with this view model.
        val refresher = GifKeyRefresher(gifKeys, { sessions.withSessionResult { FetchGifProviderKey(api)(it) } })
        gifKeyJob =
            viewModelScope.launch {
                holder.state.first { it is OnboardingState.Done }
                refresher.run()
            }
    }

    /** The end of the signed-in session (a wipe, the end of this view model): no more refresh, and the key leaves memory. */
    private fun endGifSession() {
        gifKeyJob?.cancel()
        gifKeys.clear()
    }

    override fun onCleared() {
        invites.leave() // the link leaves memory and the clipboard before the API closes
        contacts.leave()
        chats.leave()
        newChat.leave()
        conversation?.leave()
        tiles.leave()
        picker.close()
        previews.leave()
        endGifSession()
        lifecycleObserver?.let { observed?.removeObserver(it) }
        foreground = false
        // The socket closes with this view model; the scope that runs its loop ends with it as well.
        socket.close()
        (providerFetcher as? AutoCloseable)?.close()
        (mediaFetcher as? AutoCloseable)?.close()
        // The history key and the decrypted cache leave memory with the view model (the wipe has closed it already).
        MainScope().launch { runCatching { history.close() } }
        api.close()
        engine.close()
    }
}
