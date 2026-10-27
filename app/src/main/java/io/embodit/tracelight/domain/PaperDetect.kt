package io.embodit.tracelight.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** An image point in pixels (x right, y down). */
data class Px(val x: Float, val y: Float) {
    operator fun minus(o: Px) = Px(x - o.x, y - o.y)

    operator fun plus(o: Px) = Px(x + o.x, y + o.y)

    operator fun times(s: Float) = Px(x * s, y * s)

    fun cross(o: Px): Float = x * o.y - y * o.x

    fun dist(o: Px): Float = hypot(x - o.x, y - o.y)
}

/** A sheet found in one camera image: its four corners (pixels, in hull order) and how sure we are. */
data class PaperQuad(val corners: List<Px>, val areaPx: Float, val fill: Float, val threshold: Int) {
    val center: Px
        get() = Px(corners.sumOf { it.x.toDouble() }.toFloat() / 4f, corners.sumOf { it.y.toDouble() }.toFloat() / 4f)
}

/**
 * Finds a sheet of paper in a passthrough frame (no model): the paper is the largest bright, unsaturated
 * region that does not touch the image border; its outline is reduced to the quadrilateral of largest
 * area on its convex hull, then each side is re-fitted to its boundary pixels by least squares and the
 * corners are the intersections of neighbouring sides (sub-pixel). Pure Kotlin: unit-tested on synthetic
 * images; runs on a downsampled grey image (about 3 ms for 256 x 256 on the device).
 */
object PaperDetect {
    const val STEP = 2
    const val MIN_AREA_FRACTION = 0.01f
    const val MAX_AREA_FRACTION = 0.8f
    const val MIN_FILL = 0.85f
    const val MAX_SATURATION = 70

    /**
     * [rgba] is width x height x 4 bytes. Returns null when no sheet is clearly visible (none, cut by
     * the image border, or not quadrilateral enough).
     */
    fun find(rgba: ByteArray, width: Int, height: Int): PaperQuad? {
        val w = width / STEP
        val h = height / STEP
        if (w < 16 || h < 16 || rgba.size < width * height * 4) return null
        val grey = IntArray(w * h)
        val pale = BooleanArray(w * h)
        val hist = IntArray(256)
        for (y in 0 until h) for (x in 0 until w) {
            val o = ((y * STEP) * width + x * STEP) * 4
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            val v = (r * 77 + g * 150 + b * 29) shr 8
            grey[y * w + x] = v
            pale[y * w + x] = max(r, max(g, b)) - min(r, min(g, b)) <= MAX_SATURATION
            hist[v]++
        }
        val t = otsu(hist, w * h)
        val mask = BooleanArray(w * h) { grey[it] > t && pale[it] }
        val label = IntArray(w * h)
        var best: Region? = null
        var next = 1
        val queue = IntArray(w * h)
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            val region = flood(mask, label, w, h, start, next++, queue)
            if (region.touchesBorder) continue
            val frac = region.area.toFloat() / (w * h)
            if (frac < MIN_AREA_FRACTION || frac > MAX_AREA_FRACTION) continue
            if (best == null || region.area > best.area) best = region
        }
        val r = best ?: return null
        val hull = convexHull(r.boundary)
        if (hull.size < 4) return null
        val quad = maxAreaQuad(hull) ?: return null
        val quadArea = abs(polyArea(quad))
        if (quadArea < 1f) return null
        val fill = r.area / quadArea
        if (fill < MIN_FILL || fill > 1.15f) return null
        val refined = refine(quad, r.boundary) ?: quad
        val scaled = refined.map { Px((it.x + 0.5f) * STEP, (it.y + 0.5f) * STEP) }
        return PaperQuad(scaled, quadArea * STEP * STEP, fill, t)
    }

    private class Region(val area: Int, val boundary: List<Px>, val touchesBorder: Boolean)

    private fun flood(mask: BooleanArray, label: IntArray, w: Int, h: Int, start: Int, id: Int, queue: IntArray): Region {
        var head = 0
        var tail = 0
        queue[tail++] = start
        label[start] = id
        var area = 0
        var border = false
        val boundary = ArrayList<Px>()
        while (head < tail) {
            val i = queue[head++]
            area++
            val x = i % w
            val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
            var edge = false
            for (k in 0 until 4) {
                val nx = x + DX[k]
                val ny = y + DY[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    edge = true
                    continue
                }
                val j = ny * w + nx
                if (!mask[j]) {
                    edge = true
                    continue
                }
                if (label[j] == 0) {
                    label[j] = id
                    queue[tail++] = j
                }
            }
            if (edge) boundary += Px(x.toFloat(), y.toFloat())
        }
        return Region(area, boundary, border)
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    /** Otsu's threshold of a grey histogram. */
    fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var t = 128
        for (i in 0 until 256) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                t = i
            }
        }
        return t
    }

    /** Andrew's monotone chain; counter-clockwise in image coordinates (y down). */
    fun convexHull(points: List<Px>): List<Px> {
        val p = points.distinct().sortedWith(compareBy<Px> { it.x }.thenBy { it.y })
        if (p.size < 3) return p
        val lower = ArrayList<Px>()
        for (q in p) {
            while (lower.size >= 2 && (lower[lower.size - 1] - lower[lower.size - 2]).cross(q - lower[lower.size - 2]) <= 0f) lower.removeAt(lower.size - 1)
            lower += q
        }
        val upper = ArrayList<Px>()
        for (q in p.asReversed()) {
            while (upper.size >= 2 && (upper[upper.size - 1] - upper[upper.size - 2]).cross(q - upper[upper.size - 2]) <= 0f) upper.removeAt(upper.size - 1)
            upper += q
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    fun polyArea(p: List<Px>): Float {
        var a = 0f
        for (i in p.indices) a += p[i].cross(p[(i + 1) % p.size])
        return a / 2f
    }

    /** The quadrilateral of largest area with corners on the hull (start from the four diagonal extremes, then local moves). */
    fun maxAreaQuad(hull: List<Px>): List<Px>? {
        val n = hull.size
        if (n < 4) return null
        // the hull can have hundreds of points: thin it to <= 64 first
        val stride = max(1, n / 64)
        val h = hull.filterIndexed { i, _ -> i % stride == 0 }
        val m = h.size
        if (m < 4) return null
        fun area(ix: IntArray): Float = abs(polyArea(ix.sorted().map { h[it] }))
        val start =
            intArrayOf(
                h.indices.maxByOrNull { h[it].x + h[it].y }!!,
                h.indices.maxByOrNull { h[it].x - h[it].y }!!,
                h.indices.minByOrNull { h[it].x + h[it].y }!!,
                h.indices.maxByOrNull { -h[it].x + h[it].y }!!,
            )
        if (start.toSet().size < 4) return null
        var ix = start
        var best = area(ix)
        var improved = true
        var rounds = 0
        while (improved && rounds < 200) {
            improved = false
            rounds++
            for (k in 0 until 4) for (d in intArrayOf(-3, -2, -1, 1, 2, 3)) {
                val c = ix.copyOf()
                c[k] = ((c[k] + d) % m + m) % m
                if (c.toSet().size < 4) continue
                val a = area(c)
                if (a > best + 1e-3f) {
                    best = a
                    ix = c
                    improved = true
                }
            }
        }
        return ix.sorted().map { h[it] }
    }

    /**
     * Re-fits each side to the boundary pixels near it (between the inner 70 % of its corners) and
     * intersects neighbouring sides. Null when a side has too few pixels.
     */
    fun refine(quad: List<Px>, boundary: List<Px>): List<Px>? {
        val lines = ArrayList<Line>()
        for (k in 0 until 4) {
            val a = quad[k]
            val b = quad[(k + 1) % 4]
            val len = a.dist(b)
            if (len < 4f) return null
            val dir = (b - a) * (1f / len)
            val pts = boundary.filter { p ->
                val t = (p - a).x * dir.x + (p - a).y * dir.y
                val d = abs((p - a).cross(dir))
                t > len * 0.15f && t < len * 0.85f && d < 2.5f
            }
            if (pts.size < 6) return null
            lines += fitLine(pts) ?: return null
        }
        val out = ArrayList<Px>()
        for (k in 0 until 4) out += intersect(lines[(k + 3) % 4], lines[k]) ?: return null
        // the refined corners must stay near the hull ones
        for (k in 0 until 4) if (out[k].dist(quad[k]) > 6f) return null
        return out
    }

    /** A line through [p] along unit [d]. */
    data class Line(val p: Px, val d: Px)

    fun fitLine(pts: List<Px>): Line? {
        val n = pts.size
        if (n < 2) return null
        var mx = 0.0
        var my = 0.0
        for (p in pts) {
            mx += p.x
            my += p.y
        }
        mx /= n
        my /= n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in pts) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(Px(mx.toFloat(), my.toFloat()), Px(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat()))
    }

    fun intersect(a: Line, b: Line): Px? {
        val den = a.d.cross(b.d)
        if (abs(den) < 1e-6f) return null
        val t = (b.p - a.p).cross(b.d) / den
        return a.p + a.d * t
    }
}

/**
 * From image to desk (design change 2026-10-23, user: "需自动识别对齐"): a pixel becomes a ray of the
 * rectified camera (intrinsics K, OpenCV axes: x right, y down, z ahead), the camera is placed on the
 * head by [CameraRig] (an extrinsic measured on the device), and the ray meets the desk plane.
 */
data class CameraRig(
    /** Camera origin in the head frame (x right, y up, -z ahead), metres. */
    val offset: Vec3 = Vec3(0.032f, 0f, -0.06f),
    /** Small extra rotation of the camera relative to the head: pitch (down +), yaw (left +), radians. */
    val pitch: Float = 0f,
    val yaw: Float = 0f,
) {
    /** The world ray through image pixel [p] with the head at [head]; null when K is not usable. */
    fun ray(p: Px, k: FloatArray, head: HeadPose, width: Int = 512, height: Int = 512): Pair<Vec3, Vec3>? {
        if (k.size < 9 || k[0] <= 0f || k[4] <= 0f) return null
        // K comes back normalised on the emulator ([0.5, 0, 1; 0, 0.5, 1]: the principal point at 1 = the image
        // centre, so the unit is half the image size); pixel units otherwise
        val sx = if (k[2] < 10f) width / 2f else 1f
        val sy = if (k[5] < 10f) height / 2f else 1f
        val fx = k[0] * sx
        val fy = k[4] * sy
        val cx = k[2] * sx
        val cy = k[5] * sy
        // camera (OpenCV) -> head (x right, y up, -z ahead)
        var d = Vec3((p.x - cx) / fx, -(p.y - cy) / fy, -1f)
        if (pitch != 0f) d = Quat4.axisAngle(Vec3.RIGHT, -pitch).rotate(d)
        if (yaw != 0f) d = Quat4.axisAngle(Vec3.UP, yaw).rotate(d)
        val dir = head.rotation.rotate(d).normalized()
        val origin = head.position + head.rotation.rotate(offset)
        return origin to dir
    }

    /** Where pixel [p] meets the horizontal plane at height [deskY]. */
    fun onDesk(p: Px, k: FloatArray, head: HeadPose, deskY: Float, width: Int = 512, height: Int = 512): Vec3? {
        val (o, d) = ray(p, k, head, width, height) ?: return null
        if (abs(d.y) < 1e-4f) return null
        val t = (deskY - o.y) / d.y
        if (t <= 0f || t > 3f) return null
        return o + d * t
    }
}

/** A sheet of any size on the desk: centre, heading of its long side's "away" direction, width and height (metres). */
data class SheetRect(val pose: PaperPose, val widthM: Float, val heightM: Float) {
    val corners: List<P2>
        get() {
            val w = widthM * 500f
            val h = heightM * 500f
            return listOf(Mm(-w, -h), Mm(w, -h), Mm(w, h), Mm(-w, h)).map { P2.of(pose.toWorld(it)) }
        }

    companion object {
        /**
         * The rectangle that fits four desk points (any order): sides from the averaged opposite edges, the
         * side pointing most away from [viewer] becomes the sheet's "up". Corners come back as ① near left,
         * ② near right, ③ far right, ④ far left.
         */
        fun fit(points: List<P2>, y: Float, viewer: P2): SheetRect? {
            if (points.size != 4) return null
            // order around the centroid
            val c = P2(points.sumOf { it.x.toDouble() }.toFloat() / 4f, points.sumOf { it.z.toDouble() }.toFloat() / 4f)
            val ordered = points.sortedBy { atan2((it.z - c.z).toDouble(), (it.x - c.x).toDouble()) }
            val e0 = ordered[1] - ordered[0]
            val e1 = ordered[2] - ordered[1]
            val e2 = ordered[3] - ordered[2]
            val e3 = ordered[0] - ordered[3]
            // two side directions, opposite edges averaged (flip the second of each pair)
            val a = (e0 + e2 * -1f) * 0.5f
            val b = (e1 + e3 * -1f) * 0.5f
            val la = a.length
            val lb = b.length
            if (la < 0.02f || lb < 0.02f) return null
            val toward = (c - viewer).normalized()
            // "away" = whichever side direction is closer to the line of sight
            val (awayDir, awayLen, sideLen) = if (abs(a.normalized().dot(toward)) >= abs(b.normalized().dot(toward))) Triple(a.normalized(), la, lb) else Triple(b.normalized(), lb, la)
            val away = if (awayDir.dot(toward) >= 0f) awayDir else awayDir * -1f
            return SheetRect(PaperPose(c.at(y), yawOf(away)), sideLen, awayLen)
        }
    }
}

/** Mean corner distance (metres) between two sheets; their order must match (both from [SheetRect.corners]). */
fun sheetDistance(a: SheetRect, b: SheetRect): Float {
    var s = 0f
    val ca = a.corners
    val cb = b.corners
    for (i in 0 until 4) s += (ca[i] - cb[i]).length
    return s / 4f
}
