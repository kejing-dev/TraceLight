package io.embodit.tracelight.debug

import android.content.Context
import io.embodit.tracelight.domain.CornerDraft
import io.embodit.tracelight.domain.Library
import io.embodit.tracelight.domain.Mm
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperFit
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.PlaneSurface
import io.embodit.tracelight.domain.Sheet
import io.embodit.tracelight.domain.SheetRect
import io.embodit.tracelight.platform.TraceGraph
import io.embodit.tracelight.platform.TraceLog
import io.embodit.tracelight.ui.trace.TraceEvent
import io.embodit.tracelight.ui.trace.TraceViewModel
import io.embodit.tracelight.ui.trace.debug.Probe
import io.embodit.tracelight.ui.trace.scene.TraceScene
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test hooks of the debug build. One log line (tag `TraceLight`) answers every command. Broadcast:
 * `am broadcast -a io.embodit.tracelight.DEBUG -p io.embodit.tracelight --es cmd NAME [--es s TEXT] [--es a NUMBERS]`.
 *
 * Designed states (QA screenshots; a desk must be known from S03 on: `desk` makes one in front of the camera)
 * - `state` s=S00 | S01 | S02 | S03 | S04 [a=k corners placed] | S06 | S07 | S09 [a=step] | S12 | S14 | S16
 *   S07 / S09 / S12 / S14 seed an A4 sheet on the desk ahead first; S09 / S12 use the piece in `piece` (default cats)
 *
 * Driving
 * - `desk` [a=aheadM,belowM] · `seed` [a=aheadM] (= a pinch in S03) · `sheet` a=wMm,hMm (align a sheet of that size ahead)
 * - `piece` s=<id> [a=step] (lessons: lines-1 .. characters-5) · `set` s=<setId>
 * - `drag` a=i,dx,dy (corner i 1..4, mm in the sheet's axes) · `move` a=dx,dy (whole frame, mm) · `confirm`
 * - `stick` a=x,ms · `ui` s=<control> · `hold` s=on|off · `nodesk` s=on|off · `tracking` s=lost|ok
 *
 * Reading back
 * - `dump` (also files/dump.json) · `pose` · `panels` · `planes` · `sheet` · `bounds` · `pieces`
 */
internal object DebugCommands {
    private const val NO_HOST = "CMD ignored: no Stage"
    private var piece = "animals-3"

    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val host = Probe.host
        if (host == null) {
            TraceLog.w(NO_HOST)
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val nums = a?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        when (cmd) {
            "state" -> state(vm, scene, s ?: "S04", nums)
            "desk" -> {
                // a 1.2 x 0.7 m TABLE plane [a0] ahead of the eyes and [a1] below them (emulator: its desks are metres away)
                val h = scene.headPose() ?: return no("no head")
                val ahead = nums.getOrNull(0) ?: 0.45f
                val below = nums.getOrNull(1) ?: 0.55f
                val f = h.level()
                val r = P2(-f.z, f.x)
                val c = P2.of(h.position) + f * ahead
                val y = h.position.y - below
                val pts = listOf(c - r * 0.6f - f * 0.25f, c + r * 0.6f - f * 0.25f, c + r * 0.6f + f * 0.45f, c - r * 0.6f + f * 0.45f).map { it.at(y) }
                TraceGraph.planes.debugPut(PlaneSurface("debug-desk-0000-0000", "TABLE", pts))
                TraceLog.i("CMD desk y=$y ahead=$ahead")
            }
            "seed" -> {
                val p = scene.debugSeed(nums.firstOrNull() ?: 0.40f) ?: return no("no desk")
                if (vm.current().designState != "S03") vm.debugJump("S03")
                vm.onEvent(TraceEvent.FrameDropped(p))
                TraceLog.i("CMD seed ${fmt(p)}")
            }
            "sheet" -> {
                val p = scene.debugSeed() ?: return no("no desk")
                val w = (nums.getOrNull(0) ?: 210f) / 1000f
                val h = (nums.getOrNull(1) ?: 297f) / 1000f
                vm.debugAligned(p, w, h, then = "S07")
                TraceLog.i("CMD sheet ${w}x$h ${fmt(p)}")
            }
            "piece" -> {
                piece = s ?: piece
                if (Library.piece(piece) == null) return no("no piece $piece")
                if (vm.current().aligned == null) scene.debugSeed()?.let { vm.debugAligned(it, then = "S07") }
                vm.debugPiece(piece, (nums.firstOrNull() ?: 0f).toInt())
            }
            "set" -> vm.onEvent(TraceEvent.SetPicked(s ?: "lines"))
            "drag" -> {
                val st = vm.current()
                val d = st.draft ?: return no("no draft")
                val i = (nums.getOrNull(0) ?: 1f).toInt().coerceIn(1, 4) - 1
                val fit = PaperFit.fit(d, Sheet.A4).pose
                val to = d.points[i] + fit.right * ((nums.getOrNull(1) ?: 0f) / 1000f) + fit.away * ((nums.getOrNull(2) ?: 0f) / 1000f)
                scene.debugCorner(i, to)
                TraceLog.i("CMD drag corner=${i + 1} to=(${to.x}, ${to.z})")
            }
            "move" -> {
                val d = vm.current().draft ?: return no("no draft")
                val fit = PaperFit.fit(d, Sheet.A4).pose
                vm.onEvent(TraceEvent.FrameMoved(fit.right * ((nums.getOrNull(0) ?: 0f) / 1000f) + fit.away * ((nums.getOrNull(1) ?: 0f) / 1000f)))
            }
            "confirm" -> vm.debugConfirm()
            "stick" -> TraceLog.i("CMD ${scene.debugStick(nums.getOrNull(0) ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong())}")
            "ui" -> ui(vm, s ?: "")
            "hold" -> vm.holdTransient = s != "off"
            "nodesk" -> scene.debugNoDesk = s != "off"
            "tracking" -> TraceGraph.head.forceLost = s == "lost"
            "park" -> scene.parkPanels()
            "dump" -> dump(context, vm, scene)
            "pose" -> TraceLog.i(scene.poseLine())
            "panels" -> scene.panelLines().forEach { TraceLog.i(it) }
            "planes" -> scene.planeLines().forEach { TraceLog.i(it) }
            "sheet_lines" -> scene.sheetLines().forEach { TraceLog.i(it) }
            "pieces" -> Library.all.forEach { TraceLog.i("PIECE ${it.id} ${it.zh} level=${it.level} steps=${it.steps.size}") }
            "bounds" -> bounds()
            else -> TraceLog.w("CMD unknown '$cmd'")
        }
    }

    private fun no(why: String) {
        TraceLog.w("CMD failed: $why")
    }

    private fun fmt(p: PaperPose) = String.format(Locale.US, "center=%s yaw=%.1f", TraceLog.pos(p.center), Math.toDegrees(p.yaw.toDouble()))

    private fun state(vm: TraceViewModel, scene: TraceScene, id: String, nums: List<Float>) {
        val seeded = { scene.debugSeed() }
        when (id) {
            "S00", "S01", "S02", "S16" -> vm.debugJump(id)
            "S03" -> vm.debugJump("S03")
            "S04" -> {
                val p = seeded() ?: return no("no desk")
                val k = (nums.firstOrNull() ?: 2f).toInt().coerceIn(0, 4)
                val d = CornerDraft.of(SheetRect(p, 0.21f, 0.297f)).let { base -> base.copy(moved = List(4) { it < k }) }
                vm.debugDraft(d, activeCorner = if (k >= 4) 3 else k)
            }
            "S06" -> {
                val p = seeded() ?: return no("no desk")
                vm.holdTransient = true
                vm.debugAligned(p)
            }
            "S07" -> {
                val p = seeded() ?: return no("no desk")
                vm.holdTransient = false
                vm.debugAligned(p, then = "S07")
            }
            "S09" -> {
                if (vm.current().aligned == null) seeded()?.let { vm.debugAligned(it, then = "S07") }
                vm.debugPiece(piece, (nums.firstOrNull() ?: 1f).toInt())
            }
            "S12" -> {
                if (vm.current().aligned == null) seeded()?.let { vm.debugAligned(it, then = "S07") }
                val p = Library.piece(piece) ?: return no("no piece")
                vm.debugPiece(piece, p.steps.size - 1)
                vm.onEvent(TraceEvent.NextStepClicked)
            }
            "S14" -> {
                if (vm.current().aligned == null) seeded()?.let { vm.debugAligned(it, then = "S07") }
                vm.onEvent(TraceEvent.FreeDrawClicked)
            }
            else -> return no("state $id not in this build")
        }
        TraceLog.i("CMD state $id")
    }

    private fun ui(vm: TraceViewModel, control: String) {
        val e: TraceEvent =
            when (control) {
                "ready" -> TraceEvent.ReadyClicked
                "confirm" -> TraceEvent.ConfirmClicked
                "restart" -> TraceEvent.RestartClicked
                "cancel" -> TraceEvent.CancelClicked
                "retryDesk" -> TraceEvent.RetryDeskClicked
                "next" -> TraceEvent.NextStepClicked
                "prev" -> TraceEvent.PrevStepClicked
                "nextPiece" -> TraceEvent.NextPieceClicked
                "library" -> TraceEvent.LibraryClicked
                "free" -> TraceEvent.FreeDrawClicked
                "grid" -> TraceEvent.GridClicked
                "menu" -> TraceEvent.MenuClicked
                "realign" -> TraceEvent.RealignClicked
                else -> return no("ui '$control'")
            }
        vm.onEvent(e)
    }

    private fun dump(context: Context, vm: TraceViewModel, scene: TraceScene) {
        val st = vm.current()
        val o = JSONObject()
        o.put("designState", st.designState)
        o.put("deskFound", st.deskFound)
        o.put("activeCorner", st.activeCorner + 1)
        st.draft?.let { d -> o.put("draft", JSONArray(d.points.mapIndexed { i, p -> JSONObject().put("x", p.x.toDouble()).put("z", p.z.toDouble()).put("moved", d.moved[i]) })); o.put("draftY", d.y.toDouble()) }
        st.aligned?.let { a -> o.put("aligned", JSONObject().put("x", a.center.x.toDouble()).put("y", a.center.y.toDouble()).put("z", a.center.z.toDouble()).put("yawDeg", Math.toDegrees(a.yaw.toDouble())).put("wMm", st.sheetW * 1000.0).put("hMm", st.sheetH * 1000.0)) }
        o.put("anchor", st.anchorId ?: "")
        o.put("set", st.setId).put("piece", st.pieceId ?: "").put("step", st.step).put("done", JSONArray(st.done.toList())).put("grid", st.grid).put("menu", st.menuOpen)
        o.put("controller", st.controller)
        val text = o.toString()
        TraceLog.i("DUMP $text")
        scene.sheetLines().forEach { TraceLog.i(it) }
        runCatching { File(context.filesDir, "dump.json").writeText(text) }
    }

    private fun bounds() {
        for (panel in listOf("lesson", "draw")) {
            val size = Probe.sizeOf(panel)
            for ((name, b) in Probe.controlsOf(panel)) TraceLog.i(String.format(Locale.US, "BOUNDS panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f panel_px=%s", panel, name, b.cx, b.cy, b.w, b.h, size))
        }
    }

    @Suppress("unused")
    private fun mm(x: Float, y: Float) = Mm(x, y)
}
