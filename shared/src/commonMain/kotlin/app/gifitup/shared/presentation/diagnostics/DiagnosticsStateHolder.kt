package app.gifitup.shared.presentation.diagnostics

import app.gifitup.shared.domain.diagnostics.DiagInfo
import app.gifitup.shared.domain.diagnostics.DiagnosticsLog
import app.gifitup.shared.domain.diagnostics.DiagnosticsReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the diagnostics screen shows: the version line and the full report, or `null` until it is built. */
data class DiagnosticsState(
    val version: String,
    val report: String? = null,
)

/**
 * The diagnostics screen's logic. The report is built on demand from the closed event log and [info] (fixed words and
 * numbers), shown whole before anything leaves the device, and kept only in this state: it is dropped with the screen.
 * Sharing, saving and copying are the screen's platform hand-offs; this holder never sends anything.
 */
class DiagnosticsStateHolder(
    private val scope: CoroutineScope,
    private val log: DiagnosticsLog,
    private val info: () -> DiagInfo,
    private val nowSeconds: () -> Long,
) {
    private val flow = MutableStateFlow(DiagnosticsState(info().version))
    val state: StateFlow<DiagnosticsState> = flow.asStateFlow()

    private var open = true

    /** Builds the report from the log as it is now. */
    fun refresh() {
        open = true
        scope.launch { show(DiagnosticsReport.render(info(), log.entries(), nowSeconds())) }
    }

    /** Forgets the log, then shows the new, nearly empty report. */
    fun clear() {
        scope.launch {
            log.clear()
            show(DiagnosticsReport.render(info(), log.entries(), nowSeconds()))
        }
    }

    /** Sets the report only while the screen is open and from the state as it is now, so a screen left meanwhile stays empty. */
    private fun show(report: String) {
        if (open) flow.update { it.copy(report = report) }
    }

    /** The screen is left: the report goes out of memory. */
    fun leave() {
        open = false
        flow.update { it.copy(report = null) }
    }
}
