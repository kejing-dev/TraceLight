package io.embodit.tracelight.ui.trace.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.tracelight.domain.CornerDraft
import io.embodit.tracelight.domain.Desks
import io.embodit.tracelight.domain.DragLine
import io.embodit.tracelight.domain.Hand
import io.embodit.tracelight.domain.HeadPose
import io.embodit.tracelight.domain.Mm
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperFit
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.PlaneSurface
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.Sheet
import io.embodit.tracelight.domain.SheetRect
import io.embodit.tracelight.domain.Stick
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.domain.raycast
import io.embodit.tracelight.domain.yawOf
import io.embodit.tracelight.platform.TraceGraph
import io.embodit.tracelight.platform.TraceLog
import io.embodit.tracelight.ui.trace.TraceEvent
import io.embodit.tracelight.ui.trace.TraceUiState
import io.embodit.tracelight.ui.trace.components.PanelSizes
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object ActiveScene {
    @Volatile var scene: TraceScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in TraceScreen). */
class TraceFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Panel ids of the pool: the setup panel (aligning) and the drawing panel (library, tracing, free drawing). */
object PanelIds {
    const val LESSON = "lesson"
    const val DRAW = "draw"

    val pool: List<String> = listOf(LESSON, DRAW)
}

/**
 * The 3D side of the app. It owns the calibrated stage root, the panel pool, the ghost frame, the
 * frame with its corner and move handles, the drawing on the sheet, starts and stops the sensing
 * (planes, head, controllers, world anchors) and turns system taps and drags into events. Per-frame
 * work (ghost frame, drags, thumbstick turn, pen tip, panel placement) happens in [onFrame], never
 * through Compose: the only Compose state written here is which panel is in view. Main thread only.
 */
class TraceScene(private val sink: (TraceEvent) -> Unit) {

    /** Panel id -> in view. A pooled panel composes its content only while this is true (Spatial Ruler finding 3). */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = TraceGraph.head
    private val controller = TraceGraph.controller
    private val planes = TraceGraph.planes
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: TraceMaterials? = null
    private var meshes: TraceMeshes? = null
    private var colliders: RoomColliders? = null
    private var built = false
    private var attached = false
    private var paused = false
    private var buildJob: Job? = null

    // ---- the sheet: everything lies below [sheetNode] (Stage space, or held by the sheet's world anchor)
    private var sheetNode: Entity? = null
    private var ghost: Outline? = null
    private var ghostX: List<Strip> = emptyList()
    private var frame: Outline? = null
    private val corners = ArrayList<CornerHandle>()
    private var mover: CornerHandle? = null
    private var guides: GuideViews? = null

    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var ui = TraceUiState()
    private var frames = 0L
    private var lastLogMs = 0L
    private var lastLost = false
    private var lastDesk = false
    private var lastController = false
    private var lastFrameMs = -1L

    // ghost frame (S03): where the head ray meets a desk
    private var ghostPose: PaperPose? = null
    private var ghostPlane: PlaneSurface? = null
    private var lastGhostOnDesk = false
    private var lastGhostFrozen = false

    // panels beside the sheet: placed once per sheet, never chasing the ghost
    private var deskPanelAt: Vec3? = null
    private var deskPanelFor: Any? = null
    private val lessonFollow = LazyFollow("lesson", ::lessonRest, windowDeg = 35f, nearM = 0.35f, farM = 0.9f)

    // aligned flash (S06): 0.6 s of lightbox warm white
    private var flashStartMs = -1L

    // ---- the sheet's world anchor
    private var anchorEntity: Entity? = null
    private var anchorId: String? = null
    private var anchorBound = false
    private var anchorStill = 0
    private var anchorLast: Vec3? = null
    private var anchorBindPose: Pair<Vec3, Quat4>? = null
    private var lastDriftLogMs = 0L

    // ---- system drag of a corner (0..3) or of the whole frame (MOVE)
    private class Drag(val corner: Int, val press: Vec3, val offset: P2) {
        var begun = false
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var totalPx = Vec3.ZERO
        var dir: Vec3? = null
        var equivalent = 1f
        var moves = 0
        var lastLogMs = 0L
    }

    private var drag: Drag? = null
    private var lastDragEndMs = -1L

    /** The dragged corner's live point (written on release as TraceEvent.CornerMoved). */
    private var liveCorner: Pair<Int, P2>? = null

    /** The whole frame's live offset while its move handle is dragged (TraceEvent.FrameMoved on release). */
    private var liveMove: P2? = null

    /** Thumbstick turn of the whole frame, live here and written on release (TraceEvent.FrameTurned). */
    private var turnYaw = 0f
    private var turning = false
    private var neutralSinceMs = -1L

    /** Set by the screen before [attach]. */
    var appContext: android.content.Context? = null

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z, Spatial Ruler):
        // move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        TraceLog.i("STAGE root offset=${TraceLog.pos(offset.domain())}")
        val node = Entity()
        runCatching { node.setName("sheetNode") }
        root.addChild(node)
        sheetNode = node

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.LESSON, PanelSizes.LESSON_W, PanelSizes.LESSON_H, PANEL_SCALE)
        rig.world(PanelIds.DRAW, PanelSizes.DRAW_W, PanelSizes.DRAW_H, PANEL_SCALE)
        head.start()
        controller.start()
        TraceGraph.anchors.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        startSensing()
        buildJob =
            scope?.launch {
                try {
                    build(node, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) TraceLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        TraceLog.i("SCENE attached")
    }

    /**
     * Materials and meshes, then the sheet's entities. A mesh costs about a rendered frame and blocking
     * the main thread for all of them raised an ANR (first launch: "Waited 5000ms for FocusEvent"):
     * the build starts late and pauses after every step (S00).
     */
    private suspend fun build(node: Entity, t0: Long) {
        delay(BUILD_START_MS)
        val shared = SharedResources()
        this.shared = shared
        val m = TraceMaterials(shared)
        materials = m
        delay(BUILD_STEP_MS)
        val meshes = TraceMeshes(shared)
        this.meshes = meshes
        delay(BUILD_STEP_MS)
        val root = stageRoot ?: return
        val colliders = RoomColliders(root)
        colliders.buildBackstop()
        this.colliders = colliders
        colliders.sync(planes.all)
        delay(BUILD_STEP_MS)
        ghost = Outline(node, meshes.dashes, m.ghost, "ghost")
        ghostX = List(2) { Strip(node, meshes.band, m.coral, "ghostX:$it") }
        frame = Outline(node, meshes.band, m.amber, "frame")
        delay(BUILD_STEP_MS)
        repeat(4) { corners += CornerHandle(node, meshes, m, it) }
        mover = CornerHandle(node, meshes, m, MOVE, ringM = 0.026f, armM = 0.02f, colliderR = 0.03f)
        delay(BUILD_STEP_MS)
        guides = scope?.let { GuideViews(node, meshes, m, it) }
        built = true
        TraceLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(TraceEvent.SceneReady)
    }

    private fun startSensing() {
        planes.start { colliders?.sync(planes.all) }
    }

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        head.stop()
        controller.stop()
        endDrag(cancelled = true)
        turning = false
        TraceLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        controller.start()
        startSensing()
        TraceLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        runCatching { guides?.release() }
        guides = null
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        controller.stop()
        TraceGraph.anchors.stop()
        panelShown.clear()
        corners.clear()
        mover = null
        ghost = null
        ghostX = emptyList()
        frame = null
        runCatching { colliders?.clear() }
        colliders = null
        runCatching { rig?.release() }
        runCatching { anchorEntity?.destroy(true) }
        anchorEntity = null
        anchorId = null
        anchorBound = false
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        stageRoot = null
        sheetNode = null
        contentProbe = null
        rig = null
        content = null
        built = false
        hasHead = false
        drag = null
        liveCorner = null
        liveMove = null
        turning = false
        lessonFollow.reset()
        deskPanelAt = null
        deskPanelFor = null
        TraceLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: TraceUiState) {
        val before = ui
        ui = state
        val rig = rig ?: return
        if (state.designState != before.designState) {
            if (state.designState == "S06") flashStartMs = SystemClock.elapsedRealtime()
            if (state.designState == "S03") deskPanelFor = null
            if (!state.aligning) {
                endDrag(cancelled = true)
                turning = false
            }
        }
        if (state.draft != before.draft) {
            liveCorner = null
            liveMove = null
        }
        if (state.anchorId != anchorId) bindAnchor(state.anchorId)
        if (isDrawPanel(state.designState)) rig[PanelIds.LESSON]?.hide() else rig[PanelIds.DRAW]?.hide()
    }

    private fun isDrawPanel(id: String) = id in DRAW_STATES

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        pollTracking(now)
        if (built) {
            pollDesk()
            pollController()
            stepStick(now)
            drawGhost()
            drawSheet(now)
            watchAnchor(now)
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        lastFrameMs = now
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            TraceLog.i("FRAME n=$frames head=${TraceLog.pos(pose.position)} fwd=${TraceLog.pos(pose.forward)} state=${ui.designState} planes=${planes.all.size} desks=${desks().size} stick=${controller.status()}")
        }
    }

    private fun pollTracking(now: Long) {
        val lost = head.lost(now)
        if (lost != lastLost) {
            lastLost = lost
            if (lost) endDrag(cancelled = true)
            sink(TraceEvent.TrackingChanged(lost))
        }
    }

    private fun desks(): List<PlaneSurface> = if (debugNoDesk) emptyList() else Desks.desks(planes.all, if (hasHead) pose.position.y else null)

    private fun pollDesk() {
        val found = desks().isNotEmpty()
        if (found != lastDesk) {
            lastDesk = found
            TraceLog.i("DESK found=$found ${desks().firstOrNull()?.let { "top=${it.label} y=${String.format(Locale.US, "%.3f", it.centroid.y)} area=${String.format(Locale.US, "%.2f", it.area)}" } ?: ""}")
            sink(TraceEvent.DeskChanged(found))
        }
    }

    /**
     * The desk under a point: TABLE planes first, then the lowest other desk plane that contains it (on
     * the device a keyboard 3 - 4 cm above the desk counted as a desk plane and frames landed on it).
     */
    private fun deskUnder(p: Vec3): PlaneSurface? {
        val under = desks().filter { it.contains(Vec3(p.x, it.centroid.y, p.z)) }
        return under.firstOrNull { it.semantic == "TABLE" } ?: under.minByOrNull { it.centroid.y }
    }

    /** S03: the ghost frame slides with the head ray on the desk; it holds still while the gaze rests on the panel. */
    private fun drawGhost() {
        val g = ghost ?: return
        if (ui.designState != "S03" || !hasHead) {
            g.hide()
            ghostX.forEach { it.hide() }
            ghostPose = null
            return
        }
        val frozen = gazeOnPanel()
        if (!frozen) {
            val hit = raycast(desks(), pose.position, pose.forward, 3f)
            if (hit != null) {
                val desk = deskUnder(hit.point) ?: hit.plane
                ghostPose = PaperPose(Vec3(hit.point.x, desk.centroid.y, hit.point.z), yawOf(pose.level()))
                ghostPlane = desk
            } else {
                ghostPose = null
                ghostPlane = null
            }
        }
        val gp = ghostPose
        val onDesk = gp != null
        if (onDesk != lastGhostOnDesk || frozen != lastGhostFrozen) {
            lastGhostOnDesk = onDesk
            lastGhostFrozen = frozen
            sink(TraceEvent.GhostChanged(onDesk, frozen))
        }
        if (gp != null) {
            g.draw(SheetRect(gp, ghostW(), ghostH()).corners, gp.center.y + LIFT_M, GHOST_W_M)
            ghostX.forEach { it.hide() }
        } else {
            g.hide()
            // a small coral ✕ where the head ray meets anything else (or 0.8 m ahead)
            val at = raycast(planes.all, pose.position, pose.forward, 3f)?.point ?: (pose.position + pose.forward * 0.8f)
            val r = pose.right * X_HALF_M
            val u = pose.up * X_HALF_M
            val face = (pose.position - at).normalized()
            ghostX[0].draw(at - r - u, at + r + u, X_W_M, face)
            ghostX[1].draw(at - r + u, at + r - u, X_W_M, face)
        }
    }

    /** A re-alignment drops a frame of the last sheet's size; the first one is A4. */
    private fun ghostW() = if (ui.previous != null) ui.sheetW else Sheet.A4.w / 1000f

    private fun ghostH() = if (ui.previous != null) ui.sheetH else Sheet.A4.h / 1000f

    /** True when the head ray passes through the setup panel (S03: the ghost freezes). */
    private fun gazeOnPanel(): Boolean {
        val slot = rig?.get(PanelIds.LESSON) ?: return false
        if (!slot.shown) return false
        val to = slot.position - pose.position
        val d = to.length
        if (d < 1e-3f) return false
        val ang = Math.toDegrees(acos((to / d).dot(pose.forward).coerceIn(-1f, 1f).toDouble()))
        val halfW = Math.toDegrees(kotlin.math.atan((PanelSizes.LESSON_W * DP_M * PANEL_SCALE / 2f / d).toDouble()))
        return ang < halfW * 0.9
    }

    /** The corner points as drawn now: the dragged corner live, the whole frame moved or turned live. */
    private fun liveDraft(): CornerDraft? {
        var d = ui.draft ?: return null
        liveCorner?.let { (i, p) -> d = d.with(i, p).copy(moved = d.moved) }
        liveMove?.let { by -> d = d.copy(points = d.points.map { it + by }) }
        if (turning && turnYaw != 0f) d = d.turned(turnYaw)
        return d
    }

    private fun drawSheet(now: Long) {
        val m = materials ?: return
        val f = frame ?: return
        val g = guides
        val lift = LIFT_M
        val draft = if (ui.aligning) liveDraft() else null
        val aligned = ui.aligned
        when {
            draft != null -> {
                f.material(m.amber)
                f.draw(draft.points, draft.y + lift, FRAME_W_M)
                val fit = PaperFit.fit(draft, Sheet.A4)
                for (c in corners) {
                    val look =
                        when {
                            c.index == ui.activeCorner && !ui.allCornersSet -> CornerHandle.Look.ACTIVE
                            draft.moved[c.index] -> CornerHandle.Look.PLACED
                            else -> CornerHandle.Look.IDLE
                        }
                    c.draw(draft.points[c.index], draft.y + lift + 0.0002f, fit.pose.right, fit.pose.away, look, now)
                }
                mover?.draw(draft.centroid, draft.y + lift + 0.0002f, fit.pose.right, fit.pose.away, CornerHandle.Look.IDLE, now)
                g?.draw(null, 0, null, 0f, 0f, GuideViews.Mode.HIDDEN, lift, now)
                g?.drawGrid(false, null, 0f, 0f, lift)
            }
            aligned != null -> {
                corners.forEach { it.show(false) }
                mover?.show(false)
                val flashing = flashStartMs > 0 && now - flashStartMs < FLASH_MS
                f.material(if (flashing) m.warm else m.faint)
                f.draw(SheetRect(aligned, ui.sheetW, ui.sheetH).corners, aligned.center.y + lift, FRAME_W_M)
                val mode =
                    when {
                        ui.trackingLost -> GuideViews.Mode.HIDDEN
                        ui.designState == "S09" -> GuideViews.Mode.TRACE
                        ui.designState == "S12" -> GuideViews.Mode.DONE
                        else -> GuideViews.Mode.HIDDEN
                    }
                g?.draw(ui.piece, ui.step, aligned, ui.sheetW, ui.sheetH, mode, lift + 0.0002f, now)
                g?.drawGrid(ui.designState == "S14" && ui.grid && !ui.trackingLost, aligned, ui.sheetW, ui.sheetH, lift + 0.0001f)
            }
            else -> {
                f.hide()
                corners.forEach { it.show(false) }
                mover?.show(false)
                g?.draw(null, 0, null, 0f, 0f, GuideViews.Mode.HIDDEN, lift, now)
                g?.drawGrid(false, null, 0f, 0f, lift)
            }
        }
    }

    // =========================================================================================
    // the sheet's world anchor
    // =========================================================================================

    /**
     * Once the ViewModel has made a world anchor at the sheet's centre, the sheet's node moves under an
     * entity that follows that anchor (`AnchorComponent(AnchorTarget.createWorldAnchorTarget(uuid))`):
     * the platform's tracking corrections then carry the drawing with the desk. The node's local pose is
     * the inverse of the anchor's Stage pose at the moment it settles, so nothing jumps.
     */
    private fun bindAnchor(id: String?) {
        val root = stageRoot ?: return
        val node = sheetNode ?: return
        if (id == anchorId) return
        runCatching { node.setParent(root) }
        node.setLocal(position = Vec3.ZERO, rotation = Quat4.IDENTITY)
        runCatching { anchorEntity?.destroy(true) }
        anchorEntity = null
        anchorBound = false
        anchorBindPose = null
        anchorLast = null
        anchorStill = 0
        anchorId = id
        if (id == null) return
        val e = Entity()
        runCatching { e.setName("sheetAnchor") }
        val made = runCatching { e.components.set(AnchorComponent(AnchorTarget.createWorldAnchorTarget(UUID.fromString(id)))) }
        if (made.isFailure) {
            TraceLog.w("ANCHOR component failed: ${made.exceptionOrNull()} (frame stays in Stage space)")
            return
        }
        root.addChild(e)
        anchorEntity = e
        TraceLog.i("ANCHOR entity id=${id.take(8)}")
    }

    private fun anchorStagePose(): Pair<Vec3, Quat4>? {
        val e = anchorEntity ?: return null
        val root = stageRoot ?: return null
        val p = runCatching { e.convertPositionTo(Vector3.ZERO, root).domain() }.getOrNull() ?: return null
        val q = runCatching { e.convertRotationTo(Quat.identity(), root).domain() }.getOrNull() ?: return null
        if (!p.isFinite()) return null
        return p to q
    }

    private fun watchAnchor(now: Long) {
        val e = anchorEntity ?: return
        val node = sheetNode ?: return
        val (p, q) = anchorStagePose() ?: return
        if (!anchorBound) {
            // wait for the anchor entity to take the anchor's pose and hold it for a few frames
            val last = anchorLast
            anchorLast = p
            if (p.length < 1e-4f || last == null || last.distanceTo(p) > 0.0005f) {
                anchorStill = 0
                return
            }
            if (++anchorStill < ANCHOR_STILL_FRAMES) return
            val inv = q.conjugate()
            runCatching { node.setParent(e) }
            node.setLocal(position = inv.rotate(p) * -1f, rotation = inv)
            anchorBound = true
            anchorBindPose = p to q
            lastDriftLogMs = now
            TraceLog.i("ANCHOR bound pos=${TraceLog.pos(p)} ${TraceLog.quat(q)}")
            return
        }
        if (now - lastDriftLogMs >= DRIFT_LOG_MS) {
            lastDriftLogMs = now
            val (p0, _) = anchorBindPose ?: return
            TraceLog.i(String.format(Locale.US, "DRIFT anchor_moved_mm=%.1f head=%s", p.distanceTo(p0) * 1000f, TraceLog.pos(pose.position)))
        }
    }

    // =========================================================================================
    // thumbstick turn + controller
    // =========================================================================================

    /**
     * Thumbstick X turns the whole frame about its centre while aligning (taste profile D; Stick.rateDeg:
     * dead zone, square curve). After 150 ms back in the dead zone the turn is written (snap there).
     */
    private fun stepStick(now: Long) {
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        val rate = Stick.rateDeg(controller.x(now))
        val can = ui.dragEnabled && drag == null && ui.draft != null
        if (rate != 0f && can) {
            if (!turning) {
                turning = true
                turnYaw = 0f
                TraceLog.i(String.format(Locale.US, "STICK turn start x=%.2f", controller.x(now)))
            }
            turnYaw += Math.toRadians((rate * dt).toDouble()).toFloat()
            neutralSinceMs = -1L
        } else if (turning) {
            if (!can) {
                turning = false
                turnYaw = 0f
                return
            }
            if (neutralSinceMs < 0) neutralSinceMs = now else if (now - neutralSinceMs >= STICK_RELEASE_MS) {
                turning = false
                val d = turnYaw
                turnYaw = 0f
                TraceLog.i(String.format(Locale.US, "STICK turn end deg=%.1f", Math.toDegrees(d.toDouble())))
                if (d != 0f) sink(TraceEvent.FrameTurned(d))
            }
        }
    }

    /** Debug `stick`: the stick reads [x] for [ms]. */
    fun debugStick(x: Float, ms: Long): String {
        controller.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d aligning=%s", x, ms, ui.aligning)
    }

    private fun pollController() {
        val (l, r) = controller.poses()
        val tracked = l != null || r != null
        if (tracked != lastController) {
            lastController = tracked
            sink(TraceEvent.ControllerChanged(tracked))
        }
        controller.takePresses()
    }

    // =========================================================================================
    // panels beside the sheet
    // =========================================================================================

    /** S00 - S02: 0.60 m from the eyes, yaw only, 12 degrees below the level gaze. */
    private fun lessonRest(h: HeadPose): Vec3 = restAt(h, 0f, -LESSON_DOWN_DEG, LESSON_M)

    private fun restAt(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    /**
     * Beside the sheet, on the side away from the drawing hand, standing on the desk a little toward
     * its far edge: within reach of the eyes from the chair (gaze + pinch, no walking), never over the paper.
     */
    private fun besideSheet(p: PaperPose, wM: Float, panelId: String): Vec3 {
        val (wDp, hDp) = if (panelId == PanelIds.DRAW) PanelSizes.DRAW_W to PanelSizes.DRAW_H else PanelSizes.LESSON_W to PanelSizes.LESSON_H
        val halfW = wDp * DP_M * PANEL_SCALE / 2f
        val side = if (ui.settings.hand == Hand.LEFT) 1f else -1f
        val x = side * (wM * 1000f / 2f + halfW * 1000f + PANEL_GAP_MM)
        return p.toWorld(Mm(x, PANEL_AWAY_MM), PANEL_BASE_M + hDp * DP_M * PANEL_SCALE / 2f)
    }

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val state = ui.designState
        val panelId = if (isDrawPanel(state)) PanelIds.DRAW else PanelIds.LESSON
        val slot = rig[panelId] ?: return
        val draft = ui.draft
        val aligned = ui.aligned
        val at: Vec3? =
            when {
                state in setOf("S00", "S01", "S02", "S16") -> {
                    deskPanelFor = null
                    lessonFollow.step(pose, now, hold = false)?.let { slot.show(it) } ?: slot.show()
                    return
                }
                state == "S03" -> {
                    // placed once on entering S03 at the first desk hit, never chasing the ghost
                    if (deskPanelFor != "S03") {
                        val gp = ghostPose ?: raycast(desks(), pose.position, pose.forward, 3f)?.let { PaperPose(it.point, yawOf(pose.level())) }
                        if (gp != null) {
                            deskPanelAt = besideSheet(gp, ghostW(), panelId)
                            deskPanelFor = "S03"
                            TraceLog.i("PANEL desk at=${TraceLog.pos(deskPanelAt)}")
                        }
                    }
                    deskPanelAt
                }
                draft != null -> {
                    if (deskPanelFor !is CornerDraft) {
                        deskPanelAt = besideSheet(PaperFit.fit(draft, Sheet.A4).pose, Sheet.A4.w / 1000f, panelId)
                        deskPanelFor = draft
                    }
                    deskPanelAt
                }
                aligned != null -> {
                    val key = Triple(aligned, ui.sheetW, panelId)
                    if (deskPanelFor != key) {
                        deskPanelAt = besideSheet(aligned, ui.sheetW, panelId)
                        deskPanelFor = key
                        TraceLog.i("PANEL $panelId beside sheet at=${TraceLog.pos(deskPanelAt)}")
                    }
                    deskPanelAt
                }
                else -> lessonFollow.step(pose, now, hold = false) ?: slot.position
            }
        if (at != null) slot.show(at) else lessonFollow.step(pose, now, hold = false)?.let { slot.show(it) } ?: slot.show()
        if (!slot.shown) slot.show()
    }

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    private fun handleOf(entity: Entity?): Int? {
        if (entity == null) return null
        for (c in corners) if (c.owns(entity)) return c.index
        if (mover?.owns(entity) == true) return MOVE
        return null
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) {
            TraceLog.i("TAP ignored reason=after-drag target=$name")
            return
        }
        TraceLog.i("TAP target=$name stage=${TraceLog.pos(stage)} state=${ui.designState}")
        if (ui.designState == "S09" && ui.menuOpen) return
        if (ui.designState != "S03") return
        // the pinch lands where the pointer hit a desk; else where the ghost is (head ray)
        val onDesk = stage?.let { s -> deskUnder(s)?.takeIf { abs(it.centroid.y - s.y) < 0.06f } }
        val drop =
            when {
                onDesk != null && stage != null -> PaperPose(Vec3(stage.x, onDesk.centroid.y, stage.z), yawOf(pose.level()))
                ghostPose != null -> ghostPose
                else -> null
            }
        if (drop == null) {
            TraceLog.i("TAP drop refused reason=not-on-desk")
            return
        }
        sink(TraceEvent.FrameDropped(drop))
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity
                val name = runCatching { target?.getName() }.getOrNull() ?: "none"
                val press = if (start != null) toStage(start.x, start.y, start.z) else null
                val i = handleOf(target)
                TraceLog.i("DRAG start target=$name handle=${i ?: "-"} stage=${TraceLog.pos(press)}")
                if (i == null || press == null) return
                val d = ui.draft ?: return
                if (!ui.dragEnabled) {
                    TraceLog.i("DRAG refused state=${ui.designState}")
                    return
                }
                val from = if (i == MOVE) d.centroid else d.points[i]
                drag = Drag(i, Vec3(press.x, d.y, press.z), P2.of(press) - from)
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) beginDrag(d)
                if (d.begun) {
                    val dir = d.dir ?: return
                    val step = (d.hand - d.prevHand) * DRAG_GAIN
                    val turned = DragLine.turn(dir, step, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    val hit = DragLine.onLevel(pose.position, turned, d.press.y) ?: return
                    val at = hit - d.offset
                    val draft = ui.draft ?: return
                    if (d.corner == MOVE) liveMove = at - draft.centroid else liveCorner = d.corner to at
                    if (d.moves <= 3 || now - d.lastLogMs > 500) {
                        d.lastLogMs = now
                        TraceLog.i(String.format(Locale.US, "DRAG move handle=%d at=(%.4f, %.4f) n=%d", d.corner, at.x, at.z, d.moves))
                    }
                }
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                if (!d.begun) {
                    TraceLog.i("DRAG $phase not-begun n=${d.moves}")
                    return
                }
                lastDragEndMs = now
                TraceLog.i("DRAG $phase handle=${d.corner} n=${d.moves}")
                endDrag(cancelled = phase == "cancel")
            }
        }
    }

    private fun beginDrag(d: Drag) {
        d.begun = true
        val to = d.press - pose.position
        if (to.length < 1e-3f) return
        d.dir = to.normalized()
        val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        d.equivalent = DragLine.equivalentDepth(to.length, pitch)
        d.prevHand = d.hand
        TraceLog.i("DRAG begin handle=${d.corner} depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
    }

    private fun endDrag(cancelled: Boolean) {
        val live = liveCorner
        val move = liveMove
        drag = null
        if (cancelled) {
            liveCorner = null
            liveMove = null
            return
        }
        if (move != null) sink(TraceEvent.FrameMoved(move))
        if (live != null) sink(TraceEvent.CornerMoved(live.first, live.second))
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    /** Debug: no desk counts (S16 on a room that has one). */
    var debugNoDesk = false

    /** Debug `seed`: a sheet on the desk ahead of the head, its top edge along the level gaze. */
    fun debugSeed(aheadM: Float = 0.40f): PaperPose? {
        val f = pose.level()
        var c = P2.of(pose.position) + f * aheadM
        val desk = desks().firstOrNull { it.contains(c.at(it.centroid.y)) } ?: desks().firstOrNull() ?: return null
        val y = desk.centroid.y
        if (!desk.contains(c.at(y))) c = P2.of(desk.centroid)
        return PaperPose(c.at(y), yawOf(f))
    }

    /** Debug `drag`: corner [i] goes to [to] (Stage) as a drag release would leave it. */
    fun debugCorner(i: Int, to: P2) {
        sink(TraceEvent.CornerMoved(i, to))
    }

    fun poseLine(): String =
        String.format(Locale.US, "POSE hmd pos=%s %s fwd=%s right=%s valid=%s support=%s frames=%d", TraceLog.pos(pose.position), TraceLog.quat(pose.rotation), TraceLog.pos(pose.forward), TraceLog.pos(pose.right), hasHead, head.supportState(), frames)

    fun headPose(): HeadPose? = if (hasHead) pose else null

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun planeLines(): List<String> =
        planes.all.map { s ->
            String.format(Locale.US, "PLANE %s area=%.2f center=%s normal=%s desk=%s", s.label, s.area, TraceLog.pos(s.centroid), TraceLog.pos(s.normal), s in desks())
        }

    fun sheetLines(): List<String> {
        val out = ArrayList<String>()
        out += "SHEET anchor=${anchorId?.take(8)} bound=$anchorBound entity=${anchorStagePose()?.let { "${TraceLog.pos(it.first)} ${TraceLog.quat(it.second)}" }} size=${ui.sheetW}x${ui.sheetH}"
        liveDraft()?.let { d -> d.points.forEachIndexed { i, p -> out += String.format(Locale.US, "CORNER %d at=(%.4f, %.4f) y=%.4f moved=%s", i + 1, p.x, p.z, d.y, d.moved[i]) } }
        return out
    }

    fun parkPanels() {
        lessonFollow.reset()
        deskPanelFor = null
    }

    companion object {
        const val PANEL_SCALE = 0.75f
        const val BUILD_START_MS = 1500L
        const val BUILD_STEP_MS = 150L
        const val LESSON_M = 0.60f
        const val LESSON_DOWN_DEG = 12f
        const val PANEL_GAP_MM = 40f
        const val PANEL_AWAY_MM = 60f
        const val PANEL_BASE_M = 0.03f
        const val LIFT_M = 0.0005f
        const val FRAME_W_M = 0.001f
        const val GHOST_W_M = 0.0015f
        const val X_HALF_M = 0.012f
        const val X_W_M = 0.002f
        const val FLASH_MS = 600L
        const val DRAG_START_M = 0.005f
        /** The hand moves 10 cm, the corner 8 cm (design 0.5 felt sluggish on the device). */
        const val DRAG_GAIN = 0.8f
        const val TAP_AFTER_DRAG_MS = 300L
        const val LOG_MS = 5_000L
        const val DRIFT_LOG_MS = 5_000L
        const val ANCHOR_STILL_FRAMES = 5
        const val STICK_RELEASE_MS = 150L
        const val MOVE = 4
        val DRAW_STATES = setOf("S07", "S09", "S12", "S14")
    }
}

/**
 * Lazy follow (Spatial Ruler T11): the panel rests where it is; once its centre has been more than
 * [windowDeg] off the view direction, or nearer than [nearM] or farther than [farM], for 0.4 s, it
 * slides (0.4 s, ease-in-out cubic) to the rest pose of that moment.
 */
internal class LazyFollow(private val name: String, private val rest: (HeadPose) -> Vec3, private val windowDeg: Float, private val nearM: Float, private val farM: Float) {
    private var at: Vec3? = null
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    fun reset() {
        at = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = at
        if (current == null) {
            val p = rest(head)
            at = p
            TraceLog.i("FOLLOW $name park pos=${TraceLog.pos(p)}")
            return write(p)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = from.lerp(to, e)
            if (q >= 1f) {
                slideStartMs = NEVER
                at = to
            }
            return write(p)
        }
        // yaw only: the angle is measured in the horizontal plane
        val toPanel = current - head.position
        val dist = toPanel.length
        val flat = P2.of(toPanel).normalized()
        val away = Math.toDegrees(acos(flat.dot(head.level()).coerceIn(-1f, 1f).toDouble())).toFloat()
        val out = away > windowDeg || dist < nearM || dist > farM
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = rest(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            TraceLog.i(String.format(Locale.US, "FOLLOW %s slide angle=%.1f dist=%.2f to=%s", name, away, dist, TraceLog.pos(to)))
        }
        return write(current)
    }

    private fun write(p: Vec3): Vec3? {
        if (p == lastWritten) return null
        lastWritten = p
        return p
    }

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 400L
        private const val SLIDE_MS = 400f
    }
}
