package app.gifitup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gifitup.AppGraph
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.domain.auth.DeviceAuth
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.invite.CreateInvite
import app.gifitup.shared.domain.invite.ListInvites
import app.gifitup.shared.domain.invite.RevokeInvite
import app.gifitup.shared.domain.origin.ServerOriginSetting
import app.gifitup.shared.domain.session.SessionHolder
import app.gifitup.shared.presentation.invite.CreateInviteStateHolder
import app.gifitup.shared.presentation.onboarding.OnboardingStateHolder
import kotlin.time.Clock

/**
 * The onboarding, and after it the invite screen, for one server origin (ADR-0013). It owns everything bound to that
 * origin, the HTTP engine, the API, the identity use cases and the session, so they live exactly as long as the flow
 * that uses them: a rotation keeps them, and [onCleared] closes them (#131 SEC-15). It keeps nothing in saved state:
 * the invite code lives in the holder's memory only, and a process death starts the flow again (a pending
 * registration is finished without it). The invite link of the create-invite screen is in memory only too.
 */
internal class OnboardingViewModel(
    graph: AppGraph,
    storage: DeviceStorage,
    mls: MlsClientFactory,
    origin: String,
    setting: ServerOriginSetting,
) : ViewModel() {
    private val engine = graph.engine()
    private val api = GifItUpApi(engine, checkNotNull(ApiEndpoint.forOrigin(origin)) { "the origin setting only holds canonical origins" })
    private val auth = DeviceAuth(storage, api, mls, graph.random)

    private val sessions = SessionHolder({ auth.logIn() }, viewModelScope) { Clock.System.now().epochSeconds }

    val holder =
        OnboardingStateHolder(
            scope = viewModelScope,
            origin = setting,
            identities = storage.identities,
            register = auth.register,
            refill = auth.refillKeyPackages,
            sessions = sessions,
        )

    /** The create-invite screen's logic, over the same session, API and engine (closed with them). */
    val invites =
        CreateInviteStateHolder(viewModelScope, sessions, CreateInvite(api), ListInvites(api), RevokeInvite(api), graph.clipboard)

    init {
        holder.start()
    }

    override fun onCleared() {
        invites.leave() // the link leaves memory and the clipboard before the API closes
        api.close()
        engine.close()
    }
}
