package app.gifitup.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.gifitup.AppGraph
import app.gifitup.BuildDefaults
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.presentation.onboarding.BlockReason
import app.gifitup.shared.presentation.onboarding.OnboardingState

/**
 * Root composable shared by Android and Web (ADR-0013). [incomingInvite] is an invite link the platform took from
 * its entry point (the web page's address); it is handed to the onboarding once, in memory, and never becomes a
 * route, a route argument or saved state.
 */
@Composable
fun App(
    graph: AppGraph,
    incomingInvite: String? = null,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val storage = graph.deviceStorage
            if (storage == null) Text("GifItUp can't open its storage here.") else Configured(graph, storage, incomingInvite)
        }
    }
}

@Composable
private fun Configured(
    graph: AppGraph,
    storage: DeviceStorage,
    incomingInvite: String?,
) {
    val setting = remember(storage) { storage.originSetting(BuildDefaults.DEFAULT_ORIGIN) }
    var originVersion by remember { mutableIntStateOf(0) }
    val mls by produceState<MlsClientFactory?>(null) { value = graph.mlsClientFactory() }
    val current by produceState<ServerOriginSetting.Current?>(null, originVersion) { value = setting.current() }
    val factory = mls
    when (val origin = current) {
        null -> {
            CircularProgressIndicator()
        }

        ServerOriginSetting.Current.Unreadable -> {
            Text(Texts.blocked(BlockReason.STATE_INVALID))
        }

        is ServerOriginSetting.Current.Origin -> {
            if (factory == null) {
                CircularProgressIndicator()
            } else {
                Navigation(graph, storage, factory, setting, origin.origin, incomingInvite) { originVersion++ }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // The composition's inputs, passed once.
private fun Navigation(
    graph: AppGraph,
    storage: DeviceStorage,
    mls: MlsClientFactory,
    setting: ServerOriginSetting,
    origin: String,
    incomingInvite: String?,
    onOriginChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val services = remember(origin) { AppServices(graph, storage, mls, origin, scope) }
    DisposableEffect(services) { onDispose { services.close() } }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = ROUTE_ONBOARDING) {
        composable(ROUTE_ONBOARDING) {
            // Keyed by origin: a new server gets a new flow, nothing of the old one is reused.
            val model = viewModel(key = "onboarding-$origin") { OnboardingViewModel(services, setting, storage) }
            val state by model.holder.state.collectAsState()
            var pendingInvite by remember { mutableStateOf(incomingInvite) }
            LaunchedEffect(state is OnboardingState.EnterInvite) {
                val link = pendingInvite
                if (state is OnboardingState.EnterInvite && link != null) {
                    pendingInvite = null
                    model.holder.submitInvite(link)
                }
            }
            OnboardingScreen(state, model.holder) { nav.navigate(ROUTE_SETTINGS) }
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                setting = setting,
                currentOrigin = origin,
                onChanged = {
                    nav.popBackStack()
                    onOriginChanged()
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_SETTINGS = "settings"
