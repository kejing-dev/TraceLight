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
import io.embodit.tracelight.domain.Library
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperFit
import io.embodit.tracelight.domain.PaperPose
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
 * The whole behaviour (v0.2.0, user 2026-10-26: "改成手动标定… 后续的交互别再那么多测试了，就直接开始画，
 * 画的内容丰富一点"): align a sheet of any size by hand (drop the frame, drag the four corners, 对好了),
 * then pick a drawing and trace it step by step, or draw freely. Events from the panels and the scene
 * come in through [onEvent], the state goes out through [state].
 */
class TraceViewModel(
    private val anchors: AnchorGateway,
    private val store: TraceStore,
    private val head: () -> HeadPose?,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(TraceUiState(settings = store.loadSettings(), done = store.loadDone()))
    val state: StateFlow<TraceUiState> = _state

    private var deskJob: Job? = null
    private var alignedJob: Job? = null
    private var anchorJob: Job? = null
    private var lastLogged: String? = null

    /** Where the flow goes after S06 (the library on first alignment; back where it was on a re-alignment). */
    private var returnTo = "S07"

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: TraceEvent) {
        val s = _state.value
        if (e !is TraceEvent.GhostChanged && e !is TraceEvent.DeskChanged && e !is TraceEvent.ControllerChanged) TraceLog.i("UI ${e.name()} state=${s.designState}")
        when (e) {
            TraceEvent.SceneReady -> {
                update { it.copy(sceneReady = true) }
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
            is TraceEvent.FrameMoved -> moveFrame(e.by)
            is TraceEvent.FrameTurned -> turnFrame(e.dYaw)
            TraceEvent.ConfirmClicked -> confirm()
            TraceEvent.RestartClicked -> if (s.aligning) restart()
            TraceEvent.CancelClicked -> cancel()
            is TraceEvent.TrackingChanged -> {
                TraceLog.i("TRACK ${if (e.lost) "lost" else "recovered"}")
                update { it.copy(trackingLost = e.lost) }
            }
            is TraceEvent.ControllerChanged -> if (s.controller != e.tracked) update { it.copy(controller = e.tracked) }
            is TraceEvent.AnchorReady -> update { it.copy(anchorId = e.id) }
            is TraceEvent.SetPicked -> if (Library.set(e.id) != null) update { it.copy(setId = e.id, menuOpen = false) }
            is TraceEvent.PiecePicked -> openPiece(e.id)
            TraceEvent.NextStepClicked -> nextStep()
            TraceEvent.PrevStepClicked -> if (s.designState == "S09" && s.step > 0) update { it.copy(step = it.step - 1) }
            TraceEvent.NextPieceClicked -> s.pieceId?.let { Library.next(it) }?.let { openPiece(it.id) } ?: toLibrary()
            TraceEvent.LibraryClicked -> toLibrary()
            TraceEvent.FreeDrawClicked -> if (s.aligned != null) {
                update { it.copy(menuOpen = false, pieceId = null) }
                go("S14")
            }
            TraceEvent.GridClicked -> update { it.copy(grid = !it.grid) }
            TraceEvent.MenuClicked -> update { it.copy(menuOpen = !it.menuOpen) }
            TraceEvent.RealignClicked -> realign()
            is TraceEvent.DebugState -> debugJump(e.id)
        }
    }

    // =========================================================================================
    // alignment (by hand, any sheet)
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
        val snapped = FrameSnap.snap(pose, s.previous)
        val at = snapped ?: pose
        TraceLog.i(String.format(Locale.US, "FRAME drop center=%s yaw=%.1f snapped=%s", TraceLog.pos(at.center), Math.toDegrees(at.yaw.toDouble()), snapped != null))
        // the previous sheet's size if there was one, else A4: the corners are dragged to the real sheet anyway
        val rect = SheetRect(at, if (s.previous != null) s.sheetW else Sheet.A4.w / 1000f, if (s.previous != null) s.sheetH else Sheet.A4.h / 1000f)
        update { it.copy(draft = CornerDraft.of(rect), activeCorner = 0) }
        go("S04")
    }

    private fun moveCorner(i: Int, at: P2) {
        val s = _state.value
        val d = s.draft ?: return
        if (!s.aligning || i !in 0..3) return
        val next = d.with(i, at)
        // the next corner in ① ② ③ ④ order that was not touched yet
        val active = (1..4).map { (i + it) % 4 }.firstOrNull { !next.moved[it] } ?: i
        TraceLog.i(String.format(Locale.US, "CORNER moved i=%d at=(%.4f, %.4f) next=%d", i + 1, at.x, at.z, active + 1))
        update { it.copy(draft = next, activeCorner = active) }
    }

    private fun moveFrame(by: P2) {
        val s = _state.value
        val d = s.draft ?: return
        if (!s.aligning) return
        var moved = d.copy(points = d.points.map { it + by })
        s.previous?.let { prev ->
            FrameSnap.snap(PaperFit.fit(moved, Sheet.A4).pose, prev)?.let {
                TraceLog.i("SNAP frame onto previous (move)")
                moved = CornerDraft.of(SheetRect(it, s.sheetW, s.sheetH)).copy(moved = moved.moved)
            }
        }
        TraceLog.i(String.format(Locale.US, "FRAME moved by=(%.4f, %.4f)", by.x, by.z))
        update { it.copy(draft = moved) }
    }

    private fun turnFrame(dYaw: Float) {
        val s = _state.value
        val d = s.draft ?: return
        if (!s.aligning) return
        var turned = d.turned(dYaw)
        s.previous?.let { prev ->
            FrameSnap.snap(PaperFit.fit(turned, Sheet.A4).pose, prev)?.let {
                TraceLog.i("SNAP frame onto previous (turn)")
                turned = CornerDraft.of(SheetRect(it, s.sheetW, s.sheetH)).copy(moved = turned.moved)
            }
        }
        TraceLog.i(String.format(Locale.US, "FRAME turned d=%.1f", Math.toDegrees(dYaw.toDouble())))
        update { it.copy(draft = turned) }
    }

    /** 对好了: the rectangle that fits the four corners, whatever its size; nothing to check, no step in between. */
    private fun confirm() {
        val s = _state.value
        val d = s.draft ?: return
        if (!s.aligning) return
        val viewer = head()?.position?.let { P2.of(it) } ?: d.centroid.let { P2(it.x, it.z + 1f) }
        val rect = SheetRect.fit(d.points, d.y, viewer)
        TraceLog.i(String.format(Locale.US, "FIT size=%s", rect?.let { String.format(Locale.US, "%.0fx%.0f mm", it.widthM * 1000f, it.heightM * 1000f) } ?: "-"))
        if (rect == null) {
            aligned(PaperFit.fit(d, Sheet.A4).pose, Sheet.A4.w / 1000f, Sheet.A4.h / 1000f)
            return
        }
        aligned(rect.pose, rect.widthM, rect.heightM)
    }

    private fun aligned(pose: PaperPose, w: Float, h: Float) {
        update { it.copy(aligned = pose, sheetW = w, sheetH = h, draft = null, previous = null, anchorId = null, menuOpen = false) }
        go("S06")
        makeAnchor(pose)
        alignedJob?.cancel()
        alignedJob =
            scope.launch {
                delay(ALIGNED_MS)
                while (holdTransient) delay(200)
                if (_state.value.designState == "S06") go(returnTo)
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
        update { it.copy(draft = null, activeCorner = 0) }
        go("S03")
    }

    /** 重新对齐 from the drawing states: drop a new frame; 取消 brings the old one back. */
    private fun realign() {
        val s = _state.value
        val a = s.aligned ?: return
        returnTo = if (s.designState in setOf("S07", "S09", "S14")) s.designState else "S07"
        update { it.copy(previous = a, aligned = null, anchorId = null, menuOpen = false, draft = null) }
        findDesk()
    }

    private fun cancel() {
        val s = _state.value
        val prev = s.previous ?: return
        if (s.designState !in setOf("S02", "S03", "S04")) return
        update { it.copy(aligned = prev, previous = null, draft = null) }
        makeAnchor(prev)
        go(returnTo)
    }

    // =========================================================================================
    // drawing
    // =========================================================================================

    private fun toLibrary() {
        if (_state.value.aligned == null) return
        update { it.copy(menuOpen = false, pieceId = null, step = 0) }
        go("S07")
    }

    private fun openPiece(id: String) {
        val p = Library.piece(id) ?: return
        if (_state.value.aligned == null) return
        TraceLog.i("PIECE open ${p.id} steps=${p.steps.size}")
        update { it.copy(pieceId = p.id, setId = p.set, step = 0, menuOpen = false) }
        go("S09")
    }

    private fun nextStep() {
        val s = _state.value
        val p = s.piece ?: return
        if (s.designState != "S09") return
        if (s.step < p.steps.size - 1) {
            update { it.copy(step = it.step + 1) }
            return
        }
        val done = s.done + p.id
        store.saveDone(done)
        TraceLog.i("PIECE done ${p.id} total=${done.size}")
        update { it.copy(done = done) }
        go("S12")
    }

    // =========================================================================================
    // plumbing
    // =========================================================================================

    private fun go(id: String) {
        update { it.copy(designState = id) }
    }

    private fun update(change: (TraceUiState) -> TraceUiState) {
        val next = change(_state.value)
        _state.value = next
        if (next.designState != lastLogged) {
            lastLogged = next.designState
            TraceLog.i("STATE ${next.designState} desk=${next.deskFound} draft=${next.draft != null} aligned=${next.aligned != null} piece=${next.pieceId} step=${next.step}")
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

    fun debugJump(id: String) {
        when (id) {
            "S00", "S01", "S02", "S16" -> {
                update { it.copy(draft = null, aligned = null, pieceId = null) }
                go(id)
            }
            else -> go(id)
        }
    }

    /** Debug: put a draft (and state) in place directly. */
    fun debugDraft(draft: CornerDraft, activeCorner: Int, state: String = "S04") {
        update { it.copy(draft = draft, activeCorner = activeCorner) }
        go(state)
    }

    fun debugAligned(pose: PaperPose, w: Float = Sheet.A4.w / 1000f, h: Float = Sheet.A4.h / 1000f, then: String = "S07") {
        returnTo = then
        aligned(pose, w, h)
    }

    fun debugPiece(id: String, step: Int) {
        openPiece(id)
        val p = Library.piece(id) ?: return
        update { it.copy(step = step.coerceIn(0, p.steps.size - 1)) }
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
    }
}
