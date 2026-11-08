package app.gifitup.shared.presentation.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Turns the live socket on and off in the order the app's lifecycle asked (#27, #274 SEC-1). Every change goes through one
 * queue and one worker, so a quick background-and-back can never run a start in the middle of the stop before it: the last
 * request always wins, and a request for the state it is already in does nothing.
 */
class LiveSwitch(
    scope: CoroutineScope,
    private val open: suspend () -> Unit,
    private val close: suspend () -> Unit,
) {
    private val requests = Channel<Boolean>(Channel.UNLIMITED)
    private var isOn = false

    init {
        scope.launch {
            for (wanted in requests) {
                if (wanted == isOn) continue
                isOn = wanted
                if (wanted) open() else close()
            }
        }
    }

    /** Asks for the socket to be on ([wanted] `true`) or off. Returns at once. */
    fun set(wanted: Boolean) {
        requests.trySend(wanted)
    }
}
