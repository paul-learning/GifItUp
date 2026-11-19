package app.gifitup.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.gifitup.AppGraph
import app.gifitup.BuildDefaults
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.settings.ScreenProtectionSetting
import app.gifitup.shared.presentation.onboarding.BlockReason
import app.gifitup.shared.presentation.onboarding.OriginView
import app.gifitup.shared.presentation.settings.HeldShare
import app.gifitup.shared.presentation.settings.ScreenProtectionStateHolder
import app.gifitup.shared.presentation.start.AppStartState
import app.gifitup.shared.presentation.start.LoadAppStart
import kotlinx.coroutines.CoroutineScope

/**
 * Root composable shared by Android and Web (ADR-0013). [inbox] holds an invite link the platform took from its entry
 * point; the onboarding takes it once, in memory, and it never becomes a route, a route argument or saved state.
 */
@Composable
fun App(
    graph: AppGraph,
    inbox: InviteInbox = remember { InviteInbox(null) },
    screen: ScreenProtection = ScreenProtection.None,
    export: LogExport = LogExport.None,
) {
    GifItUpTheme {
        ProvideGlyphs(graph.glyphs) {
            // The window is edge to edge on Android: the surface paints to the edges, the content stays out of the status bar, the
            // navigation bar, a display cutout and the keyboard (#217). On the web the insets are zero.
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val storage = graph.deviceStorage
                    if (storage == null) Text("GifItUp can't open its storage here.") else Configured(graph, storage, inbox, screen, export)
                }
            }
        }
    }
}

@Composable
private fun Configured(
    graph: AppGraph,
    storage: DeviceStorage,
    inbox: InviteInbox,
    screen: ScreenProtection,
    export: LogExport,
) {
    val setting = remember(storage) { storage.originSetting(BuildDefaults.DEFAULT_ORIGIN, graph.serverSettingsEditable) }
    var originVersion by remember { mutableIntStateOf(0) }
    val mls by produceState<MlsClientFactory?>(null) { value = graph.mlsClientFactory() }
    val start by produceState<AppStartState>(AppStartState.Loading, originVersion) { value = LoadAppStart(setting)() }
    val factory = mls
    when (val state = start) {
        AppStartState.StorageFailed -> {
            Text(Texts.error(AuthError.Local.STORAGE_FAILED))
        }

        AppStartState.StateInvalid -> {
            Text(Texts.blocked(BlockReason.STATE_INVALID))
        }

        is AppStartState.Ready -> {
            if (factory == null) {
                CircularProgressIndicator()
            } else {
                Navigation(
                    services = Services(graph, storage, factory, setting, export),
                    current = state.origin,
                    inbox = inbox,
                    screen = screen,
                ) {
                    // A new server never picks up the link that came with the old one (#131 SEC-14).
                    inbox.drop()
                    originVersion++
                }
            }
        }

        AppStartState.Loading -> {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun Navigation(
    services: Services,
    current: OriginView,
    inbox: InviteInbox,
    screen: ScreenProtection,
    onOriginChanged: () -> Unit,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    // The app's own lifecycle (not a destination's: a destination stops when another opens on top of it), for the live socket.
    val appLifecycle = LocalLifecycleOwner.current.lifecycle
    var shownOrigin by rememberSaveable {
        mutableStateOf(current.origin)
    } // survives a rotation like the back stack (#131 SEC-26); not a secret
    // Once the new origin has loaded (not before, #131 SEC-25): a new onboarding entry replaces the old one, and popping
    // the old one clears its ViewModel, which closes the old origin's engine, API and session (#131 SEC-23).
    LaunchedEffect(current.origin) {
        if (current.origin != shownOrigin) {
            shownOrigin = current.origin
            nav.navigate(ROUTE_ONBOARDING) { popUpTo(ROUTE_ONBOARDING) { inclusive = true } }
        }
    }
    val protectionControls = rememberScreenProtection(screen, services.storage, scope)
    NavHost(navController = nav, startDestination = ROUTE_ONBOARDING) {
        onboardingRoute(nav, services, current, inbox, appLifecycle)
        invitesRoute(nav, services, current, screen)
        chatRoutes(nav, current, services.graph.glyphs, protectionControls)
        diagnosticsRoute(nav, services, current, screen)
        friendsRoute(nav, services, current)
        settingsRoute(nav, services, current, scope, onOriginChanged)
    }
}

/**
 * The app's windows stay out of screenshots, recordings and the recents thumbnail unless the user switched that off (#281). On
 * from the first frame (the Activity's start-up hold); the stored choice is read right after, here, once the navigation is
 * composed, and then the hold is let go. Returns the switch's controls, or `null` where the platform can't do it (the web).
 */
@Composable
private fun rememberScreenProtection(
    screen: ScreenProtection,
    storage: DeviceStorage,
    scope: CoroutineScope,
): ProtectionControls? {
    // Nothing to protect, read or show where the platform has no such protection (the web).
    if (!screen.supported) return null
    val holder = remember(storage) { ScreenProtectionStateHolder(scope, ScreenProtectionSetting(storage.store)) }
    LaunchedEffect(holder) { holder.load() }
    val protect by holder.on.collectAsState()
    val settled by holder.settled.collectAsState()
    // The start-up hold of the Activity (protected before the first frame) ends once the stored choice is known.
    LaunchedEffect(settled) { if (settled) screen.settled() }
    // One share of the window's protection, taken and let go of by the state (never by an `onDispose` that reads a delegated state
    // property: that reads the *new* value, not the one the effect was made with, and the share was never released).
    val share = remember(screen) { HeldShare({ screen.protect(true) }, { screen.protect(false) }) }
    val wanted = protect // the value of this composition, not whatever the state is when the effect runs
    SideEffect { share.set(wanted) }
    DisposableEffect(share) { onDispose { share.set(false) } }
    return ProtectionControls.of(holder)
}
