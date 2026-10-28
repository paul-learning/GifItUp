package app.gifitup.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

internal const val ROUTE_ONBOARDING = "onboarding"
internal const val ROUTE_FRIENDS = "friends"
internal const val ROUTE_CHATS = "chats"
internal const val ROUTE_NEW_CHAT = "new-chat"

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
        ChatListScreen(
            model.chats,
            onNewChat = { nav.navigate(ROUTE_NEW_CHAT) },
            onFriends = { nav.navigate(ROUTE_FRIENDS) },
            onOpen = null, // The conversation screen comes next (#25 part 3).
        ) {
            if (nav.currentDestination?.route == ROUTE_CHATS) nav.popBackStack()
        }
    }
    composable(ROUTE_NEW_CHAT) { entry ->
        val parent = remember(entry) { nav.getBackStackEntry(ROUTE_ONBOARDING) }
        val model = viewModel<OnboardingViewModel>(parent, key = "onboarding-$origin")
        val leave = { if (nav.currentDestination?.route == ROUTE_NEW_CHAT) nav.popBackStack() }
        NewChatScreen(model.newChat, onDone = { leave() }, onBack = { leave() })
    }
}
