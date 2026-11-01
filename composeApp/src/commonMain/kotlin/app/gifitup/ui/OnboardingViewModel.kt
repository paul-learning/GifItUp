package app.gifitup.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gifitup.AppGraph
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.gif.KlipyGifProvider
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.domain.auth.DeviceAuth
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.auth.FetchGifProviderKey
import app.gifitup.shared.domain.auth.HistoryCloser
import app.gifitup.shared.domain.chat.ChatService
import app.gifitup.shared.domain.chat.ReadMarks
import app.gifitup.shared.domain.gif.GifKeyHolder
import app.gifitup.shared.domain.gif.GifKeyRefresher
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.invite.CreateInvite
import app.gifitup.shared.domain.invite.ListInvites
import app.gifitup.shared.domain.invite.RevokeInvite
import app.gifitup.shared.domain.media.CivilDate
import app.gifitup.shared.domain.media.LoadGifMedia
import app.gifitup.shared.domain.media.MediaPolicy
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.domain.messages.DeviceHistoryOpener
import app.gifitup.shared.domain.messages.DeviceHistoryResetter
import app.gifitup.shared.domain.messages.LazyHistory
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.domain.session.SessionHolder
import app.gifitup.shared.presentation.chat.ChatListStateHolder
import app.gifitup.shared.presentation.chat.ConversationStateHolder
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.NewChatStateHolder
import app.gifitup.shared.presentation.contacts.ContactsStateHolder
import app.gifitup.shared.presentation.gif.GifPickerStateHolder
import app.gifitup.shared.presentation.invite.CreateInviteStateHolder
import app.gifitup.shared.presentation.onboarding.OnboardingState
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
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

    private val auth = DeviceAuth(storage, api, mls, graph.random, HistoryCloser { history.close() })

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
    val contacts = ContactsStateHolder(viewModelScope, sessions, auth.contacts)

    private val chatService = ChatService(auth.chat, history) { Clock.System.now().epochSeconds }

    /** The chat list's logic (#25), over the same session, API and engine. */
    val chats = ChatListStateHolder(viewModelScope, sessions, chatService, history, marks)

    /** The new-chat flow's logic: the friends are the contact book's saved contacts. */
    val newChat = NewChatStateHolder(viewModelScope, sessions, chatService, auth.friendNames)

    // Fetches GIFs straight from the provider (ADR-0003), apart from the server's API client.
    private val mediaFetcher = graph.conversations.mediaFetcher()

    /** The GIFs on the open conversation's screen: loaded on their own or on a tap, by the device's patch level. */
    val tiles =
        GifTilesStateHolder(
            viewModelScope,
            MediaPolicy(graph.conversations.securityPatch) { CivilDate.ofEpochDays(Clock.System.now().epochSeconds / SECONDS_PER_DAY) },
            LoadGifMedia(mediaFetcher),
        )
    private val captions = CaptionInput(graph.conversations.graphemes)

    // The GIF picker (#23): the provider key is fetched from our server only by the refresher below (when the account is
    // ready, then every 6 hours), kept in memory only, and cleared with this view model. Searches go to the provider directly.
    private val gifKeys = GifKeyHolder()
    private val providerFetcher = graph.conversations.providerFetcher()
    private val gifProvider = KlipyGifProvider(providerFetcher, gifKeys) { Clock.System.now().toEpochMilliseconds() }

    /** The picker's logic; its results' previews load through [previews], like the bubbles. */
    val picker = GifPickerStateHolder(viewModelScope, gifProvider)
    val previews =
        GifTilesStateHolder(
            viewModelScope,
            MediaPolicy(graph.conversations.securityPatch) { CivilDate.ofEpochDays(Clock.System.now().epochSeconds / SECONDS_PER_DAY) },
            LoadGifMedia(mediaFetcher),
        )

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
        viewModelScope.launch {
            holder.state.first { it is OnboardingState.Done }
            refresher.run()
        }
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
        gifKeys.clear()
        (providerFetcher as? AutoCloseable)?.close()
        (mediaFetcher as? AutoCloseable)?.close()
        // The history key and the decrypted cache leave memory with the view model (the wipe has closed it already).
        MainScope().launch { runCatching { history.close() } }
        api.close()
        engine.close()
    }
}

private const val SECONDS_PER_DAY = 86_400L
