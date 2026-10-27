package io.embodit.tracelight.ui.trace

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.tracelight.data.AnchorGateway
import io.embodit.tracelight.data.AnchorOutcome
import io.embodit.tracelight.data.TraceStore
import io.embodit.tracelight.domain.CornerDraft
import io.embodit.tracelight.domain.FrameSnap
import io.embodit.tracelight.domain.HeadPose
import io.embodit.tracelight.domain.PaperFit
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.Settings
import io.embodit.tracelight.domain.Sheet
import io.embodit.tracelight.domain.SheetRect
import io.embodit.tracelight.platform.TraceGraph
import io.embodit.tracelight.platform.TraceLog
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The behaviour of the alignment flow and the calibration page (design `states` S00 - S06, S16, S18,
 * S20; the spike build of `preconditions.deviceSpike`). Events from the panels and the scene come in
 * through [onEvent], the state goes out through [state].
 */
class TraceViewModel(
    private val anchors: AnchorGateway,
    private val store: TraceStore,
    private val head: () -> HeadPose?,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(TraceUiState(settings = store.loadSettings()))
    val state: StateFlow<TraceUiState> = _state

    private var deskJob: Job? = null
    private var alignedJob: Job? = null
    private var anchorJob: Job? = null
    private var lastLogged: String? = null

    /** Where the flow goes after S06 (design `returnTo`): the spike build always opens the calibration page. */
    private var returnTo = "S20"

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: TraceEvent) {
        val s = _state.value
        if (e !is TraceEvent.GhostChanged && e !is TraceEvent.DeskChanged && e !is TraceEvent.ControllerChanged && e !is TraceEvent.PaperSeen) TraceLog.i("UI ${e.name()} state=${s.designState}")
        when (e) {
            TraceEvent.SceneReady -> {
                update { it.copy(sceneReady = true) }
                // S00 -> S01 (first run; the spike build keeps no sheet between launches)
                if (s.designState == "S00") go("S01")
            }
            is TraceEvent.DeskChanged -> {
                if (e.found == s.deskFound) return
                update { it.copy(deskFound = e.found) }
                if (e.found && s.designState in setOf("S02", "S16")) go("S03")
            }
            TraceEvent.ReadyClicked -> if (s.designState == "S01") findDesk()
            TraceEvent.RetryDeskClicked -> if (s.designState == "S16") findDesk()
            is TraceEvent.GhostChanged -> if (s.ghostOnDesk != e.onDesk || s.ghostFrozen != e.frozen) update { it.copy(ghostOnDesk = e.onDesk, ghostFrozen = e.frozen) }
            is TraceEvent.FrameDropped -> if (s.designState == "S03") drop(e.pose)
            is TraceEvent.CornerMoved -> moveCorner(e.index, e.at)
            is TraceEvent.PaperSeen -> if (s.found != e.rect || s.foundStable != e.stable) update { it.copy(found = e.rect, foundStable = e.stable && e.rect != null) }
            TraceEvent.AutoAcceptClicked -> {
                val r = s.found ?: return
                if (s.designState != "S03" || !s.foundStable) return
                TraceLog.i(String.format(Locale.US, "AUTO accept size=%.0fx%.0f mm center=%s yaw=%.1f", r.widthM * 1000f, r.heightM * 1000f, TraceLog.pos(r.pose.center), Math.toDegrees(r.pose.yaw.toDouble())))
                aligned(r.pose, r.widthM, r.heightM)
            }
            TraceEvent.AutoAdjustClicked -> {
                val r = s.found ?: return
                if (s.designState != "S03") return
                TraceLog.i("AUTO adjust: corners from the camera, drag any that are off")
                update { it.copy(draft = CornerDraft.of(r).copy(moved = List(4) { true }), activeCorner = 0, confirmed = 0, verdict = null) }
                go("S04")
            }
            is TraceEvent.FrameTurned -> turnFrame(e.dYaw)
            TraceEvent.ConfirmClicked -> confirm()
            TraceEvent.CornerOkClicked -> cornerOk()
            TraceEvent.RestartClicked -> if (s.aligning) restart()
            TraceEvent.CancelClicked -> cancel()
            is TraceEvent.TrackingChanged -> {
                TraceLog.i("TRACK ${if (e.lost) "lost" else "recovered"}")
                update { it.copy(trackingLost = e.lost) }
            }
            is TraceEvent.ControllerChanged -> if (s.controller != e.tracked) update { it.copy(controller = e.tracked) }
            is TraceEvent.AnchorReady -> update { it.copy(anchorId = e.id) }
            TraceEvent.RecordClicked -> record()
            TraceEvent.TestFigureClicked -> update { it.copy(calib = it.calib.copy(testFigure = !it.calib.testFigure)) }.also { spike("figure on=${_state.value.calib.testFigure}") }
            is TraceEvent.GainClicked -> update { it.copy(calib = it.calib.copy(gain = e.gain)) }.also { spike("gain set=${e.gain}") }
            TraceEvent.TestTapClicked -> {
                update { it.copy(calib = it.calib.copy(taps = it.calib.taps + 1)) }
                spike("tap n=${_state.value.calib.taps}")
            }
            is TraceEvent.HeightClicked -> {
                update { it.copy(calib = it.calib.copy(heightMm = (it.calib.heightMm + e.deltaMm).coerceIn(-6, 6))) }
                spike("height mm=${_state.value.calib.heightMm}")
            }
            TraceEvent.NextRoundClicked -> nextRound()
            is TraceEvent.DebugState -> debugJump(e.id)
        }
    }

    // =========================================================================================
    // alignment flow
    // =========================================================================================

    private fun findDesk() {
        go("S02")
        deskJob?.cancel()
        if (_state.value.deskFound) {
            go("S03")
            return
        }
        deskJob =
            scope.launch {
                delay(DESK_TIMEOUT_MS)
                if (_state.value.designState == "S02") {
                    TraceLog.i("DESK none after ${DESK_TIMEOUT_MS / 1000}s")
                    go("S16")
                }
            }
    }

    private fun drop(pose: PaperPose) {
        val s = _state.value
        // re-aligning on the same spot: the frame snaps onto the previous sheet (taste profile D)
        val snapped = if (s.recheck) FrameSnap.snap(pose, s.previous) else null
        val at = snapped ?: pose
        if (snapped != null) TraceLog.i("SNAP frame onto previous")
        TraceLog.i(String.format(Locale.US, "FRAME drop center=%s yaw=%.1f snapped=%s", TraceLog.pos(at.center), Math.toDegrees(at.yaw.toDouble()), snapped != null))
        update { it.copy(draft = CornerDraft.of(at), activeCorner = 0, confirmed = 0, verdict = null) }
        go("S04")
    }

    private fun moveCorner(i: Int, at: io.embodit.tracelight.domain.P2) {
        val s = _state.value
        val d = s.draft ?: return
        if (!s.aligning || i !in 0..3) return
        val next = d.with(i, at)
        // first pass: the next corner in ① ② ③ ④ order that was not touched yet
        val active = if (s.recheck) s.activeCorner else (1..4).map { (i + it) % 4 }.firstOrNull { !next.moved[it] } ?: i
        TraceLog.i(String.format(Locale.US, "CORNER moved i=%d at=(%.4f, %.4f) next=%d", i + 1, at.x, at.z, active + 1))
        update { it.copy(draft = next, activeCorner = active, verdict = if (it.designState == "S05") it.verdict else null) }
    }

    private fun turnFrame(dYaw: Float) {
        val s = _state.value
        val d = s.draft ?: return
        if (!s.aligning) return
        var turned = d.turned(dYaw)
        if (s.recheck) {
            val fit = PaperFit.fit(turned, io.embodit.tracelight.domain.Sheet.A4)
            FrameSnap.snap(fit.pose, s.previous)?.let {
                TraceLog.i("SNAP frame onto previous (turn)")
                turned = CornerDraft.of(it).copy(moved = turned.moved)
            }
        }
        TraceLog.i(String.format(Locale.US, "FRAME turned d=%.1f", Math.toDegrees(dYaw.toDouble())))
        update { it.copy(draft = turned) }
    }

    private fun cornerOk() {
        val s = _state.value
        if (!s.recheck || !s.aligning) return
        val n = (s.confirmed + 1).coerceAtMost(4)
        update { it.copy(confirmed = n, activeCorner = (s.activeCorner + 1) % 4) }
    }

    private fun confirm() {
        val s = _state.value
        val d = s.draft ?: return
        if (!s.aligning || !s.allCornersSet) return
        // any sheet (design change 2026-10-23, user: "任何大小的纸都可以"): the rectangle that fits the four corners
        val viewer = head()?.position?.let { P2.of(it) } ?: d.centroid.let { P2(it.x, it.z + 1f) }
        val rect = SheetRect.fit(d.points, d.y, viewer)
        val v = PaperFit.judge(d)
        TraceLog.i(String.format(Locale.US, "FIT size=%s a4_max=%.1f worst=%d res=%s", rect?.let { String.format(Locale.US, "%.0fx%.0f mm", it.widthM * 1000f, it.heightM * 1000f) } ?: "-", v.a4.maxMm, v.a4.worst + 1, v.a4.residualsMm.joinToString(",") { String.format(Locale.US, "%.1f", it) }))
        if (rect == null) {
            aligned(v.a4.pose, Sheet.A4.w / 1000f, Sheet.A4.h / 1000f)
            return
        }
        aligned(rect.pose, rect.widthM, rect.heightM)
    }

    private fun aligned(pose: PaperPose, w: Float = _state.value.sheetW, h: Float = _state.value.sheetH) {
        update { it.copy(aligned = pose, sheetW = w, sheetH = h, verdict = null, draft = null, recheck = false, previous = null, anchorId = null, found = null, foundStable = false) }
        go("S06")
        makeAnchor(pose)
        alignedJob?.cancel()
        alignedJob =
            scope.launch {
                delay(ALIGNED_MS)
                while (holdTransient) delay(200)
                if (_state.value.designState != "S06") return@launch
                if (returnTo == "S20") startRound() else go(returnTo)
            }
    }

    /** A world anchor at the sheet's centre holds the frame (it follows the platform's tracking corrections). */
    private fun makeAnchor(pose: PaperPose) {
        anchorJob?.cancel()
        anchorJob =
            scope.launch {
                when (val r = anchors.create(pose.center, "sheet")) {
                    is AnchorOutcome.Ok -> {
                        TraceLog.i("ANCHOR sheet id=${r.value.id.take(8)} pos=${TraceLog.pos(r.value.pose.position)}")
                        update { it.copy(anchorId = r.value.id) }
                        removeOthers(r.value.id)
                    }
                    is AnchorOutcome.Failed -> TraceLog.w("ANCHOR sheet failed code=${r.code} msg=${r.message} (frame stays in Stage space)")
                }
            }
    }

    private suspend fun removeOthers(keep: String) {
        val all = anchors.loadAll()
        if (all is AnchorOutcome.Ok) for (a in all.value) if (a.id != keep) anchors.remove(a.id)
    }

    private fun restart() {
        update { it.copy(draft = null, verdict = null, confirmed = 0, activeCorner = 0) }
        go("S03")
    }

    private fun cancel() {
        val s = _state.value
        val prev = s.previous ?: return
        if (s.designState !in setOf("S02", "S03", "S04")) return
        update { it.copy(aligned = prev, previous = null, recheck = false, draft = null, verdict = null) }
        go(returnTo)
    }

    // =========================================================================================
    // calibration page (S20)
    // =========================================================================================

    private fun startRound() {
        update { it.copy(calib = it.calib.copy(startedAtMs = clock(), records = 0, taps = 0)) }
        go("S20")
        spike("round start")
    }

    private fun record() {
        if (_state.value.designState != "S20") return
        update { it.copy(calib = it.calib.copy(records = it.calib.records + 1)) }
        spike("record n=${_state.value.calib.records}")
    }

    private fun nextRound() {
        val s = _state.value
        if (s.designState != "S20") return
        spike("round end")
        // a fresh alignment: the next round must not be helped by the previous one (no snap, no prefill)
        update { it.copy(calib = it.calib.copy(round = it.calib.round + 1, testFigure = false), aligned = null, anchorId = null, draft = null, recheck = false, previous = null) }
        go(if (s.deskFound) "S03" else "S02")
    }

    /** `SPIKE t=mm:ss round= gain= head=` one line per reading, so the voice recording can be lined up. */
    private fun spike(what: String) {
        val s = _state.value
        val c = s.calib
        val t = ((clock() - c.startedAtMs).coerceAtLeast(0L) / 1000L).toInt()
        val h = head()
        TraceLog.i(String.format(Locale.US, "SPIKE t=%02d:%02d round=%d gain=%.1f height=%d %s head=%s fwd=%s", t / 60, t % 60, c.round, c.gain, c.heightMm, what, TraceLog.pos(h?.position), TraceLog.pos(h?.forward)))
    }

    // =========================================================================================
    // plumbing
    // =========================================================================================

    private var slowJob: Job? = null

    private fun go(id: String) {
        val was = _state.value.designState
        update { it.copy(designState = id) }
        if (id == "S03" && was != "S03") {
            update { it.copy(searchSlow = false, found = null, foundStable = false) }
            slowJob?.cancel()
            slowJob =
                scope.launch {
                    delay(SEARCH_SLOW_MS)
                    if (_state.value.designState == "S03" && !_state.value.foundStable) update { it.copy(searchSlow = true) }
                }
        }
    }

    private fun update(change: (TraceUiState) -> TraceUiState) {
        val next = change(_state.value)
        _state.value = next
        if (next.designState != lastLogged) {
            lastLogged = next.designState
            TraceLog.i("STATE ${next.designState} desk=${next.deskFound} draft=${next.draft != null} aligned=${next.aligned != null} round=${next.calib.round}")
        }
    }

    fun settings(change: (Settings) -> Settings) {
        val next = change(_state.value.settings)
        store.saveSettings(next)
        update { it.copy(settings = next) }
    }

    // =========================================================================================
    // debug seams (src/debug drives them)
    // =========================================================================================

    /** Debug `hold`: transient states (S06) stay until released (QA screenshots). */
    @Volatile var holdTransient = false

    /** Debug `state`: the scene seeds a sheet first when the state needs one (TraceScene.debugSeed). */
    fun debugJump(id: String) {
        when (id) {
            "S00", "S01", "S02", "S16", "S18" -> {
                update { it.copy(draft = null, verdict = null, aligned = null) }
                go(id)
            }
            else -> go(id)
        }
    }

    /** Debug: put a draft (and state) in place directly. */
    fun debugDraft(draft: CornerDraft, activeCorner: Int, state: String = "S04") {
        update { it.copy(draft = draft, activeCorner = activeCorner, verdict = null, recheck = false, confirmed = 0) }
        go(state)
    }

    fun debugAligned(pose: PaperPose, w: Float = Sheet.A4.w / 1000f, h: Float = Sheet.A4.h / 1000f) = aligned(pose, w, h)

    fun debugRecheck(previous: PaperPose) {
        update { it.copy(recheck = true, previous = previous, aligned = null, draft = CornerDraft.of(previous).copy(moved = List(4) { true }), activeCorner = 0, confirmed = 0, verdict = null) }
        go("S04")
    }

    fun debugConfirm() = confirm()

    fun current(): TraceUiState = _state.value

    private fun TraceEvent.name(): String = this::class.java.simpleName

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TraceViewModel(TraceGraph.anchors, TraceGraph.store(context.applicationContext), { TraceGraph.head.pose() }) as T
    }

    companion object {
        const val DESK_TIMEOUT_MS = 20_000L
        const val ALIGNED_MS = 800L
        const val SEARCH_SLOW_MS = 8_000L
    }
}
