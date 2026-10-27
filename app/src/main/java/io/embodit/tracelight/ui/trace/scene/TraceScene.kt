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
import io.embodit.tracelight.domain.Calibration
import io.embodit.tracelight.domain.CornerDraft
import io.embodit.tracelight.domain.Desks
import io.embodit.tracelight.domain.DragLine
import io.embodit.tracelight.domain.HeadPose
import io.embodit.tracelight.domain.Hand
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

/** Panel ids of the pool: the lesson panel (alignment flow) and, in its place, the calibration page. */
object PanelIds {
    const val LESSON = "lesson"
    const val CALIB = "calib"

    val pool: List<String> = listOf(LESSON, CALIB)
}

/**
 * The 3D side of the app. It owns the calibrated stage root, the panel pool, the ghost frame, the
 * frame and its corner handles, the calibration crosses, starts and stops the sensing (planes, head,
 * controllers, world anchors) and turns system taps and drags into events. Per-frame work (ghost
 * frame, corner drags, thumbstick turn, panel placement, breathing) happens in [onFrame], never
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
    private var divider: Strip? = null
    private var offsetLine: Strip? = null
    private val corners = ArrayList<CornerHandle>()
    private var crosses: SheetMesh? = null
    private var crossesSize: Pair<Float, Float>? = null
    private var testFigure: SheetMesh? = null
    private var finder: PaperFinder? = null

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

    // the panel on the desk (S03 on): placed once per state change, never chasing the ghost
    private var deskPanelAt: Vec3? = null
    private var deskPanelFor: String? = null
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

    // ---- system drag of a corner
    private class Drag(val corner: Int, val press: Vec3, val offset: P2) {
        var begun = false
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var totalPx = Vec3.ZERO
        var dir: Vec3? = null
        var equivalent = 1f
        var gain = 0.5f
        var y = 0f
        var moves = 0
        var lastLogMs = 0L
    }

    private var drag: Drag? = null
    private var lastDragEndMs = -1L

    /** The dragged corner's live point (written on release as TraceEvent.CornerMoved). */
    private var liveCorner: Pair<Int, P2>? = null

    /** Thumbstick turn of the whole frame, live here and written on release (TraceEvent.FrameTurned). */
    private var turnYaw = 0f
    private var turning = false
    private var neutralSinceMs = -1L

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val sceneScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sceneScope
        finder =
            PaperFinder(TraceGraph.camera, sceneScope, ::desks) { rect, stable -> sink(TraceEvent.PaperSeen(rect, stable)) }.also { f ->
                if (io.embodit.tracelight.ui.trace.debug.Probe.enabled) f.saveFramesTo = appContext?.getExternalFilesDir(null)?.let { java.io.File(it, "cam") }
            }
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
        rig.world(PanelIds.CALIB, PanelSizes.CALIB_W, PanelSizes.CALIB_H, PANEL_SCALE)
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
     * the main thread for all of them raised an ANR in Spatial Ruler: the build yields after every step (S00).
     */
    private suspend fun build(node: Entity, t0: Long) {
        // let the Stage take focus before the first mesh: a mesh waits for a rendered frame and the
        // emulator idles near 1 fps (first launch ANR: "Waited 5000ms for FocusEvent", MeshResource.load)
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
        divider = Strip(node, meshes.dashes, m.divider, "divider")
        offsetLine = Strip(node, meshes.band, m.coral, "offset")
        delay(BUILD_STEP_MS)
        repeat(4) { corners += CornerHandle(node, meshes, m, it) }
        delay(BUILD_STEP_MS)
        MeshFactory.polylines("crosses", Calibration.crosses().map { l -> l.map { it.local() } }, Calibration.LINE_MM / 1000f)?.let { crosses = SheetMesh(node, it, m.traceBlue, "crosses") }
        crossesSize = 0.210f to 0.297f
        delay(BUILD_STEP_MS)
        MeshFactory.polylines("testFigure", listOf(Calibration.testCircle().map { it.local() }, Calibration.testSquare().map { it.local() }), GUIDE_W_M)?.let { testFigure = SheetMesh(node, shared.keep(it), m.traceBlue, "testFigure") }
        delay(BUILD_STEP_MS)
        built = true
        TraceLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(TraceEvent.SceneReady)
    }

    private fun startSensing() {
        planes.start { colliders?.sync(planes.all) }
        startCameraWhenAllowed()
    }

    /** The camera needs CAMERA and SPATIAL_DATA (readback); checked again every 2 s until both are granted. */
    private fun startCameraWhenAllowed() {
        val ctx = appContext ?: return
        val ok = listOf(android.Manifest.permission.CAMERA, "com.picovr.permission.SPATIAL_DATA").all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (ok) {
            TraceGraph.camera.start(ctx)
            return
        }
        if (!cameraWaitLogged) TraceLog.w("CAM waiting for camera permission: automatic alignment off, manual frame works")
        cameraWaitLogged = true
        main.postDelayed({ if (attached && !paused) startCameraWhenAllowed() }, 2000L)
    }

    private var cameraWaitLogged = false

    /** Set by the screen before [attach]: the camera needs a context. */
    var appContext: android.content.Context? = null

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        head.stop()
        controller.stop()
        TraceGraph.camera.stop()
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
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        controller.stop()
        TraceGraph.camera.stop()
        TraceGraph.anchors.stop()
        panelShown.clear()
        corners.clear()
        crosses = null
        crossesSize = null
        testFigure = null
        finder = null
        ghost = null
        ghostX = emptyList()
        frame = null
        divider = null
        offsetLine = null
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
        if (state.draft != before.draft) liveCorner = null
        if (state.anchorId != anchorId) bindAnchor(state.anchorId)
        val calib = state.designState == "S20"
        if (calib) {
            rig[PanelIds.LESSON]?.hide()
        } else {
            rig[PanelIds.CALIB]?.hide()
        }
    }

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
            finder?.step(active = (ui.designState == "S03" || ui.designState == "S20") && hasHead, now = now)
            dumpFirstFrame()
            pollController(now)
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
                ghostPose = PaperPose(hit.point, yawOf(pose.level()))
                ghostPlane = hit.plane
            } else {
                ghostPose = null
                ghostPlane = null
            }
        }
        if (ui.found != null) {
            // the camera sees the sheet: its outline replaces the ghost (drawSheet)
            g.hide()
            ghostX.forEach { it.hide() }
            return
        }
        val gp = ghostPose
        val onDesk = gp != null
        if (onDesk != lastGhostOnDesk || frozen != lastGhostFrozen) {
            lastGhostOnDesk = onDesk
            lastGhostFrozen = frozen
            sink(TraceEvent.GhostChanged(onDesk, frozen))
        }
        if (gp != null) {
            g.draw(CornerDraft.of(gp).points, gp.center.y + LIFT_M, GHOST_W_M)
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

    /** True when the head ray passes through the lesson panel (design S03: the ghost freezes). */
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

    /** The corner points as drawn now: the dragged one live, all of them turned while the stick is held. */
    private fun liveDraft(): CornerDraft? {
        var d = ui.draft ?: return null
        liveCorner?.let { (i, p) -> d = d.with(i, p).copy(moved = d.moved) }
        if (turning && turnYaw != 0f) d = d.turned(turnYaw)
        return d
    }

    private fun drawSheet(now: Long) {
        val m = materials ?: return
        val f = frame ?: return
        val div = divider ?: return
        val off = offsetLine ?: return
        val lift = LIFT_M + ui.calib.heightMm / 1000f
        val draft = if (ui.aligning) liveDraft() else null
        val aligned = ui.aligned
        when {
            draft != null -> {
                f.material(m.amber)
                f.draw(draft.points, draft.y + lift, FRAME_W_M)
                div.hide()
                val fit = PaperFit.fit(draft, Sheet.A4)
                val worst = if (ui.designState == "S05") ui.verdict?.a4?.worst else null
                for (c in corners) {
                    val look =
                        when {
                            worst == c.index -> CornerHandle.Look.OFF
                            c.index == ui.activeCorner -> CornerHandle.Look.ACTIVE
                            ui.recheck && c.index < ui.confirmed -> CornerHandle.Look.PLACED
                            !ui.recheck && draft.moved[c.index] -> CornerHandle.Look.PLACED
                            else -> CornerHandle.Look.IDLE
                        }
                    c.draw(draft.points[c.index], draft.y + lift + 0.0002f, fit.pose.right, fit.pose.away, look, now)
                }
                if (worst != null) {
                    val target = P2.of(fit.pose.toWorld(Sheet.A4.corners[worst]))
                    off.draw(draft.points[worst].at(draft.y + lift), target.at(draft.y + lift), OFFSET_W_M, Vec3.UP)
                } else off.hide()
                crosses?.show(false)
                testFigure?.show(false)
            }
            aligned != null -> {
                corners.forEach { it.show(false) }
                off.hide()
                val flashing = flashStartMs > 0 && now - flashStartMs < FLASH_MS
                f.material(if (flashing) m.warm else m.faint)
                val rect = SheetRect(aligned, ui.sheetW, ui.sheetH)
                f.draw(rect.corners, aligned.center.y + lift, FRAME_W_M)
                val halfMm = ui.sheetW * 500f
                div.draw(aligned.toWorld(Mm(-halfMm, 0f), lift), aligned.toWorld(Mm(halfMm, 0f), lift), DIVIDER_W_M, Vec3.UP)
                val calib = ui.designState == "S20" && !ui.trackingLost
                if (calib) ensureCrosses(ui.sheetW, ui.sheetH)?.place(aligned, lift + 0.0001f) else crosses?.show(false)
                if (calib && ui.calib.testFigure) testFigure?.place(aligned, lift + 0.0001f) else testFigure?.show(false)
            }
            ui.designState == "S03" && ui.found != null -> {
                // the sheet the camera sees (automatic alignment): amber while it settles, jade once stable
                val r = ui.found!!
                f.material(if (ui.foundStable) m.jade else m.amber)
                f.draw(r.corners, r.pose.center.y + lift, FOUND_W_M)
                div.hide()
                off.hide()
                corners.forEach { it.show(false) }
                crosses?.show(false)
                testFigure?.show(false)
            }
            else -> {
                f.hide()
                div.hide()
                off.hide()
                corners.forEach { it.show(false) }
                crosses?.show(false)
                testFigure?.show(false)
            }
        }
    }

    private var rawDumped = false

    /** Debug builds: the first camera frame's raw bytes, for checking the readback layout on a new device. */
    private fun dumpFirstFrame() {
        if (rawDumped || !io.embodit.tracelight.ui.trace.debug.Probe.enabled) return
        val f = TraceGraph.camera.latest ?: return
        rawDumped = true
        val dir = appContext?.getExternalFilesDir(null)?.let { java.io.File(it, "cam") } ?: return
        scope?.launch(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                java.io.File(dir, "first.bin").writeBytes(f.rgba)
                TraceLog.i("CAM first frame bytes=${f.rgba.size} w=${f.width} h=${f.height} k=${f.camMatrix.joinToString(",")}")
            }.onFailure { TraceLog.w("CAM first dump failed: $it") }
        }
    }

    /** Calibration crosses for a sheet of [w] x [h] metres (rebuilt when the size changes). */
    private fun ensureCrosses(w: Float, h: Float): SheetMesh? {
        val size = w to h
        if (crossesSize == size && crosses != null) return crosses
        val shared = shared ?: return crosses
        val m = materials ?: return crosses
        val node = sheetNode ?: return crosses
        crosses?.let { old -> old.show(false); runCatching { old.destroy() } }
        crosses = MeshFactory.polylines("crosses", Calibration.crosses(w * 1000f, h * 1000f).map { l -> l.map { it.local() } }, Calibration.LINE_MM / 1000f)?.let { SheetMesh(node, it, m.traceBlue, "crosses") }
        crossesSize = size
        TraceLog.i(String.format(Locale.US, "CROSSES for %.0fx%.0f mm", w * 1000f, h * 1000f))
        return crosses
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
        // back to Stage space first
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
            val moved = p.distanceTo(p0) * 1000f
            // where the sheet's centre is drawn now vs at alignment (both Stage space)
            val c = ui.aligned?.center
            val drawn = c?.let { runCatching { node.convertPositionTo(it.sdk(), stageRoot).domain() }.getOrNull() }
            TraceLog.i(String.format(Locale.US, "DRIFT anchor_moved_mm=%.1f center_drawn=%s center_aligned=%s head=%s", moved, TraceLog.pos(drawn), TraceLog.pos(c), TraceLog.pos(pose.position)))
        }
    }

    // =========================================================================================
    // thumbstick turn + controller tip
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

    /**
     * Design T4: with a controller, a trigger press while its tracked point is within 3 cm of the desk
     * and 30 cm of the sheet puts the current corner right below it. The tip offset of the controller
     * model is unconfirmed: the raw pose is logged with every press (`TIP`).
     */
    private fun pollController(now: Long) {
        val (l, r) = controller.poses()
        val tracked = l != null || r != null
        if (tracked != lastController) {
            lastController = tracked
            sink(TraceEvent.ControllerChanged(tracked))
        }
        val (pl, pr) = controller.takePresses()
        if (!pl && !pr) return
        val p = (if (pr) r else null) ?: (if (pl) l else null) ?: return
        val d = ui.draft
        TraceLog.i("TIP press hand=${if (pr) "right" else "left"} pos=${TraceLog.pos(p.position)} ${TraceLog.quat(p.rotation)} state=${ui.designState}")
        if (!ui.dragEnabled || d == null) return
        if (abs(p.position.y - d.y) > TIP_DESK_M) return
        if (P2.of(p.position).minus(d.centroid).length > TIP_SHEET_M) return
        sink(TraceEvent.CornerMoved(ui.activeCorner, P2.of(p.position)))
    }

    // =========================================================================================
    // panels on the desk
    // =========================================================================================

    /** S00 - S02 (design `spatialLayout.panel`): 0.60 m from the eyes, yaw only, 12 degrees below the level gaze. */
    private fun lessonRest(h: HeadPose): Vec3 = restAt(h, 0f, -LESSON_DOWN_DEG, LESSON_M)

    private fun restAt(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    private fun panelLift(): Float = PANEL_BASE_M + PanelSizes.LESSON_H * DP_M * PANEL_SCALE / 2f

    /** Where the panel stands for a sheet (paper (-220, +200) mm, mirrored for the left hand; 30 mm above the desk). */
    private fun besideSheet(p: PaperPose): Vec3 {
        val side = if (ui.settings.hand == Hand.LEFT) 1f else -1f
        return p.toWorld(Mm(side * PANEL_SIDE_MM, PANEL_AWAY_MM), panelLift())
    }

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val calib = ui.designState == "S20"
        val slot = rig[if (calib) PanelIds.CALIB else PanelIds.LESSON] ?: return
        val state = ui.designState
        val draft = ui.draft
        val aligned = ui.aligned
        val at: Vec3? =
            when {
                state in setOf("S00", "S01", "S02", "S16", "S18") -> {
                    deskPanelFor = null
                    lessonFollow.step(pose, now, hold = false)?.let { slot.show(it) } ?: slot.show()
                    return
                }
                state == "S03" -> {
                    // placed once on entering S03 at the first desk hit, never chasing the ghost (R2-3)
                    if (deskPanelFor != "S03") {
                        val gp = ghostPose ?: raycast(desks(), pose.position, pose.forward, 3f)?.let { PaperPose(it.point, yawOf(pose.level())) }
                        if (gp != null) {
                            deskPanelAt = besideSheet(gp)
                            deskPanelFor = "S03"
                            TraceLog.i("PANEL desk at=${TraceLog.pos(deskPanelAt)}")
                        }
                    }
                    deskPanelAt
                }
                draft != null -> {
                    val key = "draft"
                    if (deskPanelFor != key) {
                        deskPanelAt = besideSheet(PaperFit.fit(draft, Sheet.A4).pose)
                        deskPanelFor = key
                    }
                    deskPanelAt
                }
                aligned != null -> {
                    val key = "aligned"
                    if (deskPanelFor != key) {
                        deskPanelAt = besideSheet(aligned)
                        deskPanelFor = key
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

    private fun cornerOf(entity: Entity?): Int? {
        if (entity == null) return null
        for (c in corners) if (c.owns(entity)) return c.index
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
        if (ui.designState != "S03") return
        // the pinch lands where the pointer hit a desk; else where the ghost is (head ray)
        val onDesk = stage?.let { s -> desks().firstOrNull { abs(it.centroid.y - s.y) < 0.02f && it.contains(s) } }
        val drop =
            when {
                onDesk != null && stage != null -> PaperPose(Vec3(stage.x, onDesk.centroid.y, stage.z), yawOf(pose.level()))
                ghostPose != null -> ghostPose!!.let { it.copy(center = Vec3(it.center.x, ghostPlane?.centroid?.y ?: it.center.y, it.center.z)) }
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
                val i = cornerOf(target)
                TraceLog.i("DRAG start target=$name corner=${i?.plus(1) ?: "-"} stage=${TraceLog.pos(press)}")
                if (i == null || press == null) return
                val d = ui.draft ?: return
                if (!ui.dragEnabled) {
                    TraceLog.i("DRAG refused state=${ui.designState}")
                    return
                }
                drag = Drag(i, press, P2.of(press) - d.points[i]).also {
                    it.gain = ui.calib.gain
                    it.y = d.y
                }
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
                    // gain 0.5 (design `corners.dragGain`): the hand moves 2 cm, the corner 1 cm
                    val step = (d.hand - d.prevHand) * d.gain
                    val turned = DragLine.turn(dir, step, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    val hit = DragLine.onLevel(pose.position, turned, d.press.y) ?: return
                    liveCorner = d.corner to (hit - d.offset)
                    if (d.moves <= 3 || now - d.lastLogMs > 500) {
                        d.lastLogMs = now
                        TraceLog.i("DRAG move corner=${d.corner + 1} live=${liveCorner?.second?.let { String.format(Locale.US, "(%.4f, %.4f)", it.x, it.z) }} n=${d.moves}")
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
                TraceLog.i("DRAG $phase corner=${d.corner + 1} n=${d.moves}")
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
        TraceLog.i("DRAG begin corner=${d.corner + 1} gain=${d.gain} depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
    }

    private fun endDrag(cancelled: Boolean) {
        val live = liveCorner
        drag = null
        if (live == null) return
        if (cancelled) {
            liveCorner = null
            return
        }
        sink(TraceEvent.CornerMoved(live.first, live.second))
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

    /**
     * Debug `seed`: a sheet on the desk below the head, its top edge along the level gaze, centred
     * [aheadM] ahead of the eyes (default 0.40 m) where the desk allows.
     */
    fun debugSeed(aheadM: Float = 0.40f): PaperPose? {
        val f = pose.level()
        var c = P2.of(pose.position) + f * aheadM
        val desk = desks().firstOrNull { it.contains(c.at(it.centroid.y)) } ?: desks().firstOrNull() ?: return null
        val y = desk.centroid.y
        if (!desk.contains(c.at(y))) c = P2.of(desk.centroid)
        return PaperPose(c.at(y), yawOf(f))
    }

    /** Debug `drag`: corner [i] glides to [to] (Stage) as a drag release would leave it. */
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
        val node = sheetNode
        out += "SHEET anchor=${anchorId?.take(8)} bound=$anchorBound entity=${anchorStagePose()?.let { "${TraceLog.pos(it.first)} ${TraceLog.quat(it.second)}" }} still=$anchorStill"
        ui.aligned?.let { a ->
            for ((k, mm) in (Calibration.targets).withIndex()) {
                val w = a.toWorld(mm)
                val drawn = runCatching { node?.convertPositionTo(w.sdk(), stageRoot)?.domain() }.getOrNull()
                out += String.format(Locale.US, "CROSS %d mm=(%.0f, %.0f) stage=%s drawn=%s", k, mm.x, mm.y, TraceLog.pos(w), TraceLog.pos(drawn))
            }
        }
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
        const val PANEL_SIDE_MM = 220f
        const val PANEL_AWAY_MM = 200f
        const val PANEL_BASE_M = 0.03f
        const val LIFT_M = 0.0005f
        const val FRAME_W_M = 0.001f
        const val GHOST_W_M = 0.0015f
        const val DIVIDER_W_M = 0.0006f
        const val OFFSET_W_M = 0.0006f
        const val GUIDE_W_M = 0.0008f
        const val FOUND_W_M = 0.0015f
        const val X_HALF_M = 0.012f
        const val X_W_M = 0.002f
        const val FLASH_MS = 600L
        const val DRAG_START_M = 0.005f
        const val TAP_AFTER_DRAG_MS = 300L
        const val LOG_MS = 5_000L
        const val DRIFT_LOG_MS = 5_000L
        const val ANCHOR_STILL_FRAMES = 5
        const val STICK_RELEASE_MS = 150L
        const val TIP_DESK_M = 0.03f
        const val TIP_SHEET_M = 0.30f
    }
}

/**
 * Lazy follow (Spatial Ruler T11): the panel rests where it is; once its centre has been more than
 * [windowDeg] off the view direction, or nearer than [nearM] or farther than [farM], for 0.5 s, it
 * slides (0.35 s, ease-in-out cubic) to the rest pose of that moment.
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
        // yaw only (design S00 - S02): the angle is measured in the horizontal plane
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
