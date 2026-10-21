package app.gifitup.ui

import app.gifitup.AppGraph
import app.gifitup.shared.data.crypto.MlsClientFactory
import app.gifitup.shared.data.network.ApiEndpoint
import app.gifitup.shared.data.network.GifItUpApi
import app.gifitup.shared.domain.auth.DeviceAuth
import app.gifitup.shared.domain.auth.DeviceStorage
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock

/**
 * Everything that is bound to one server origin (ADR-0013): the HTTP engine, the API, the identity use cases and the
 * session. Built again when the origin changes, so nothing keeps talking to the old one; [close] releases the engine.
 */
internal class AppServices(
    graph: AppGraph,
    storage: DeviceStorage,
    mls: MlsClientFactory,
    val origin: String,
    scope: CoroutineScope,
) : AutoCloseable {
    private val engine = graph.engine()
    val api = GifItUpApi(engine, checkNotNull(ApiEndpoint.forOrigin(origin)) { "the origin setting only holds canonical origins" })
    val auth = DeviceAuth(storage, api, mls, graph.random)
    val sessions = SessionHolder({ auth.logIn() }, scope) { Clock.System.now().epochSeconds }

    override fun close() {
        api.close()
        engine.close()
    }
}
