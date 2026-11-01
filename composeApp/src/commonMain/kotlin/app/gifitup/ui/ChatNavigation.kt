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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.gifitup.GlyphSupport
import app.gifitup.shared.domain.groups.GroupInfo
import app.gifitup.shared.presentation.onboarding.OnboardingState

internal const val ROUTE_ONBOARDING = "onboarding"
internal const val ROUTE_FRIENDS = "friends"
internal const val ROUTE_PROFILE = "profile"
internal const val ROUTE_CONTACT = "contact"
internal const val ROUTE_NEW_CHAT = "new-chat"
internal const val ROUTE_CONVERSATION = "conversation"

/**
 * The landing screen once the account is ready (#217): the chat list with its filter, the round "+" and the burger menu, and
 * on a wide window the open conversation beside it. It lives in the onboarding route, whose ViewModel it uses.
 */
@Composable
internal fun ChatHome(
    model: OnboardingViewModel,
    nav: NavHostController,
    notice: (@Composable () -> Unit)?,
) {
    BoxWithConstraints {
        // A wide window (the web, a tablet) shows the list and the open conversation side by side.
        val wide = maxWidth >= WIDE_MIN_WIDTH
        val open = { info: GroupInfo ->
            model.openConversation(info)
            if (!wide) nav.navigate(ROUTE_CONVERSATION)
        }
        val menu = MenuActions({ nav.navigate(ROUTE_PROFILE) }, { nav.navigate(ROUTE_INVITES) }, { nav.navigate(ROUTE_FRIENDS) })
        val actions = ChatListActions({ nav.navigate(ROUTE_NEW_CHAT) }, menu, open) { openProfile(model, nav, it) }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                ChatListScreen(model.chats, actions, Modifier.width(LIST_PANE_WIDTH), model.conversation?.id, notice)
                VerticalDivider()
                OpenConversation(model, nav, Modifier.weight(1f))
            }
        } else {
            ChatListScreen(model.chats, actions, notice = notice)
        }
    }
}

/** Opens [username]'s profile; the name goes in the ViewModel, never in the route. */
internal fun openProfile(
    model: OnboardingViewModel,
    nav: NavHostController,
    username: String,
) {
    model.openProfile(username)
    nav.navigate(ROUTE_CONTACT)
}

/**
 * The other chat routes. The onboarding entry stays on the back stack below them, so its ViewModel (API, session, engine, the
 * in-memory history) is the one they use; leaving a route drops that screen's state, not the history.
 */
internal fun NavGraphBuilder.chatRoutes(
    nav: NavHostController,
    origin: String,
    glyphs: GlyphSupport,
) {
    composable(ROUTE_PROFILE) { entry ->
        val parent = remember(entry) { nav.getBackStackEntry(ROUTE_ONBOARDING) }
        val model = viewModel<OnboardingViewModel>(parent, key = "onboarding-$origin")
        val signedIn by model.holder.state.collectAsState()
        val leave = { if (nav.currentDestination?.route == ROUTE_PROFILE) nav.popBackStack() }
        (signedIn as? OnboardingState.Done)?.let { ProfileScreen(it.username, model.contacts, glyphs) { leave() } }
    }
    composable(ROUTE_CONTACT) { entry ->
        val parent = remember(entry) { nav.getBackStackEntry(ROUTE_ONBOARDING) }
        val model = viewModel<OnboardingViewModel>(parent, key = "onboarding-$origin")
        val username = model.contactProfile
        val leave = { if (nav.currentDestination?.route == ROUTE_CONTACT) nav.popBackStack() }
        // A process that was recreated has no open profile: back.
        if (username == null) LaunchedEffect(Unit) { leave() } else ContactProfileScreen(username, model.contacts, glyphs) { leave() }
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
        if (holder == null) {
            LaunchedEffect(Unit) { leave() }
        } else {
            ConversationScreen(
                holder,
                model.tiles,
                PickerParts(model.picker, model.previews),
                onOpenProfile = { openProfile(model, nav, it) },
                onBack = { leave() },
            )
        }
    }
}

/** The right pane of the wide layout: the conversation the list opened, or a hint. */
@Composable
private fun OpenConversation(
    model: OnboardingViewModel,
    nav: NavHostController,
    modifier: Modifier,
) {
    val holder = model.conversation
    Box(modifier.fillMaxSize()) {
        if (holder == null) {
            Text(ConversationTexts.PICK_CHAT, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            key(holder) {
                ConversationScreen(
                    holder,
                    model.tiles,
                    PickerParts(model.picker, model.previews),
                    onOpenProfile = { openProfile(model, nav, it) },
                    onBack = null,
                )
            }
        }
    }
}

private val WIDE_MIN_WIDTH = 840.dp
private val LIST_PANE_WIDTH = 360.dp
