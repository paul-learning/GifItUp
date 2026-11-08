package app.gifitup.cli

import app.gifitup.shared.data.network.Session
import app.gifitup.shared.domain.auth.AuthResult
import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.events.LiveUpdates
import app.gifitup.shared.domain.events.SocketConnector
import app.gifitup.shared.domain.events.Wake
import app.gifitup.shared.domain.session.SessionHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.PrintStream
import java.util.Base64

/**
 * `chat-listen` (ADR-0015, #27): the app's [LiveUpdates] loop over the real connector, for the end-to-end test. A thin
 * shell: it holds the one session the command logged in with, waits for the first wake about [group] and prints one
 * fixed line. Never the token, a frame or a failure's text.
 */
internal class ListenCommand(
    private val connector: SocketConnector,
    private val out: PrintStream,
) {
    suspend fun run(
        session: Session,
        group: ByteArray,
        timeoutSeconds: Long,
    ): Int =
        coroutineScope {
            val known = KeyBytes(group)
            val holder = SessionHolder({ AuthResult.Ok(session) }, this, { System.currentTimeMillis() / MILLIS })
            val live = LiveUpdates(connector, holder, this, { it == known }, System::currentTimeMillis)
            val done = CompletableDeferred<Int>()
            launch {
                live.status.first { it.connected }
                out.println("listening")
            }
            launch {
                out.println(line(live.nextWake()))
                done.complete(EXIT_OK)
            }
            launch {
                val stopped = live.status.first { it.stopped != null }.stopped
                out.println("stopped: ${stopped?.name?.lowercase()}")
                done.complete(EXIT_REFUSED)
            }
            live.start()
            val code =
                withTimeoutOrNull(timeoutSeconds * MILLIS) { done.await() } ?: run {
                    out.println("no hint within $timeoutSeconds s")
                    EXIT_FAILURE
                }
            live.stop()
            // The three watchers above wait for events that will not come now; this scope ends only when they do.
            coroutineContext.cancelChildren()
            code
        }

    private fun line(wake: Wake): String =
        when {
            wake.groups.isNotEmpty() -> {
                "hint for group " +
                    Base64.getUrlEncoder().withoutPadding().encodeToString(wake.groups.first().bytes)
            }

            wake.welcomes -> {
                "hint for welcomes"
            }

            else -> {
                "hint for everything"
            }
        }

    private companion object {
        const val MILLIS = 1000L
    }
}
