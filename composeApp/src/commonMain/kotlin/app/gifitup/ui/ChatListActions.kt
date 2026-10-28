package app.gifitup.ui

import app.gifitup.shared.domain.groups.GroupInfo

/** What the chat list can do besides showing: [onOpen] is `null` where there is nowhere to open a chat. */
internal class ChatListActions(
    val onNewChat: () -> Unit,
    val onFriends: () -> Unit,
    val onOpen: ((GroupInfo) -> Unit)?,
)
