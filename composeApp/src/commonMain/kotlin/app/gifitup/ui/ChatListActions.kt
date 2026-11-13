package app.gifitup.ui

import app.gifitup.shared.domain.groups.GroupInfo

/**
 * What the chat list can do besides showing (#217): the menu's three destinations, a new chat or group, and [onOpen], which
 * is `null` where there is nowhere to open a chat.
 */
internal class ChatListActions(
    val onNewChat: () -> Unit,
    val onMenu: MenuActions,
    val onOpen: ((GroupInfo) -> Unit)?,
    /** A long tap on a direct chat opens that friend's profile. */
    val onProfile: (String) -> Unit = {},
    /** The server is not the default: the top bar carries a marker that opens the Profile (ADR-0013). */
    val customServer: Boolean = false,
    /** The push channel's socket is connected: the list's fallback timer slows down (#27). */
    val liveConnected: Boolean = false,
)

/** The destinations of the burger menu and of the profile button next to it. */
internal class MenuActions(
    val onProfile: () -> Unit,
    val onInvite: () -> Unit,
    val onFriends: () -> Unit,
    val onDiagnostics: () -> Unit,
    /** The signed-in user, for the initial that opens the profile; blank while unknown. */
    val username: String = "",
)
