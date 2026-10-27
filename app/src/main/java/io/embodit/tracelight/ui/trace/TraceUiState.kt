package io.embodit.tracelight.ui.trace

import io.embodit.tracelight.domain.CornerDraft
import io.embodit.tracelight.domain.Library
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.Piece
import io.embodit.tracelight.domain.Settings

/**
 * Everything the panels and the scene draw. [designState] names the state the QA screenshots follow
 * (v0.2.0 flow, user 2026-10-26: align by hand, then draw straight away):
 * S00 loading · S01 welcome · S02 finding the desk · S03 drop the frame · S04 drag the corners ·
 * S06 aligned · S07 library · S09 tracing a piece · S12 piece done · S14 free drawing · S16 no desk.
 */
data class TraceUiState(
    val designState: String = "S00",
    val sceneReady: Boolean = false,
    val deskFound: Boolean = false,
    /** Corner points while aligning (S04). */
    val draft: CornerDraft? = null,
    /** The corner the panel asks about: 0 = ① near left ... 3 = ④ far left. */
    val activeCorner: Int = 0,
    /** The aligned sheet and its size in metres (any sheet). */
    val aligned: PaperPose? = null,
    val sheetW: Float = 0.210f,
    val sheetH: Float = 0.297f,
    /** The sheet before a re-alignment: the snap target and where 取消 goes back to. */
    val previous: PaperPose? = null,
    /** World anchor that holds the aligned sheet (null while it is being made). */
    val anchorId: String? = null,
    val settings: Settings = Settings(),
    /** A controller is tracked (S04 shows the stick hint). */
    val controller: Boolean = false,
    val trackingLost: Boolean = false,
    /** Gaze rests on the panel: the ghost frame holds still (S03). */
    val ghostFrozen: Boolean = false,
    /** Where the head ray meets the desk (S03): false = not on a desk. */
    val ghostOnDesk: Boolean = false,
    // ---- drawing
    val setId: String = Library.sets.first().id,
    val pieceId: String? = null,
    /** Index of the step being traced (S09). */
    val step: Int = 0,
    /** Pieces finished at least once (local progress). */
    val done: Set<String> = emptySet(),
    /** S14: a faint grid on the sheet. */
    val grid: Boolean = false,
    /** The ⋯ menu of the drawing panel is open. */
    val menuOpen: Boolean = false,
) {
    val aligning: Boolean
        get() = designState == "S04"

    /** Corners can be dragged now. */
    val dragEnabled: Boolean
        get() = aligning && !trackingLost

    val allCornersSet: Boolean
        get() = draft?.allMoved == true

    val piece: Piece?
        get() = pieceId?.let { Library.piece(it) }

    /** The sheet is in place and the drawing states show it. */
    val drawing: Boolean
        get() = aligned != null && designState in setOf("S07", "S09", "S12", "S14")
}

sealed interface TraceEvent {
    data object SceneReady : TraceEvent

    data class DeskChanged(val found: Boolean) : TraceEvent

    data object ReadyClicked : TraceEvent

    data class GhostChanged(val onDesk: Boolean, val frozen: Boolean) : TraceEvent

    /** Pinch on the desk in S03: the frame's centre and heading (long edge along the view). */
    data class FrameDropped(val pose: PaperPose) : TraceEvent

    data class CornerMoved(val index: Int, val at: P2) : TraceEvent

    /** The whole frame dragged by its centre handle (S04). */
    data class FrameMoved(val by: P2) : TraceEvent

    /** Thumbstick turn of the whole frame, written on release (radians, about the corners' centroid). */
    data class FrameTurned(val dYaw: Float) : TraceEvent

    data object ConfirmClicked : TraceEvent

    data object RestartClicked : TraceEvent

    data object CancelClicked : TraceEvent

    data object RetryDeskClicked : TraceEvent

    data class TrackingChanged(val lost: Boolean) : TraceEvent

    data class ControllerChanged(val tracked: Boolean) : TraceEvent

    data class AnchorReady(val id: String?) : TraceEvent

    // ---- drawing
    data class SetPicked(val id: String) : TraceEvent

    data class PiecePicked(val id: String) : TraceEvent

    data object NextStepClicked : TraceEvent

    data object PrevStepClicked : TraceEvent

    data object NextPieceClicked : TraceEvent

    data object LibraryClicked : TraceEvent

    data object FreeDrawClicked : TraceEvent

    data object GridClicked : TraceEvent

    data object MenuClicked : TraceEvent

    data object RealignClicked : TraceEvent

    // ---- debug
    data class DebugState(val id: String) : TraceEvent
}
