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

/**
 * Calibration page (design S20 / `preconditions.deviceSpike.build.sheet`): five crosses at the printed
 * targets, and the 5-minute tracing test figure on the upper half.
 */
object Calibration {
    val targets: List<Mm> = listOf(Mm(0f, 0f), Mm(-80f, -120f), Mm(80f, -120f), Mm(80f, 120f), Mm(-80f, 120f))
    const val ARM_MM = 12f
    const val LINE_MM = 0.6f

    /** Upper-half test figure: a 100 mm circle and an 80 mm square, concentric at the upper half's centre. */
    val testCenter = Mm(0f, 74.25f)

    fun testCircle(segments: Int = 96): List<Mm> = (0..segments).map { i -> val a = 2 * PI * i / segments; Mm(testCenter.x + 50f * cos(a).toFloat(), testCenter.y + 50f * sin(a).toFloat()) }

    fun testSquare(): List<Mm> = listOf(Mm(-40f, -40f), Mm(40f, -40f), Mm(40f, 40f), Mm(-40f, 40f), Mm(-40f, -40f)).map { Mm(testCenter.x + it.x, testCenter.y + it.y) }

    /** The five targets on a sheet of any size: the centre and 25 / 28.5 mm in from the corners (A4: (+-80, +-120)). */
    fun targetsFor(wMm: Float, hMm: Float): List<Mm> {
        val x = wMm / 2f - 25f
        val y = hMm / 2f - 28.5f
        return listOf(Mm(0f, 0f), Mm(-x, -y), Mm(x, -y), Mm(x, y), Mm(-x, y))
    }

    fun crosses(wMm: Float, hMm: Float): List<List<Mm>> = targetsFor(wMm, hMm).flatMap { t -> listOf(listOf(Mm(t.x - ARM_MM, t.y), Mm(t.x + ARM_MM, t.y)), listOf(Mm(t.x, t.y - ARM_MM), Mm(t.x, t.y + ARM_MM))) }

    fun crosses(): List<List<Mm>> = targets.flatMap { t -> listOf(listOf(Mm(t.x - ARM_MM, t.y), Mm(t.x + ARM_MM, t.y)), listOf(Mm(t.x, t.y - ARM_MM), Mm(t.x, t.y + ARM_MM))) }
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
