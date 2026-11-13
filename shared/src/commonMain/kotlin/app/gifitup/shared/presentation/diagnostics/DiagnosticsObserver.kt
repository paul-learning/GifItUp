package app.gifitup.shared.presentation.diagnostics

import app.gifitup.shared.domain.contacts.KeyBytes
import app.gifitup.shared.domain.diagnostics.DiagDetail
import app.gifitup.shared.domain.diagnostics.DiagEvent
import app.gifitup.shared.domain.diagnostics.DiagnosticsLog
import app.gifitup.shared.domain.diagnostics.diag
import app.gifitup.shared.domain.events.LiveState
import app.gifitup.shared.domain.events.LiveStatus
import app.gifitup.shared.presentation.chat.ChatProblem
import app.gifitup.shared.presentation.chat.GifTile
import app.gifitup.shared.presentation.chat.SendProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns what the state holders already say, closed enums and a flag, into [DiagEvent]s. It never sees a message, a name or a
 * group: only the kind of problem a screen shows, so the log stays as coarse as the screens are (#154 follow-up, plan
 * review SEC-2/SEC-4). A new watch needs a reviewer to check that the event's moment says nothing about a contact.
 */
class DiagnosticsObserver(
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
) {
    /** A change of the socket's state: connected, or lost after it was connected (the first "not connected" is not news). */
    fun watchSocket(connected: Flow<Boolean>) {
        scope.launch {
            var was = false
            connected.distinctUntilChanged().collect { now ->
                if (now) {
                    log.record(DiagEvent.SOCKET_CONNECTED)
                } else if (was) {
                    log.record(DiagEvent.SOCKET_LOST)
                }
                was = now
            }
        }
    }

    /** The chat list's problem, when there is one. */
    fun watchList(problem: Flow<ChatProblem?>) {
        scope.launch {
            problem.distinctUntilChanged().collect { p ->
                when (p) {
                    ChatProblem.OFFLINE -> log.record(DiagEvent.SYNC_FAILED_NETWORK)
                    ChatProblem.FAILED, ChatProblem.THROTTLED -> log.record(DiagEvent.SYNC_FAILED_SERVER)
                    ChatProblem.HISTORY -> log.record(DiagEvent.HISTORY_PROBLEM)
                    ChatProblem.NOT_SIGNED_IN -> log.record(DiagEvent.LOGIN_FAILED)
                    null -> Unit
                }
            }
        }
    }

    /** A send that did not go: not the caption rule (the user's own input), everything else. */
    fun watchSend(problem: Flow<SendProblem?>): Job =
        scope.launch {
            problem.distinctUntilChanged().collect { p ->
                if (p != null && p != SendProblem.CAPTION && p != SendProblem.IN_PROGRESS) log.record(DiagEvent.SEND_FAILED)
            }
        }

    /** Live updates waiting to retry, and why they ended for good. (Connected and lost are [watchSocket].) */
    fun watchLive(status: Flow<LiveStatus>) {
        scope.launch {
            status.map { it.state to it.stopped }.distinctUntilChanged().collect { (state, stopped) ->
                if (state == LiveState.WAITING) log.record(DiagEvent.SOCKET_STATE, DiagDetail.LIVE_WAITING)
                stopped?.let { log.record(DiagEvent.SOCKET_STOPPED, it.diag()) }
            }
        }
    }

    /**
     * A GIF that failed to load, with the closed reason. Only the first failure of a bubble counts (the tiles are compared in
     * memory by their key, which never leaves this function), so a refresh doesn't note it again.
     */
    fun watchMedia(tiles: Flow<Map<KeyBytes, GifTile>>): Job =
        scope.launch {
            var failed = emptySet<KeyBytes>()
            tiles.collect { map ->
                val now = map.filterValues { it is GifTile.Unavailable }
                now.forEach { (key, tile) ->
                    if (key !in failed) log.record(DiagEvent.MEDIA_FAILED, (tile as GifTile.Unavailable).reason.diag())
                }
                failed = now.keys
            }
        }
}
