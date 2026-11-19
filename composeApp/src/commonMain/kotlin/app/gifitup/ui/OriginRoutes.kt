package app.gifitup.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.gifitup.AppGraph
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.presentation.onboarding.OnboardingState
import app.gifitup.shared.presentation.onboarding.OriginView
import app.gifitup.shared.presentation.settings.ServerSettingsStateHolder
import kotlinx.coroutines.CoroutineScope

// The destinations of the navigation graph, one function each (#162).

/**
 * Above the landing list: a note while some setup is pending. The custom-server banner is not here any more (#217, owner
 * decision 2026-11-02): the list's top bar carries a small marker and the Profile screen shows the server (ADR-0013).
 */
@Composable
private fun HomeNotice(state: OnboardingState.Done) {
    if (state.keyPackagesUploaded == false) {
        Text(ChatTexts.SETUP_PENDING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the screens need besides the origin. */
internal class Services(
    val graph: AppGraph,
    val storage: DeviceStorage,
    val mls: MlsClientFactory,
    val setting: ServerOriginSetting,
    val export: LogExport,
)

/**
 * The view-model key of an origin. One ViewModel per origin: a new server gets a new flow with its own engine, API and session.
 * The string says "onboarding" because the view model began as the onboarding screen's; it is in memory only, and every use goes
 * through here so the keys can't drift apart.
 */
private fun originKey(origin: String) = "onboarding-$origin"

/**
 * The origin's view model, for every destination but the first. The onboarding entry stays on the back stack below them, so its
 * ViewModel (API, session, engine) is the one they use.
 */
@Composable
internal fun originModel(
    nav: NavHostController,
    entry: NavBackStackEntry,
    origin: String,
): OriginViewModel {
    val parent = remember(entry) { nav.getBackStackEntry(ROUTE_ONBOARDING) }
    return viewModel<OriginViewModel>(parent, key = originKey(origin))
}

/** The start destination: the onboarding while there is no account, the chat list once the account is ready (#217). */
internal fun NavGraphBuilder.onboardingRoute(
    nav: NavHostController,
    services: Services,
    current: OriginView,
    inbox: InviteInbox,
    appLifecycle: Lifecycle,
) {
    composable(ROUTE_ONBOARDING) {
        // One ViewModel per origin: a new server gets a new flow with its own engine, API and session.
        val model =
            viewModel(key = originKey(current.origin)) {
                OriginViewModel(services.graph, services.storage, services.mls, current.origin, services.setting)
            }
        val state by model.holder.state.collectAsState()
        LaunchedEffect(model, appLifecycle) { model.bindLifecycle(appLifecycle) }
        LaunchedEffect(state is OnboardingState.EnterInvite) {
            if (state is OnboardingState.EnterInvite) inbox.take()?.let(model.holder::submitInvite)
        }
        val signedIn = state as? OnboardingState.Done
        if (signedIn != null) {
            // The account is ready: the chat list is the landing screen (#217).
            ChatHome(model, nav, current.custom) { HomeNotice(signedIn) }
        } else {
            OnboardingScreen(
                state,
                model.holder,
                current,
                onOpenSettings = if (services.graph.serverSettingsEditable) ({ nav.navigate(ROUTE_SETTINGS) }) else null,
            )
        }
    }
}

internal fun NavGraphBuilder.invitesRoute(
    nav: NavHostController,
    services: Services,
    current: OriginView,
    screen: ScreenProtection,
) {
    composable(ROUTE_INVITES) { entry ->
        val model = originModel(nav, entry, current.origin)
        CreateInviteScreen(model.invites, services.graph.clipboard.clearsCopied, screen) {
            if (nav.currentDestination?.route == ROUTE_INVITES) nav.popBackStack()
        }
    }
}

internal fun NavGraphBuilder.friendsRoute(
    nav: NavHostController,
    services: Services,
    current: OriginView,
) {
    composable(ROUTE_FRIENDS) { entry ->
        val model = originModel(nav, entry, current.origin)
        FriendsScreen(model.contacts, services.graph.glyphs) {
            if (nav.currentDestination?.route == ROUTE_FRIENDS) nav.popBackStack()
        }
    }
}

/** About and diagnostics (#154 follow-up), over the onboarding entry's ViewModel like the other signed-in screens. */
internal fun NavGraphBuilder.diagnosticsRoute(
    nav: NavHostController,
    services: Services,
    current: OriginView,
    screen: ScreenProtection,
) {
    composable(ROUTE_DIAGNOSTICS) { entry ->
        val model = originModel(nav, entry, current.origin)
        DiagnosticsScreen(model.diagnosticsScreen, services.export, services.graph.clipboard, screen) {
            if (nav.currentDestination?.route == ROUTE_DIAGNOSTICS) nav.popBackStack()
        }
    }
}

/** Not registered where the server can't be chosen (the web, #159): there is no route to reach. */
internal fun NavGraphBuilder.settingsRoute(
    nav: NavHostController,
    services: Services,
    current: OriginView,
    scope: CoroutineScope,
    onOriginChanged: () -> Unit,
) {
    if (!services.graph.serverSettingsEditable) return
    composable(ROUTE_SETTINGS) {
        val holder = remember { ServerSettingsStateHolder(scope, services.setting, onOriginChanged) }
        SettingsScreen(holder, current.origin, services.setting.default) {
            // Only from Settings: a second tap must not pop the start destination and leave an empty back stack.
            if (nav.currentDestination?.route == ROUTE_SETTINGS) nav.popBackStack()
        }
    }
}

private const val ROUTE_SETTINGS = "settings"
internal const val ROUTE_INVITES = "invites"
