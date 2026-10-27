package io.embodit.tracelight.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point on the paper in millimetres: origin at the centre, x to the right, y away from the user (design `spatialLayout.paperFrame.coords`). */
data class Mm(val x: Float, val y: Float)

/**
 * Where a sheet lies on the desk: [center] in Stage metres (on the desk plane), [yaw] = heading of the
 * paper's "away" direction (its top edge), as [yawOf]. Paper x runs along [right], paper y along [away].
 */
data class PaperPose(val center: Vec3, val yaw: Float) {
    val away: P2
        get() = P2(sin(yaw), cos(yaw))

    val right: P2
        get() = P2(-cos(yaw), sin(yaw))

    fun toWorld(p: Mm, lift: Float = 0f): Vec3 {
        val a = away
        val r = right
        return Vec3(center.x + r.x * p.x / 1000f + a.x * p.y / 1000f, center.y + lift, center.z + r.z * p.x / 1000f + a.z * p.y / 1000f)
    }

    fun toPaper(w: P2): Mm {
        val d = w - P2(center.x, center.z)
        return Mm(d.dot(right) * 1000f, d.dot(away) * 1000f)
    }

    /** Turned by [dYaw] about its own centre (thumbstick). */
    fun turned(dYaw: Float) = copy(yaw = wrapAngle(yaw + dYaw))
}

/** Sheet sizes in millimetres (portrait). */
enum class Sheet(val w: Float, val h: Float) {
    A4(210f, 297f),
    LETTER(215.9f, 279.4f);

    /** ① lower left, ② lower right, ③ upper right, ④ upper left ("lower" = nearer the user). */
    val corners: List<Mm>
        get() = listOf(Mm(-w / 2, -h / 2), Mm(w / 2, -h / 2), Mm(w / 2, h / 2), Mm(-w / 2, h / 2))
}

/** The four dragged corner points (Stage, on the desk plane) and which of them were touched. */
data class CornerDraft(val points: List<P2>, val y: Float, val moved: List<Boolean>) {
    init {
        require(points.size == 4 && moved.size == 4)
    }

    val allMoved: Boolean
        get() = moved.all { it }

    fun with(i: Int, p: P2) = copy(points = points.toMutableList().also { it[i] = p }, moved = moved.toMutableList().also { it[i] = true })

    /** Every point turned by [dYaw] about the four points' centroid (keeps which were touched). */
    fun turned(dYaw: Float): CornerDraft {
        val c = centroid
        val cs = cos(dYaw)
        val sn = sin(dYaw)
        // a yaw turn in (x, z): heading atan2(x, z) grows by dYaw
        return copy(points = points.map { p -> val d = p - c; P2(c.x + d.x * cs + d.z * sn, c.z - d.x * sn + d.z * cs) })
    }

    val centroid: P2
        get() = P2(points.sumOf { it.x.toDouble() }.toFloat() / 4f, points.sumOf { it.z.toDouble() }.toFloat() / 4f)

    companion object {
        fun of(pose: PaperPose, sheet: Sheet = Sheet.A4): CornerDraft =
            CornerDraft(sheet.corners.map { P2.of(pose.toWorld(it)) }, pose.center.y, List(4) { false })

        /** ① near left, ② near right, ③ far right, ④ far left of a sheet of any size. */
        fun of(rect: SheetRect): CornerDraft = CornerDraft(rect.corners, rect.pose.center.y, List(4) { false })
    }
}

/** A rigid fit of a sheet to four points: its pose and each corner's residual (mm). */
data class SheetFit(val sheet: Sheet, val pose: PaperPose, val residualsMm: List<Float>) {
    val worst: Int
        get() = residualsMm.indices.maxByOrNull { residualsMm[it] } ?: 0

    val maxMm: Float
        get() = residualsMm.maxOrNull() ?: 0f
}

data class FitVerdict(val a4: SheetFit, val letter: SheetFit) {
    /** Within tolerance (design `corners.pass`, threshold to be refilled after the spike). */
    val pass: Boolean
        get() = a4.maxMm <= PaperFit.PASS_MM

    /** The residual pattern looks like US Letter (S05 copy). */
    val looksLetter: Boolean
        get() = !pass && letter.maxMm <= PaperFit.PASS_MM && letter.maxMm < a4.maxMm * 0.5f
}

/**
 * 2D Procrustes (translation + rotation, no scale) of a sheet's corners to the dragged points
 * (design `spatialLayout.corners.fit`). The template's centroid is the sheet centre, so the
 * translation is the points' centroid; the heading has a closed form.
 */
object PaperFit {
    const val PASS_MM = 4f

    fun fit(draft: CornerDraft, sheet: Sheet): SheetFit {
        val c = draft.centroid
        val t = sheet.corners
        var a = 0.0
        var b = 0.0
        for (i in 0 until 4) {
            val d = draft.points[i] - c
            val u = t[i].x / 1000.0
            val v = t[i].y / 1000.0
            a += d.z * v - d.x * u
            b += d.x * v + d.z * u
        }
        val yaw = atan2(b, a).toFloat()
        val pose = PaperPose(Vec3(c.x, draft.y, c.z), yaw)
        val res = (0 until 4).map { i -> val p = P2.of(pose.toWorld(t[i])); hypot((p.x - draft.points[i].x).toDouble(), (p.z - draft.points[i].z).toDouble()).toFloat() * 1000f }
        return SheetFit(sheet, pose, res)
    }

    fun judge(draft: CornerDraft): FitVerdict = FitVerdict(fit(draft, Sheet.A4), fit(draft, Sheet.LETTER))

    /** Debug `corners`: four points given in paper mm of [pose] (the design's injection seam). */
    fun draftFromMm(pose: PaperPose, mm: List<Mm>): CornerDraft = CornerDraft(mm.map { P2.of(pose.toWorld(it)) }, pose.center.y, List(4) { true })
}

/** Smallest absolute difference of two headings (radians). */
fun angleBetween(a: Float, b: Float): Float = abs(wrapAngle(a - b))

/**
 * Snap of a dropped / turned frame onto the previous sheet position (re-alignment on the same spot,
 * taste profile D: "靠近目标虚线框时吸附对齐（位置和朝向）"): within [SNAP_M] and [SNAP_DEG].
 */
object FrameSnap {
    const val SNAP_M = 0.015f
    const val SNAP_DEG = 5f

    fun snap(pose: PaperPose, target: PaperPose?): PaperPose? {
        val t = target ?: return null
        val d = hypot((pose.center.x - t.center.x).toDouble(), (pose.center.z - t.center.z).toDouble())
        if (d > SNAP_M) return null
        var dy = angleBetween(pose.yaw, t.yaw)
        // a sheet turned half round lies on the same rectangle
        val flipped = angleBetween(pose.yaw, t.yaw + PI.toFloat())
        if (flipped < dy) dy = flipped
        if (Math.toDegrees(dy.toDouble()) > SNAP_DEG) return null
        return t.copy(center = Vec3(t.center.x, pose.center.y, t.center.z))
    }
}

/** Thumbstick X -> turn rate (degrees per second): 0.15 dead zone, square curve, 45 deg/s at full tilt (a sheet needs finer turns than furniture). */
object Stick {
    const val DEAD = 0.15f
    const val MAX_DEG_S = 45f

    fun rateDeg(x: Float): Float {
        val a = abs(x)
        if (a < DEAD) return 0f
        val k = (a - DEAD) / (1f - DEAD)
        return MAX_DEG_S * k * k * if (x < 0) -1f else 1f
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
