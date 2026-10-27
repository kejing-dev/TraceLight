package io.embodit.tracelight.domain

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperDetectTest {
    private val w = 512
    private val h = 512

    /** A dark desk with a white quadrilateral (corners in pixels) and some grey noise. */
    private fun image(corners: List<Px>, deskGrey: Int = 70): ByteArray {
        val out = ByteArray(w * h * 4)
        val rnd = java.util.Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            val inside = inside(Px(x + 0.5f, y + 0.5f), corners)
            val v = if (inside) 225 + rnd.nextInt(20) else deskGrey + rnd.nextInt(30)
            val o = (y * w + x) * 4
            out[o] = v.toByte()
            out[o + 1] = (v - 4).toByte()
            out[o + 2] = (v - 10).toByte()
            out[o + 3] = 0xFF.toByte()
        }
        return out
    }

    private fun inside(p: Px, poly: List<Px>): Boolean {
        var s = 0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val c = (b - a).cross(p - a)
            if (c > 0) s++ else if (c < 0) s--
        }
        return kotlin.math.abs(s) == poly.size
    }

    private fun quad(cx: Float, cy: Float, hw: Float, hh: Float, rot: Float, skew: Float = 0f): List<Px> =
        listOf(Px(-hw, -hh + skew), Px(hw, -hh - skew), Px(hw, hh), Px(-hw, hh)).map { p ->
            Px(cx + p.x * cos(rot) - p.y * sin(rot), cy + p.x * sin(rot) + p.y * cos(rot))
        }

    private fun assertCorners(expected: List<Px>, found: PaperQuad, tol: Float) {
        for (e in expected) {
            val d = found.corners.minOf { it.dist(e) }
            assertTrue("corner $e off by $d (found ${found.corners})", d <= tol)
        }
    }

    @Test
    fun findsATiltedSheet() {
        val q = quad(250f, 270f, 90f, 130f, 0.3f, skew = 12f)
        val found = PaperDetect.find(image(q), w, h)
        assertNotNull(found)
        assertCorners(q, found!!, 2.5f)
        assertTrue(found.fill > 0.9f)
    }

    @Test
    fun ignoresASheetCutByTheBorder() {
        val q = quad(30f, 270f, 90f, 130f, 0.1f)
        assertNull(PaperDetect.find(image(q), w, h))
    }

    @Test
    fun nothingOnAnEmptyDesk() {
        assertNull(PaperDetect.find(image(emptyList<Px>().ifEmpty { listOf(Px(-10f, -10f), Px(-9f, -10f), Px(-9f, -9f), Px(-10f, -9f)) }), w, h))
    }

    @Test
    fun rectFitAnySizeAndOrder() {
        val pose = PaperPose(Vec3(0.1f, 0.8f, -0.5f), 3.0f)
        val truth = SheetRect(pose, 0.18f, 0.25f)
        val shuffled = truth.corners.let { listOf(it[2], it[0], it[3], it[1]) }
        val fit = SheetRect.fit(shuffled, 0.8f, P2(0.1f, 0f))!!
        assertEquals(0.18f, fit.widthM, 1e-3f)
        assertEquals(0.25f, fit.heightM, 1e-3f)
        assertEquals(0f, sheetDistance(fit, truth), 1e-3f)
    }

    @Test
    fun rayFromImageCentreGoesStraightAhead() {
        val k = floatArrayOf(300f, 0f, 256f, 0f, 300f, 256f, 0f, 0f, 1f)
        val head = HeadPose(Vec3(0f, 1.2f, 0f), Quat4.axisAngle(Vec3.RIGHT, -0.6f))
        val rig = CameraRig(offset = Vec3.ZERO)
        val (_, d) = rig.ray(Px(256f, 256f), k, head)!!
        val fwd = head.forward
        assertEquals(fwd.x, d.x, 1e-4f)
        assertEquals(fwd.y, d.y, 1e-4f)
        assertEquals(fwd.z, d.z, 1e-4f)
        // a pixel below the centre looks further down
        val (_, below) = rig.ray(Px(256f, 400f), k, head)!!
        assertTrue(below.y < d.y)
    }
}
