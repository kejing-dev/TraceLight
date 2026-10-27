package io.embodit.tracelight.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pose of the head in Stage metres. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    /** Unit vector of the view direction projected on the floor; [fallback] when looking straight down. */
    fun level(fallback: P2 = P2(0f, -1f)): P2 {
        val f = forward
        val l = hypot(f.x, f.z)
        return if (l < 0.05f) fallback else P2(f.x / l, f.z / l)
    }
}

/** How a room panel is turned: yaw about the vertical and pitch towards the viewer, radians; no roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector out of the panel's face, towards the viewer. */
    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))

    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    val up: Vec3
        get() = normal.cross(right)
}

/**
 * Facing rule for panels that stand in the room (Spatial Ruler): turn about the vertical towards the
 * head, then pitch towards it (clamped to 60 degrees), never roll. Straight above or below, the last
 * yaw is kept.
 */
object FacePanel {
    private const val MAX_PITCH_RAD = 1.0471976f
    private const val KEEP_YAW_WITHIN_M = 0.1f

    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?): PanelFacing {
        val to = head - panel
        val flat = hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else atan2(to.x, to.z)
        val pitch = atan2(to.y, flat).coerceIn(-MAX_PITCH_RAD, MAX_PITCH_RAD)
        return PanelFacing(yaw, pitch)
    }
}

/** A point or direction on the floor plane (Stage x, z). */
data class P2(val x: Float, val z: Float) {
    operator fun plus(o: P2) = P2(x + o.x, z + o.z)

    operator fun minus(o: P2) = P2(x - o.x, z - o.z)

    operator fun times(s: Float) = P2(x * s, z * s)

    fun dot(o: P2): Float = x * o.x + z * o.z

    val length: Float
        get() = hypot(x, z)

    fun normalized(): P2 {
        val l = length
        return if (l < 1e-9f) this else P2(x / l, z / l)
    }

    fun at(y: Float): Vec3 = Vec3(x, y, z)

    fun isFinite(): Boolean = x.isFinite() && z.isFinite()

    companion object {
        val ZERO = P2(0f, 0f)

        fun of(v: Vec3) = P2(v.x, v.z)

        /** Unit direction of a yaw: the +Z axis turned by [yaw] about the vertical (as [PanelFacing]). */
        fun dir(yaw: Float) = P2(sin(yaw), cos(yaw))
    }
}

/** Yaw of a horizontal direction, in the convention of [P2.dir]. */
fun yawOf(d: P2): Float = atan2(d.x, d.z)

/** Wraps an angle into (-PI, PI]. */
fun wrapAngle(a: Float): Float {
    var v = a
    val twoPi = (2 * Math.PI).toFloat()
    while (v > Math.PI) v -= twoPi
    while (v <= -Math.PI) v += twoPi
    return v
}

/**
 * An upright box: an oriented rectangle on the floor ([center], half extents [hx] along its local x
 * axis and [hz] along its local z axis, turned by [yaw]) between heights [y0] and [y1] (Stage metres).
 * Local +z is the front of a piece of furniture.
 */
data class Prism(val center: P2, val hx: Float, val hz: Float, val yaw: Float, val y0: Float, val y1: Float) {
    /** Local x axis on the floor (the piece's right). */
    val ax: P2
        get() = P2(cos(yaw), -sin(yaw))

    /** Local z axis on the floor (the front). */
    val az: P2
        get() = P2(sin(yaw), cos(yaw))

    val corners: List<P2>
        get() {
            val x = ax * hx
            val z = az * hz
            return listOf(center + x + z, center - x + z, center - x - z, center + x - z)
        }

    fun contains(p: P2, margin: Float = 0f): Boolean {
        val d = p - center
        return abs(d.dot(ax)) <= hx + margin && abs(d.dot(az)) <= hz + margin
    }

    /** Height range shared with [o] is more than [minOverlap]. */
    fun overlapsHeight(o: Prism, minOverlap: Float = 0f): Boolean = min(y1, o.y1) - max(y0, o.y0) > minOverlap

    fun inflated(m: Float) = copy(hx = hx + m, hz = hz + m)

    /** One side of the rectangle: its midpoint, outward normal, direction along it and half length. */
    fun side(side: Side): SideLine =
        when (side) {
            Side.FRONT -> SideLine(side, center + az * hz, az, ax, hx)
            Side.BACK -> SideLine(side, center - az * hz, az * -1f, ax, hx)
            Side.RIGHT -> SideLine(side, center + ax * hx, ax, az, hz)
            Side.LEFT -> SideLine(side, center - ax * hx, ax * -1f, az, hz)
        }

    val sides: List<SideLine>
        get() = Side.entries.map { side(it) }
}

enum class Side { FRONT, RIGHT, BACK, LEFT }

/** One side of a [Prism] seen from above: from `mid - along * half` to `mid + along * half`, facing [normal]. */
data class SideLine(val side: Side, val mid: P2, val normal: P2, val along: P2, val half: Float)

object Geo2 {
    /**
     * Penetration depth of two rectangles on the floor (separating axis test): the smallest overlap
     * over the four axes, or 0 when they are apart.
     */
    fun penetration(a: Prism, b: Prism): Float {
        var best = Float.MAX_VALUE
        for (axis in listOf(a.ax, a.az, b.ax, b.az)) {
            val (a0, a1) = project(a, axis)
            val (b0, b1) = project(b, axis)
            val o = min(a1, b1) - max(a0, b0)
            if (o <= 0f) return 0f
            if (o < best) best = o
        }
        return best
    }

    /** Extent of [p] along [axis] (unit). */
    fun project(p: Prism, axis: P2): Pair<Float, Float> {
        val c = p.center.dot(axis)
        val r = abs(p.ax.dot(axis)) * p.hx + abs(p.az.dot(axis)) * p.hz
        return (c - r) to (c + r)
    }

    /**
     * Distance along a ray (origin [o], unit direction [d]) to the rectangle of [p], or null when it is
     * missed or behind. A ray that starts inside answers 0.
     */
    fun ray(o: P2, d: P2, p: Prism): Float? {
        val rel = o - p.center
        val ox = rel.dot(p.ax)
        val oz = rel.dot(p.az)
        val dx = d.dot(p.ax)
        val dz = d.dot(p.az)
        var t0 = -Float.MAX_VALUE
        var t1 = Float.MAX_VALUE
        val slabs = arrayOf(floatArrayOf(ox, dx, p.hx), floatArrayOf(oz, dz, p.hz))
        for (s in slabs) {
            val oo = s[0]
            val dd = s[1]
            val h = s[2]
            if (abs(dd) < 1e-9f) {
                if (oo < -h || oo > h) return null
            } else {
                var ta = (-h - oo) / dd
                var tb = (h - oo) / dd
                if (ta > tb) {
                    val t = ta
                    ta = tb
                    tb = t
                }
                t0 = max(t0, ta)
                t1 = min(t1, tb)
                if (t0 > t1) return null
            }
        }
        if (t1 < 0f) return null
        return max(t0, 0f)
    }

    /** Point in polygon (even-odd) on the floor plane. */
    fun inside(p: P2, poly: List<P2>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.z > p.z) != (b.z > p.z) && p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x) c = !c
            j = i
        }
        return c
    }

    /** Distance from [p] to the boundary of [poly]. */
    fun distanceToEdge(p: P2, poly: List<P2>): Float {
        var best = Float.MAX_VALUE
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val ab = b - a
            val l2 = ab.dot(ab)
            val t = if (l2 < 1e-12f) 0f else ((p - a).dot(ab) / l2).coerceIn(0f, 1f)
            val q = a + ab * t
            best = min(best, (p - q).length)
        }
        return best
    }

    /** Smallest rectangle (any yaw) around [points], tried every 2 degrees over a quarter turn. */
    fun minRect(points: List<P2>): MinRect? {
        if (points.isEmpty()) return null
        var best: MinRect? = null
        var bestArea = Float.MAX_VALUE
        for (step in 0 until 45) {
            val yaw = (step * 2.0 * Math.PI / 180.0).toFloat()
            val ax = P2(cos(yaw), -sin(yaw))
            val az = P2(sin(yaw), cos(yaw))
            var x0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var z0 = Float.MAX_VALUE
            var z1 = -Float.MAX_VALUE
            for (p in points) {
                val u = p.dot(ax)
                val v = p.dot(az)
                x0 = min(x0, u)
                x1 = max(x1, u)
                z0 = min(z0, v)
                z1 = max(z1, v)
            }
            val area = (x1 - x0) * (z1 - z0)
            if (area < bestArea) {
                bestArea = area
                best = MinRect(ax * ((x0 + x1) / 2f) + az * ((z0 + z1) / 2f), (x1 - x0) / 2f, (z1 - z0) / 2f, yaw)
            }
        }
        return best
    }
}

data class MinRect(val center: P2, val hx: Float, val hz: Float, val yaw: Float)
