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
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.presentation.chat.ConversationStateHolder
import app.gifitup.shared.presentation.chat.GroupInfoStateHolder
import app.gifitup.shared.presentation.session.OriginSession

/**
 * The androidx shell of one server origin's [OriginSession] (ADR-0013, #160): the session builds and owns everything bound
 * to the origin, this class gives it the view model's scope (so a rotation keeps it) and ends it in [onCleared]. What stays
 * here is what needs androidx or Compose: the lifecycle observer, and the Compose state of the open conversation, group info
 * and contact profile, which the screens observe.
 */
internal class OnboardingViewModel(
    graph: AppGraph,
    storage: DeviceStorage,
    mls: MlsClientFactory,
    origin: String,
    setting: ServerOriginSetting,
) : ViewModel() {
    private val session = OriginSession(viewModelScope, graph.originDeps(), storage, mls, origin, setting)

    val holder = session.holder
    val invites = session.invites
    val contacts = session.contacts
    val chats = session.chats
    val newChat = session.newChat
    val tiles = session.tiles
    val picker = session.picker
    val previews = session.previews
    val diagnosticsScreen = session.diagnosticsScreen
    val liveConnected = session.liveConnected

    private var lifecycleObserver: LifecycleEventObserver? = null
    private var observed: Lifecycle? = null

    /** Ties the socket to the app's own lifecycle (once): see [OriginSession.setForeground]. */
    fun bindLifecycle(lifecycle: Lifecycle) {
        if (observed != null) return
        observed = lifecycle
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> session.setForeground(true)
                    Lifecycle.Event.ON_STOP -> session.setForeground(false)
                    else -> Unit
                }
            }
        lifecycleObserver = observer
        lifecycle.addObserver(observer)
    }

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
        if (session.conversation == null) return
        groupInfo?.leave()
        groupInfo = session.newGroupInfo()
    }

    /** The conversation the list opened, `null` if none (the screen then goes back). One at a time. */
    var conversation: ConversationStateHolder? by mutableStateOf(null)
        private set

    /** Closes the open conversation (it was left or deleted): nothing of it stays open. */
    fun closeConversation() {
        session.closeConversation()
        conversation = null
    }

    /** Opens the conversation [info] describes; the previous one, if any, is left. */
    fun openConversation(info: GroupInfo) {
        conversation = session.openConversation(info)
    }

    override fun onCleared() {
        lifecycleObserver?.let { observed?.removeObserver(it) }
        session.close()
    }
}
