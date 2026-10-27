package io.embodit.tracelight.ui.trace.scene

import android.os.SystemClock
import io.embodit.tracelight.domain.CameraRig
import io.embodit.tracelight.domain.HeadPose
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperDetect
import io.embodit.tracelight.domain.PaperQuad
import io.embodit.tracelight.domain.PlaneSurface
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.SheetRect
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.domain.raycast
import io.embodit.tracelight.domain.sheetDistance
import io.embodit.tracelight.platform.TraceLog
import io.embodit.tracelight.platform.camera.PaperCamera
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Automatic alignment (design change 2026-10-23, user: "需自动识别对齐，然后任何大小的纸都可以"): every new
 * camera frame is searched for a sheet off the main thread ([PaperDetect]); its corners go through the
 * camera rig onto the desk plane under the sheet; a sheet that a few frames agree on (corners within
 * [STABLE_M]) is reported as stable. Debug builds keep sample frames for the rig calibration.
 */
internal class PaperFinder(
    private val camera: PaperCamera,
    private val scope: CoroutineScope,
    private val desks: () -> List<PlaneSurface>,
    private val onResult: (SheetRect?, Boolean) -> Unit,
) {
    var rig = CameraRig()
    var saveFramesTo: File? = null

    private var lastFrameAt = -1L
    private var job: Job? = null
    private val recent = ArrayDeque<SheetRect>()
    private var misses = 0
    private var lastReported: Pair<SheetRect?, Boolean>? = null
    private var saved = 0
    private var lastSaveMs = 0L
    private var lastLogMs = 0L

    /** Main thread, once per rendered frame while the sheet is being looked for. */
    fun step(active: Boolean, now: Long) {
        if (!active) {
            if (recent.isNotEmpty() || lastReported?.first != null) reset()
            return
        }
        val f = camera.latest ?: return
        if (f.readAtMs == lastFrameAt || job?.isActive == true) return
        lastFrameAt = f.readAtMs
        val head = f.head.takeIf { it.size == 7 }?.let { HeadPose(Vec3(it[0], it[1], it[2]), Quat4(it[3], it[4], it[5], it[6])) } ?: return
        val planes = desks()
        job =
            scope.launch {
                val quad = withContext(Dispatchers.Default) { PaperDetect.find(f.rgba, f.width, f.height) }
                val rect = quad?.let { project(it, f.camMatrix, head, planes, f.width, f.height) }
                accept(rect, quad, now)
                maybeSave(f, quad, rect, head, now)
            }
    }

    private fun project(q: PaperQuad, k: FloatArray, head: HeadPose, planes: List<PlaneSurface>, w: Int, h: Int): SheetRect? {
        // the desk under the sheet: where the centre pixel's ray meets a desk plane
        val (o, d) = rig.ray(q.center, k, head, w, h) ?: return null
        val desk = raycast(planes, o, d, 3f) ?: return null
        val y = desk.plane.centroid.y
        val pts = q.corners.map { rig.onDesk(it, k, head, y, w, h) ?: return null }
        return SheetRect.fit(pts.map { P2.of(it) }, y, P2.of(head.position))
    }

    private fun accept(rect: SheetRect?, quad: PaperQuad?, now: Long) {
        if (rect == null || rect.widthM < MIN_SIDE_M || rect.heightM < MIN_SIDE_M || rect.widthM > MAX_SIDE_M || rect.heightM > MAX_SIDE_M) {
            if (++misses >= LOST_AFTER) {
                recent.clear()
                report(null, false)
            }
            return
        }
        misses = 0
        recent.addLast(rect)
        while (recent.size > STABLE_FRAMES) recent.removeFirst()
        val stable = recent.size >= STABLE_FRAMES && recent.all { sheetDistance(it, rect) < STABLE_M }
        // the outline shown is the mean of the agreeing frames (less jitter)
        val shown = if (stable) average(recent.toList()) else rect
        report(shown, stable)
        if (now - lastLogMs > LOG_MS || stable != lastReported?.second) {
            lastLogMs = now
            TraceLog.i(String.format(Locale.US, "DETECT size=%.0fx%.0f mm center=%s yaw=%.1f stable=%s fill=%.2f px=%s", rect.widthM * 1000f, rect.heightM * 1000f, TraceLog.pos(rect.pose.center), Math.toDegrees(rect.pose.yaw.toDouble()), stable, quad?.fill ?: 0f, quad?.corners?.joinToString(" ") { String.format(Locale.US, "(%.1f,%.1f)", it.x, it.y) }))
        }
    }

    private fun average(list: List<SheetRect>): SheetRect {
        val n = list.size.toFloat()
        val cx = list.sumOf { it.pose.center.x.toDouble() }.toFloat() / n
        val cy = list.sumOf { it.pose.center.y.toDouble() }.toFloat() / n
        val cz = list.sumOf { it.pose.center.z.toDouble() }.toFloat() / n
        // headings near each other: average of unit vectors
        val sx = list.sumOf { kotlin.math.sin(it.pose.yaw.toDouble()) }
        val cz2 = list.sumOf { kotlin.math.cos(it.pose.yaw.toDouble()) }
        val yaw = kotlin.math.atan2(sx, cz2).toFloat()
        return SheetRect(io.embodit.tracelight.domain.PaperPose(Vec3(cx, cy, cz), yaw), list.sumOf { it.widthM.toDouble() }.toFloat() / n, list.sumOf { it.heightM.toDouble() }.toFloat() / n)
    }

    private fun report(rect: SheetRect?, stable: Boolean) {
        val r = rect to stable
        if (r == lastReported) return
        lastReported = r
        onResult(rect, stable)
    }

    fun reset() {
        recent.clear()
        misses = 0
        lastReported = null
        onResult(null, false)
    }

    private fun maybeSave(f: PaperCamera.Frame, quad: PaperQuad?, rect: SheetRect?, head: HeadPose, now: Long) {
        val dir = saveFramesTo ?: return
        if (saved >= MAX_SAVED || now - lastSaveMs < SAVE_EVERY_MS) return
        lastSaveMs = now
        val n = saved++
        scope.launch(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val bmp = android.graphics.Bitmap.createBitmap(f.width, f.height, android.graphics.Bitmap.Config.ARGB_8888)
                val px = IntArray(f.width * f.height)
                for (i in px.indices) {
                    val o = i * 4
                    if (o + 3 >= f.rgba.size) break
                    px[i] = (0xFF shl 24) or ((f.rgba[o].toInt() and 0xFF) shl 16) or ((f.rgba[o + 1].toInt() and 0xFF) shl 8) or (f.rgba[o + 2].toInt() and 0xFF)
                }
                bmp.setPixels(px, 0, f.width, 0, 0, f.width, f.height)
                val name = String.format(Locale.US, "auto_%03d", n)
                File(dir, "$name.jpg").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                val o = org.json.JSONObject()
                o.put("bytes", f.rgba.size)
                if (n < RAW_FRAMES) File(dir, "$name.bin").writeBytes(f.rgba)
                o.put("cam", org.json.JSONArray(f.camMatrix.map { it.toDouble() }))
                o.put("ts", org.json.JSONArray(f.timestamp.toList()))
                o.put("head", org.json.JSONArray(f.head.map { it.toDouble() }))
                o.put("quad", quad?.let { q -> org.json.JSONArray(q.corners.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }) })
                o.put("fill", quad?.fill?.toDouble())
                o.put("sheet", rect?.let { r -> org.json.JSONObject().put("w", r.widthM.toDouble()).put("h", r.heightM.toDouble()).put("cx", r.pose.center.x.toDouble()).put("cy", r.pose.center.y.toDouble()).put("cz", r.pose.center.z.toDouble()).put("yaw", r.pose.yaw.toDouble()) })
                o.put("desks", org.json.JSONArray(desks().map { d -> org.json.JSONObject().put("label", d.label).put("y", d.centroid.y.toDouble()).put("area", d.area.toDouble()) }))
                o.put("at", SystemClock.elapsedRealtime())
                File(dir, "$name.json").writeText(o.toString())
                TraceLog.i("CAM saved $name quad=${quad != null}")
            }.onFailure { TraceLog.w("CAM save failed: $it") }
        }
    }

    companion object {
        const val STABLE_FRAMES = 3
        const val STABLE_M = 0.008f
        const val LOST_AFTER = 4
        const val MIN_SIDE_M = 0.06f
        const val MAX_SIDE_M = 0.7f
        const val LOG_MS = 2_000L
        const val MAX_SAVED = 80
        const val RAW_FRAMES = 3
        const val SAVE_EVERY_MS = 2_500L
    }
}
