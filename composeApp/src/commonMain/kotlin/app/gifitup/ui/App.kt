package app.gifitup.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.gifitup.AppGraph
import app.gifitup.BuildDefaults
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.storage.LocalStateException
import app.gifitup.shared.domain.auth.AuthError
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.presentation.onboarding.BlockReason
import app.gifitup.shared.presentation.onboarding.OnboardingState
import app.gifitup.shared.presentation.onboarding.OriginView
import app.gifitup.shared.presentation.settings.ServerSettingsStateHolder

/**
 * Root composable shared by Android and Web (ADR-0013). [inbox] holds an invite link the platform took from its entry
 * point; the onboarding takes it once, in memory, and it never becomes a route, a route argument or saved state.
 */
@Composable
fun App(
    graph: AppGraph,
    inbox: InviteInbox = remember { InviteInbox(null) },
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val storage = graph.deviceStorage
            if (storage == null) Text("GifItUp can't open its storage here.") else Configured(graph, storage, inbox)
        }
    }
}

/** What the origin setting said; a storage failure is a state, never a crash (#131 SEC-16). */
private sealed interface Loaded {
    class Ready(
        val origin: ServerOriginSetting.Current,
    ) : Loaded

    data object StorageFailed : Loaded
}

@Composable
private fun Configured(
    graph: AppGraph,
    storage: DeviceStorage,
    inbox: InviteInbox,
) {
    val setting = remember(storage) { storage.originSetting(BuildDefaults.DEFAULT_ORIGIN) }
    var originVersion by remember { mutableIntStateOf(0) }
    val mls by produceState<MlsClientFactory?>(null) { value = graph.mlsClientFactory() }
    val loaded by produceState<Loaded?>(null, originVersion) {
        value =
            try {
                Loaded.Ready(setting.current())
            } catch (ignored: LocalStateException) {
                Loaded.StorageFailed
            }
    }
    val factory = mls
    val origin = (loaded as? Loaded.Ready)?.origin
    when {
        loaded == Loaded.StorageFailed -> {
            Text(Texts.error(AuthError.Local.STORAGE_FAILED))
        }

        origin == ServerOriginSetting.Current.Unreadable -> {
            Text(Texts.blocked(BlockReason.STATE_INVALID))
        }

        origin is ServerOriginSetting.Current.Origin && factory != null -> {
            Navigation(
                services = Services(graph, storage, factory, setting),
                current = OriginView(origin.origin, origin.custom),
                inbox = inbox,
            ) {
                // A new server never picks up the link that came with the old one (#131 SEC-14).
                inbox.drop()
                originVersion++
            }
        }

        else -> {
            CircularProgressIndicator()
        }
    }
}

/** What the screens need besides the origin. */
private class Services(
    val graph: AppGraph,
    val storage: DeviceStorage,
    val mls: MlsClientFactory,
    val setting: ServerOriginSetting,
)

@Composable
private fun Navigation(
    services: Services,
    current: OriginView,
    inbox: InviteInbox,
    onOriginChanged: () -> Unit,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
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
    NavHost(navController = nav, startDestination = ROUTE_ONBOARDING) {
        composable(ROUTE_ONBOARDING) {
            // One ViewModel per origin: a new server gets a new flow with its own engine, API and session.
            val model =
                viewModel(key = "onboarding-${current.origin}") {
                    OnboardingViewModel(services.graph, services.storage, services.mls, current.origin, services.setting)
                }
            val state by model.holder.state.collectAsState()
            LaunchedEffect(state is OnboardingState.EnterInvite) {
                if (state is OnboardingState.EnterInvite) inbox.take()?.let(model.holder::submitInvite)
            }
            OnboardingScreen(state, model.holder, current) { nav.navigate(ROUTE_SETTINGS) }
        }
        composable(ROUTE_SETTINGS) {
            val holder =
                remember {
                    ServerSettingsStateHolder(scope, services.setting, onOriginChanged)
                }
            SettingsScreen(holder, current.origin, services.setting.default) {
                // Only from Settings: a second tap must not pop the start destination and leave an empty back stack.
                if (nav.currentDestination?.route == ROUTE_SETTINGS) nav.popBackStack()
            }
        }
    }
}

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_SETTINGS = "settings"
