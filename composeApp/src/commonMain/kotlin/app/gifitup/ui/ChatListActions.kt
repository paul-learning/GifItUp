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
)

/** The destinations of the burger menu. */
internal class MenuActions(
    val onProfile: () -> Unit,
    val onInvite: () -> Unit,
    val onFriends: () -> Unit,
)
