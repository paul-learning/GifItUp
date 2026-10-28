package app.gifitup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gifitup.AppGraph
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.domain.auth.DeviceAuth
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.chat.ChatService
import app.gifitup.shared.domain.chat.ReadMarks
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.invite.CreateInvite
import app.gifitup.shared.domain.invite.ListInvites
import app.gifitup.shared.domain.invite.RevokeInvite
import app.gifitup.shared.domain.media.CivilDate
import app.gifitup.shared.domain.media.LoadGifMedia
import app.gifitup.shared.domain.media.MediaPolicy
import app.gifitup.shared.domain.messages.CaptionInput
import app.gifitup.shared.domain.messages.InMemoryMessageStore
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.domain.session.SessionHolder
import app.gifitup.shared.presentation.chat.ChatListStateHolder
import app.gifitup.shared.presentation.chat.ConversationStateHolder
import app.gifitup.shared.presentation.chat.GifTilesStateHolder
import app.gifitup.shared.presentation.chat.NewChatStateHolder
import app.gifitup.shared.presentation.contacts.ContactsStateHolder
import app.gifitup.shared.presentation.invite.CreateInviteStateHolder
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder
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
    private val engine = graph.engine()
    private val api = GifItUpApi(engine, checkNotNull(ApiEndpoint.forOrigin(origin)) { "the origin setting only holds canonical origins" })
    private val auth = DeviceAuth(storage, api, mls, graph.random)

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

    // The history is in memory (#177 makes it durable): it lives as long as this view model, and goes with it.
    private val history = InMemoryMessageStore()
    private val marks = ReadMarks()
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

    /** The conversation the list opened, `null` if none (the screen then goes back). One at a time. */
    var conversation: ConversationStateHolder? = null
        private set

    /** Opens the conversation [info] describes; the previous one, if any, is left. */
    fun openConversation(info: GroupInfo) {
        conversation?.leave()
        tiles.leave()
        conversation = ConversationStateHolder(viewModelScope, sessions, chatService, history, marks, info, captions)
    }

    init {
        holder.start()
    }

    override fun onCleared() {
        invites.leave() // the link leaves memory and the clipboard before the API closes
        contacts.leave()
        chats.leave()
        newChat.leave()
        conversation?.leave()
        tiles.leave()
        (mediaFetcher as? AutoCloseable)?.close()
        api.close()
        engine.close()
    }
}

private const val SECONDS_PER_DAY = 86_400L
