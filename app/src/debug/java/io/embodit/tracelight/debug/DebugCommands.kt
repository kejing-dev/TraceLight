package io.embodit.tracelight.debug

import android.content.Context
import io.embodit.tracelight.domain.CornerDraft
import io.embodit.tracelight.domain.Mm
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperFit
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.PlaneSurface
import io.embodit.tracelight.domain.Sheet
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
 * Test hooks of the debug build (design `verification.debugHooks`). One log line (tag `TraceLight`)
 * answers every command. Broadcast: `am broadcast -a io.embodit.tracelight.DEBUG -p io.embodit.tracelight --es cmd NAME [--es s TEXT] [--es a NUMBERS]`.
 *
 * Designed states (QA screenshots; the desk must be known for S03 - S20)
 * - `state` s=S00..S06, S16, S18, S20 [a=k]   jump there; S04 a=k: k corners placed (default 2); S05 s2 via `corners`
 *
 * Driving
 * - `desk` [a=aheadM,belowM]  a TABLE plane in front of the camera (default 0.45 m ahead, 0.55 m below the eyes)
 * - `seed` [a=aheadM]          drop a sheet on the desk ahead (= a pinch in S03), -> S04
 * - `corners` a=x1,y1,...,x4,y4   the four corner points in mm of the seeded sheet (all placed); `corners` s=off8 / letter
 * - `drag` a=i,dx,dy           move corner i (1..4) by dx, dy mm in the sheet's axes (= a drag release)
 * - `confirm` · `recheck` · `stick` a=x,ms · `ui` s=<control> · `hold` s=on|off · `nodesk` s=on|off · `tracking` s=lost|ok
 *
 * Reading back
 * - `dump` (also files/dump.json) · `pose` · `panels` · `planes` · `sheet` · `bounds`
 */
internal object DebugCommands {
    private const val NO_HOST = "CMD ignored: no Stage"

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
            "corners" -> corners(vm, scene, s, nums)
            "drag" -> {
                val st = vm.current()
                val d = st.draft ?: return no("no draft")
                val i = (nums.getOrNull(0) ?: 1f).toInt().coerceIn(1, 4) - 1
                val fit = PaperFit.fit(d, Sheet.A4).pose
                val dx = (nums.getOrNull(1) ?: 0f) / 1000f
                val dy = (nums.getOrNull(2) ?: 0f) / 1000f
                val to = d.points[i] + fit.right * dx + fit.away * dy
                scene.debugCorner(i, to)
                TraceLog.i("CMD drag corner=${i + 1} to=(${to.x}, ${to.z})")
            }
            "confirm" -> vm.debugConfirm()
            "recheck" -> {
                val prev = vm.current().aligned ?: scene.debugSeed() ?: return no("no sheet")
                vm.debugRecheck(prev)
                TraceLog.i("CMD recheck ${fmt(prev)}")
            }
            "stick" -> TraceLog.i("CMD ${scene.debugStick(nums.getOrNull(0) ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong())}")
            "ui" -> ui(vm, s ?: "")
            "hold" -> vm.holdTransient = s != "off"
            "nodesk" -> scene.debugNoDesk = s != "off"
            "tracking" -> TraceGraph.head.forceLost = s == "lost"
            "park" -> scene.parkPanels()
            "dump" -> dump(context, vm, scene)
            "cam" -> cam(context, scene, s ?: "frame")
            "pose" -> TraceLog.i(scene.poseLine())
            "panels" -> scene.panelLines().forEach { TraceLog.i(it) }
            "planes" -> scene.planeLines().forEach { TraceLog.i(it) }
            "sheet" -> scene.sheetLines().forEach { TraceLog.i(it) }
            "bounds" -> bounds()
            else -> TraceLog.w("CMD unknown '$cmd'")
        }
    }

    private fun no(why: String) {
        TraceLog.w("CMD failed: $why")
    }

    private fun fmt(p: PaperPose) = String.format(Locale.US, "center=%s yaw=%.1f", TraceLog.pos(p.center), Math.toDegrees(p.yaw.toDouble()))

    private fun state(vm: TraceViewModel, scene: TraceScene, id: String, nums: List<Float>) {
        when (id) {
            "S00", "S01", "S02", "S16", "S18" -> vm.debugJump(id)
            "S03" -> vm.debugJump("S03")
            "S04" -> {
                val p = scene.debugSeed() ?: return no("no desk")
                val k = (nums.firstOrNull() ?: 2f).toInt().coerceIn(0, 4)
                val d = CornerDraft.of(p).let { base -> base.copy(moved = List(4) { it < k }) }
                vm.debugDraft(d, activeCorner = if (k >= 4) 3 else k)
            }
            "S04R" -> {
                val p = vm.current().aligned ?: scene.debugSeed() ?: return no("no desk")
                vm.debugRecheck(p)
            }
            "S05" -> {
                corners(vm, scene, "off8", emptyList())
                vm.debugConfirm()
            }
            "S05L" -> {
                corners(vm, scene, "letter", emptyList())
                vm.debugConfirm()
            }
            "S06" -> {
                val p = scene.debugSeed() ?: return no("no desk")
                vm.holdTransient = true
                vm.debugAligned(p)
            }
            "S20" -> {
                val p = scene.debugSeed() ?: return no("no desk")
                vm.holdTransient = false
                vm.debugAligned(p)
            }
            else -> return no("state $id not in this build")
        }
        TraceLog.i("CMD state $id")
    }

    /** Four corner points in mm of the seeded sheet; `off8` = A4 with ③ 8 mm out, `letter` = a US Letter sheet. */
    private fun corners(vm: TraceViewModel, scene: TraceScene, s: String?, nums: List<Float>) {
        val p = vm.current().draft?.let { PaperFit.fit(it, Sheet.A4).pose } ?: scene.debugSeed() ?: return no("no desk")
        val mm =
            when (s) {
                "off8" -> Sheet.A4.corners.mapIndexed { i, c -> if (i == 2) Mm(c.x + 8f, c.y) else c }
                "letter" -> Sheet.LETTER.corners
                else -> if (nums.size >= 8) (0 until 4).map { Mm(nums[it * 2], nums[it * 2 + 1]) } else Sheet.A4.corners
            }
        vm.debugDraft(PaperFit.draftFromMm(p, mm), activeCorner = 3)
        TraceLog.i("CMD corners ${s ?: "mm"} ${mm.joinToString(" ") { "(${it.x}, ${it.y})" }}")
    }

    private fun ui(vm: TraceViewModel, control: String) {
        val e: TraceEvent =
            when (control) {
                "ready" -> TraceEvent.ReadyClicked
                "confirm" -> TraceEvent.ConfirmClicked
                "cornerOk" -> TraceEvent.CornerOkClicked
                "restart" -> TraceEvent.RestartClicked
                "cancel" -> TraceEvent.CancelClicked
                "retryDesk" -> TraceEvent.RetryDeskClicked
                "record" -> TraceEvent.RecordClicked
                "figure" -> TraceEvent.TestFigureClicked
                "gain05" -> TraceEvent.GainClicked(0.5f)
                "gain10" -> TraceEvent.GainClicked(1.0f)
                "testTap" -> TraceEvent.TestTapClicked
                "heightUp" -> TraceEvent.HeightClicked(1)
                "heightDown" -> TraceEvent.HeightClicked(-1)
                "nextRound" -> TraceEvent.NextRoundClicked
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
        o.put("recheck", st.recheck)
        o.put("confirmed", st.confirmed)
        st.draft?.let { d -> o.put("draft", JSONArray(d.points.mapIndexed { i, p -> JSONObject().put("x", p.x.toDouble()).put("z", p.z.toDouble()).put("moved", d.moved[i]) })); o.put("draftY", d.y.toDouble()) }
        st.verdict?.let { v -> o.put("fit", JSONObject().put("maxMm", v.a4.maxMm.toDouble()).put("worst", v.a4.worst + 1).put("letter", v.looksLetter).put("residuals", JSONArray(v.a4.residualsMm.map { it.toDouble() }))) }
        st.aligned?.let { a -> o.put("aligned", JSONObject().put("x", a.center.x.toDouble()).put("y", a.center.y.toDouble()).put("z", a.center.z.toDouble()).put("yawDeg", Math.toDegrees(a.yaw.toDouble()))) }
        o.put("anchor", st.anchorId ?: "")
        o.put("calib", JSONObject().put("round", st.calib.round).put("gain", st.calib.gain.toDouble()).put("records", st.calib.records).put("figure", st.calib.testFigure).put("taps", st.calib.taps).put("heightMm", st.calib.heightMm))
        o.put("controller", st.controller)
        val text = o.toString()
        TraceLog.i("DUMP $text")
        scene.sheetLines().forEach { TraceLog.i(it) }
        runCatching { File(context.filesDir, "dump.json").writeText(text) }
    }

    /** Debug `cam` s=name: the last camera frame -> files/cam/<name>.png + <name>.json (intrinsics, head, stereo grid, desks). */
    private fun cam(context: Context, scene: TraceScene, name: String) {
        val cam = TraceGraph.camera
        val f = cam.latest ?: return no("no camera frame (status=${cam.status})")
        val dir = File(context.getExternalFilesDir(null), "cam").apply { mkdirs() }
        val bmp = android.graphics.Bitmap.createBitmap(f.width, f.height, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(f.width * f.height)
        for (i in px.indices) {
            val o = i * 4
            if (o + 3 >= f.rgba.size) break
            px[i] = (0xFF shl 24) or ((f.rgba[o].toInt() and 0xFF) shl 16) or ((f.rgba[o + 1].toInt() and 0xFF) shl 8) or (f.rgba[o + 2].toInt() and 0xFF)
        }
        bmp.setPixels(px, 0, f.width, 0, 0, f.width, f.height)
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val o = JSONObject()
        o.put("w", f.width).put("h", f.height).put("bytes", f.rgba.size)
        o.put("cam", JSONArray(f.camMatrix.map { it.toDouble() }))
        o.put("ts", JSONArray(f.timestamp.toList()))
        o.put("uv", JSONArray(f.uv.map { it.toDouble() }))
        o.put("points", JSONArray(f.points.map { it.toDouble() }))
        o.put("head", JSONArray(f.head.map { it.toDouble() }))
        o.put("headNow", scene.headPose()?.let { JSONArray(listOf(it.position.x, it.position.y, it.position.z, it.rotation.x, it.rotation.y, it.rotation.z, it.rotation.w).map { v -> v.toDouble() }) })
        o.put("planes", JSONArray(scene.planeLines()))
        File(dir, "$name.json").writeText(o.toString(1))
        TraceLog.i("CMD cam saved ${dir.absolutePath}/$name.png status=${cam.status} points=${f.points.joinToString(",") { String.format(Locale.US, "%.3f", it) }} cam=${f.camMatrix.joinToString(",") { String.format(Locale.US, "%.1f", it) }}")
    }

    private fun bounds() {
        for (panel in listOf("lesson", "calib")) {
            val size = Probe.sizeOf(panel)
            for ((name, b) in Probe.controlsOf(panel)) TraceLog.i(String.format(Locale.US, "BOUNDS panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f panel_px=%s", panel, name, b.cx, b.cy, b.w, b.h, size))
        }
    }

    @Suppress("unused")
    private fun p2(x: Float, z: Float) = P2(x, z)
}
