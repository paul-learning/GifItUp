package app.gifitup.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.gifitup.shared.domain.groups.GroupInfo

internal const val ROUTE_ONBOARDING = "onboarding"
internal const val ROUTE_FRIENDS = "friends"
internal const val ROUTE_CHATS = "chats"
internal const val ROUTE_NEW_CHAT = "new-chat"
internal const val ROUTE_CONVERSATION = "conversation"

/**
 * The chat routes. The onboarding entry stays on the back stack below them, so its ViewModel (API, session, engine, the
 * in-memory history) is the one they use; leaving a route drops that screen's state, not the history.
 */
internal fun NavGraphBuilder.chatRoutes(
    nav: NavHostController,
    origin: String,
) {
    composable(ROUTE_CHATS) { entry ->
        val parent = remember(entry) { nav.getBackStackEntry(ROUTE_ONBOARDING) }
        val model = viewModel<OnboardingViewModel>(parent, key = "onboarding-$origin")
        val back = { if (nav.currentDestination?.route == ROUTE_CHATS) nav.popBackStack() }
        BoxWithConstraints {
            // A wide window (the web, a tablet) shows the list and the open conversation side by side.
            val wide = maxWidth >= WIDE_MIN_WIDTH
            val open = { info: GroupInfo ->
                model.openConversation(info)
                if (!wide) nav.navigate(ROUTE_CONVERSATION)
            }
            val actions = ChatListActions({ nav.navigate(ROUTE_NEW_CHAT) }, { nav.navigate(ROUTE_FRIENDS) }, open)
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    ChatListScreen(
                        model.chats,
                        actions,
                        modifier = Modifier.width(LIST_PANE_WIDTH),
                        selected = model.conversation?.id,
                        onBack = { back() },
                    )
                    VerticalDivider()
                    OpenConversation(model, Modifier.weight(1f))
                }
            } else {
                ChatListScreen(model.chats, actions, onBack = { back() })
            }
        }
    }
    composable(ROUTE_NEW_CHAT) { entry ->
        val parent = remember(entry) { nav.getBackStackEntry(ROUTE_ONBOARDING) }
        val model = viewModel<OnboardingViewModel>(parent, key = "onboarding-$origin")
        val leave = { if (nav.currentDestination?.route == ROUTE_NEW_CHAT) nav.popBackStack() }
        NewChatScreen(model.newChat, onDone = { leave() }, onBack = { leave() })
    }
    composable(ROUTE_CONVERSATION) { entry ->
        val parent = remember(entry) { nav.getBackStackEntry(ROUTE_ONBOARDING) }
        val model = viewModel<OnboardingViewModel>(parent, key = "onboarding-$origin")
        val holder = model.conversation
        val leave = { if (nav.currentDestination?.route == ROUTE_CONVERSATION) nav.popBackStack() }
        // A process that was recreated has no open conversation: back to the list.
        if (holder ==
            null
        ) {
            LaunchedEffect(Unit) { leave() }
        } else {
            ConversationScreen(holder, model.tiles, PickerParts(model.picker, model.previews), onBack = { leave() })
        }
    }
}

/** The right pane of the wide layout: the conversation the list opened, or a hint. */
@Composable
private fun OpenConversation(
    model: OnboardingViewModel,
    modifier: Modifier,
) {
    val holder = model.conversation
    Box(modifier.fillMaxSize()) {
        if (holder == null) {
            Text(ConversationTexts.PICK_CHAT, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            key(holder) { ConversationScreen(holder, model.tiles, PickerParts(model.picker, model.previews), onBack = null) }
        }
    }
}

private val WIDE_MIN_WIDTH = 840.dp
private val LIST_PANE_WIDTH = 360.dp
