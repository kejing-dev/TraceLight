package io.embodit.tracelight.ui.trace

import io.embodit.tracelight.domain.CornerDraft
import io.embodit.tracelight.domain.FitVerdict
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.Settings

/**
 * Calibration page (S20, spike build): the round being run, its drag gain, when it started, how many
 * readings were logged, the test figure and the pen-grip tap test (design `preconditions.deviceSpike`).
 */
data class Calib(
    val round: Int = 1,
    val gain: Float = 0.5f,
    val startedAtMs: Long = 0L,
    val records: Int = 0,
    val testFigure: Boolean = false,
    val taps: Int = 0,
    val heightMm: Int = 0,
)

/**
 * Everything the panels and the scene draw. [designState] is the design package's state id (S00 - S20),
 * the one number QA screenshots are named after.
 */
data class TraceUiState(
    val designState: String = "S00",
    val sceneReady: Boolean = false,
    val deskFound: Boolean = false,
    /** Corner points while aligning (S04 / S05). */
    val draft: CornerDraft? = null,
    /** The corner the user is asked about: 0 = ① lower left ... 3 = ④ upper left. */
    val activeCorner: Int = 0,
    /** Re-check mode: corners prefilled, confirmed one by one (design `corners.recheck`). */
    val recheck: Boolean = false,
    val confirmed: Int = 0,
    /** The fit after 对好了 (S05 reads its worst corner). */
    val verdict: FitVerdict? = null,
    /** The aligned sheet (after S06) and its size in metres (any sheet; design change 2026-10-23). */
    val aligned: PaperPose? = null,
    val sheetW: Float = 0.210f,
    val sheetH: Float = 0.297f,
    /** The sheet the camera sees now (S03, automatic alignment), and whether it held still for a few frames. */
    val found: io.embodit.tracelight.domain.SheetRect? = null,
    val foundStable: Boolean = false,
    /** S03 has looked for a while without finding a sheet: offer the manual drop. */
    val searchSlow: Boolean = false,
    /** The sheet before a re-alignment: snap target and where 取消 goes back to. */
    val previous: PaperPose? = null,
    /** World anchor that holds the aligned sheet (null while it is being made). */
    val anchorId: String? = null,
    val calib: Calib = Calib(),
    val settings: Settings = Settings(),
    /** A controller is tracked (S04 shows the tip hint). */
    val controller: Boolean = false,
    val trackingLost: Boolean = false,
    /** Gaze rests on the panel: the ghost frame holds still (design S03, R2-3). */
    val ghostFrozen: Boolean = false,
    /** Where the head ray meets the desk (S03): null = not on a desk (coral ✕). */
    val ghostOnDesk: Boolean = false,
) {
    val aligning: Boolean
        get() = designState == "S04" || designState == "S05"

    /** Corners can be dragged now. */
    val dragEnabled: Boolean
        get() = aligning && !trackingLost

    val allCornersSet: Boolean
        get() = if (recheck) confirmed >= 4 else draft?.allMoved == true
}

sealed interface TraceEvent {
    data object SceneReady : TraceEvent

    data class DeskChanged(val found: Boolean) : TraceEvent

    data object ReadyClicked : TraceEvent

    data class GhostChanged(val onDesk: Boolean, val frozen: Boolean) : TraceEvent

    /** Pinch on the desk in S03: the sheet's centre and heading (long edge along the view). */
    data class FrameDropped(val pose: PaperPose) : TraceEvent

    data class CornerMoved(val index: Int, val at: P2) : TraceEvent

    /** The camera found a sheet (null: lost it); [stable] once a few frames agree. */
    data class PaperSeen(val rect: io.embodit.tracelight.domain.SheetRect?, val stable: Boolean) : TraceEvent

    /** S03 with a sheet found: use it (对好了) or fine-tune its corners (手动调整). */
    data object AutoAcceptClicked : TraceEvent

    data object AutoAdjustClicked : TraceEvent

    /** Thumbstick turn of the whole frame, written on release (radians, about the corners' centroid). */
    data class FrameTurned(val dYaw: Float) : TraceEvent

    data object ConfirmClicked : TraceEvent

    /** Re-check: "① 对上了" for the active corner. */
    data object CornerOkClicked : TraceEvent

    data object RestartClicked : TraceEvent

    data object CancelClicked : TraceEvent

    data object RetryDeskClicked : TraceEvent

    data class TrackingChanged(val lost: Boolean) : TraceEvent

    data class ControllerChanged(val tracked: Boolean) : TraceEvent

    data class AnchorReady(val id: String?) : TraceEvent

    // ---- calibration page (S20)
    data object RecordClicked : TraceEvent

    data object TestFigureClicked : TraceEvent

    data class GainClicked(val gain: Float) : TraceEvent

    data object TestTapClicked : TraceEvent

    data object NextRoundClicked : TraceEvent

    data class HeightClicked(val deltaMm: Int) : TraceEvent

    // ---- debug
    data class DebugState(val id: String) : TraceEvent
}
