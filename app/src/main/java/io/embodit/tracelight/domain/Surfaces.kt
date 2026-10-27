package io.embodit.tracelight.domain

import kotlin.math.abs

/**
 * A detected plane: a polygon with its vertices in Stage metres. Winding is not trusted (wall quads
 * wind outwards on the emulator, Spatial Ruler), so hits are two-sided and [normal]'s sign is arbitrary.
 */
data class PlaneSurface(val id: String, val semantic: String, val vertices: List<Vec3>) {
    val normal: Vec3 by lazy {
        var best = Vec3.UP
        var bestLen = 0f
        for (i in 1 until vertices.size - 1) {
            val c = (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
            if (c.length > bestLen) {
                bestLen = c.length
                best = c
            }
        }
        if (bestLen < 1e-9f) Vec3.UP else best.normalized()
    }

    val centroid: Vec3 by lazy { if (vertices.isEmpty()) Vec3.ZERO else vertices.fold(Vec3.ZERO) { a, v -> a + v } / vertices.size.toFloat() }

    /** Area of the polygon (fan). */
    val area: Float by lazy {
        var a = Vec3.ZERO
        for (i in 1 until vertices.size - 1) a = a + (vertices[i] - vertices[0]).cross(vertices[i + 1] - vertices[0])
        a.length / 2f
    }

    val isHorizontal: Boolean
        get() = abs(normal.y) > 0.9f

    /** `SEMANTIC:uuid8`, used in entity names and logs. */
    val label: String
        get() = "$semantic:${id.take(8)}"

    /** Is [p] (on the plane) inside the polygon? Crossing count in the plane's 2D frame. */
    fun contains(p: Vec3): Boolean {
        if (vertices.size < 3) return false
        val n = normal
        val u = (vertices[1] - vertices[0]).rejectFrom(n).normalized()
        val w = n.cross(u)
        val px = (p - vertices[0]).dot(u)
        val py = (p - vertices[0]).dot(w)
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val xi = (vertices[i] - vertices[0]).dot(u)
            val yi = (vertices[i] - vertices[0]).dot(w)
            val xj = (vertices[j] - vertices[0]).dot(u)
            val yj = (vertices[j] - vertices[0]).dot(w)
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Two-sided ray hit: distance along the ray, or null. */
    fun hit(origin: Vec3, dir: Vec3): Float? {
        val n = normal
        val denom = dir.dot(n)
        if (abs(denom) < 1e-5f) return null
        val t = (vertices[0] - origin).dot(n) / denom
        if (t <= 0.05f) return null
        return if (contains(origin + dir * t)) t else null
    }
}

/** The nearest plane hit along a ray. */
data class SurfaceHit(val point: Vec3, val distance: Float, val plane: PlaneSurface)

fun raycast(planes: Collection<PlaneSurface>, origin: Vec3, dir: Vec3, maxM: Float = 8f): SurfaceHit? {
    var best: SurfaceHit? = null
    for (p in planes) {
        val t = p.hit(origin, dir) ?: continue
        if (t > maxM || (best != null && t >= best.distance)) continue
        best = SurfaceHit(origin + dir * t, t, p)
    }
    return best
}

/**
 * Which planes count as a desk (design `container.notes`): horizontal (|normal.y| > 0.95, the reported
 * orientation is not trusted), 0.45 - 1.15 m above the floor; TABLE first, CABINET / UNKNOWN horizontal
 * planes are accepted too (desks are often labelled wrong). Without a floor plane the head stands in:
 * a desk is 0.25 - 1.0 m below the eyes.
 */
object Desks {
    const val MIN_NY = 0.95f
    const val MIN_ABOVE_FLOOR_M = 0.45f
    const val MAX_ABOVE_FLOOR_M = 1.15f
    const val MIN_AREA_M2 = 0.06f
    private val ACCEPTED = setOf("TABLE", "CABINET", "UNKNOWN", "DESK", "COUCH", "BED")

    fun floorY(planes: Collection<PlaneSurface>): Float? {
        val floors = planes.filter { it.semantic == "FLOOR" && abs(it.normal.y) > MIN_NY }
        if (floors.isNotEmpty()) return floors.maxByOrNull { it.area }!!.centroid.y
        return null
    }

    fun desks(planes: Collection<PlaneSurface>, headY: Float?): List<PlaneSurface> {
        val floor = floorY(planes)
        return planes
            .filter { p ->
                if (abs(p.normal.y) <= MIN_NY || p.area < MIN_AREA_M2) return@filter false
                if (p.semantic !in ACCEPTED) return@filter false
                val y = p.centroid.y
                when {
                    floor != null -> y - floor in MIN_ABOVE_FLOOR_M..MAX_ABOVE_FLOOR_M
                    headY != null -> headY - y in 0.25f..1.0f
                    else -> true
                }
            }
            .sortedWith(compareBy<PlaneSurface> { if (it.semantic == "TABLE") 0 else 1 }.thenByDescending { it.area })
    }
}
