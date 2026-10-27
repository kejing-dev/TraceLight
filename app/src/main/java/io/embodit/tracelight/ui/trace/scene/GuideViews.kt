package io.embodit.tracelight.ui.trace.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import io.embodit.tracelight.domain.LessonFit
import io.embodit.tracelight.domain.Mm
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.Piece
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.platform.TraceLog
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A drawing on the sheet (S09 / S12): one flat mesh per step in the sheet's own coordinates (built once
 * per drawing and sheet size, one mesh about every frame), the current step in trace blue, earlier
 * steps faded, later ones hidden; an amber pen dot walks the current step's strokes in order and
 * direction, then rests and starts again. The free-drawing grid (S14) lives here too.
 */
internal class GuideViews(private val node: Entity, private val meshes: TraceMeshes, private val m: TraceMaterials, private val scope: CoroutineScope) {
    private class StepMeshes(val solid: SheetMesh?, val dashed: SheetMesh?, val path: List<List<Mm>>)

    private var built: Triple<String, Float, Float>? = null
    private val steps = ArrayList<StepMeshes>()
    private var job: Job? = null
    private var grid: SheetMesh? = null
    private var gridFor: Pair<Float, Float>? = null
    private val pen: Entity = ModelEntity(meshes.disc, m.amber)
    private var penOn = true
    private var penStep = -1
    private var penStartMs = 0L

    init {
        runCatching { pen.setName("penTip") }
        node.addChild(pen)
        showPen(false)
    }

    /**
     * [mode]: TRACE shows steps up to [step] (that one bright) and the pen; DONE shows every step faded;
     * HIDDEN shows nothing. Rebuilds when the drawing or the sheet size changes.
     */
    fun draw(piece: Piece?, step: Int, pose: PaperPose?, wM: Float, hM: Float, mode: Mode, lift: Float, nowMs: Long) {
        if (piece == null || pose == null || mode == Mode.HIDDEN) {
            hideAll()
            return
        }
        val key = Triple(piece.id, wM, hM)
        if (key != built) rebuild(piece, wM, hM, key)
        for ((i, s) in steps.withIndex()) {
            val visible = mode == Mode.DONE || i <= step
            val current = mode == Mode.TRACE && i == step
            for (mesh in listOfNotNull(s.solid, s.dashed)) {
                if (!visible) {
                    mesh.show(false)
                    continue
                }
                mesh.material(if (current) m.traceBlue else m.traceBluePast)
                mesh.place(pose, lift + if (current) 0.0002f else 0f)
            }
        }
        if (mode == Mode.TRACE) stepPen(step, pose, lift, nowMs) else showPen(false)
    }

    /** The pen dot: 45 mm/s along the step's strokes, a 1.2 s rest, again. */
    private fun stepPen(step: Int, pose: PaperPose, lift: Float, nowMs: Long) {
        val s = steps.getOrNull(step) ?: return showPen(false)
        if (step != penStep) {
            penStep = step
            penStartMs = nowMs
        }
        val total = s.path.sumOf { length(it).toDouble() }.toFloat()
        if (total <= 0f) return showPen(false)
        val cycle = total / PEN_MM_S + PEN_REST_S
        var t = (((nowMs - penStartMs) / 1000f) % cycle) * PEN_MM_S
        if (t > total) t = total
        val at = pointAt(s.path, t) ?: return showPen(false)
        pen.setLocal(position = pose.toWorld(at, lift + 0.0006f), scale = Vec3(PEN_M, 1f, PEN_M))
        showPen(true)
    }

    private fun length(l: List<Mm>): Float {
        var s = 0f
        for (i in 0 until l.size - 1) s += hypot(l[i + 1].x - l[i].x, l[i + 1].y - l[i].y)
        return s
    }

    private fun pointAt(path: List<List<Mm>>, dist: Float): Mm? {
        var left = dist
        for (l in path) {
            for (i in 0 until l.size - 1) {
                val a = l[i]
                val b = l[i + 1]
                val d = hypot(b.x - a.x, b.y - a.y)
                if (left <= d) {
                    val k = if (d < 1e-6f) 0f else left / d
                    return Mm(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
                }
                left -= d
            }
        }
        return path.lastOrNull()?.lastOrNull()
    }

    private fun rebuild(piece: Piece, wM: Float, hM: Float, key: Triple<String, Float, Float>) {
        job?.cancel()
        clearSteps()
        built = key
        penStep = -1
        val fit = LessonFit(wM * 1000f, hM * 1000f)
        // the paths are ready at once (the pen); the meshes come one per frame
        val pending = piece.steps.map { st -> st.strokes.map { k -> k to k.points.map { fit.mm(it) } } }
        for (p in pending) steps += StepMeshes(null, null, p.map { it.second })
        job =
            scope.launch {
                for ((i, p) in pending.withIndex()) {
                    val solid = p.filter { !it.first.dashed }.map { it.second.map { q -> q.local() } }
                    val dashed = p.filter { it.first.dashed }.map { it.second.map { q -> q.local() } }
                    val sm = if (solid.isEmpty()) null else MeshFactory.polylines("guide:${piece.id}:$i", solid, GUIDE_W_M)?.let { SheetMesh(node, it, m.traceBluePast, "guide:$i") }
                    delay(MESH_GAP_MS)
                    val dm = if (dashed.isEmpty()) null else MeshFactory.polylines("guideDash:${piece.id}:$i", dashed, GUIDE_W_M, dash = DASH_M)?.let { SheetMesh(node, it, m.traceBluePast, "guideDash:$i") }
                    if (built != key) {
                        sm?.destroy()
                        dm?.destroy()
                        return@launch
                    }
                    steps[i] = StepMeshes(sm, dm, steps[i].path)
                    delay(MESH_GAP_MS)
                }
                TraceLog.i("GUIDE built ${piece.id} steps=${pending.size} scale=${fit.scale}")
            }
    }

    /** S14: a faint 20 mm grid inside 15 mm margins. */
    fun drawGrid(on: Boolean, pose: PaperPose?, wM: Float, hM: Float, lift: Float) {
        if (!on || pose == null) {
            grid?.show(false)
            return
        }
        val size = wM to hM
        if (gridFor != size || grid == null) {
            grid?.destroy()
            val w = wM * 500f - 15f
            val h = hM * 500f - 15f
            val lines = ArrayList<List<Vec3>>()
            var x = 0f
            while (x <= w) {
                for (sx in if (x == 0f) listOf(0f) else listOf(x, -x)) lines += listOf(Mm(sx, -h).local(), Mm(sx, h).local())
                x += GRID_MM
            }
            var y = 0f
            while (y <= h) {
                for (sy in if (y == 0f) listOf(0f) else listOf(y, -y)) lines += listOf(Mm(-w, sy).local(), Mm(w, sy).local())
                y += GRID_MM
            }
            grid = MeshFactory.polylines("grid", lines, GRID_W_M)?.let { SheetMesh(node, it, m.grid, "grid") }
            gridFor = size
        }
        grid?.place(pose, lift)
    }

    private fun showPen(visible: Boolean) {
        if (visible == penOn) return
        penOn = visible
        pen.enabled = visible
    }

    private fun hideAll() {
        for (s in steps) {
            s.solid?.show(false)
            s.dashed?.show(false)
        }
        showPen(false)
    }

    private fun clearSteps() {
        for (s in steps) {
            s.solid?.destroy()
            s.dashed?.destroy()
        }
        steps.clear()
    }

    fun release() {
        job?.cancel()
        clearSteps()
        grid?.destroy()
        grid = null
        built = null
        runCatching { pen.destroy(true) }
    }

    enum class Mode { TRACE, DONE, HIDDEN }

    companion object {
        const val GUIDE_W_M = 0.001f
        const val DASH_M = 0.004f
        const val GRID_MM = 20f
        const val GRID_W_M = 0.0005f
        const val PEN_M = 0.006f
        const val PEN_MM_S = 45f
        const val PEN_REST_S = 1.2f
        const val MESH_GAP_MS = 30L
    }
}
