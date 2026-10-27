package io.embodit.tracelight.ui.trace.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import io.embodit.tracelight.domain.PlaneSurface
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.platform.TraceLog
import kotlin.math.abs

/**
 * Tap targets of the room for the system pointer (Spatial Ruler): every plane gets a thin two-sided
 * box collider (a plane's own static-mesh collider is one-sided and wall quads face outwards); a shell
 * of slabs catches taps that hit nothing. A tap on any of them = "tapped the empty room" (deselect,
 * close the menu); the design's `floorHit`.
 */
internal class RoomColliders(private val root: Entity) {
    private class Collider(val entity: Entity, var center: Vec3, var size: Vec3, var rotation: Quat4)

    private val colliders = HashMap<String, Collider>()
    private val ids = HashSet<Long>()
    private var backstop: Entity? = null

    fun isRoom(entity: Entity): Boolean = entity.id in ids || runCatching { entity.getName() }.getOrNull()?.startsWith("backstop") == true

    fun sync(planes: Collection<PlaneSurface>) {
        val seen = HashSet<String>()
        for (s in planes) {
            seen += s.id
            upsert(s)
        }
        for (id in colliders.keys.filter { it !in seen }) colliders.remove(id)?.let {
            ids.remove(it.entity.id)
            runCatching { it.entity.destroy(true) }
        }
    }

    private fun upsert(surface: PlaneSurface) {
        val fit = fit(surface) ?: return
        val existing = colliders[surface.id]
        if (existing == null) {
            val e = Entity()
            runCatching { e.setName("plane:${surface.label.replace('-', '_')}") }
            e.components.set(collision(fit.second))
            e.components.set(InteractableComponent())
            root.addChild(e)
            e.setLocal(position = fit.first, rotation = fit.third)
            colliders[surface.id] = Collider(e, fit.first, fit.second, fit.third)
            ids += e.id
            return
        }
        if (existing.size.distanceTo(fit.second) > 0.001f) {
            existing.entity.components.set(collision(fit.second))
            existing.size = fit.second
        }
        if (existing.center.distanceTo(fit.first) > 0.0005f || abs(existing.rotation.x - fit.third.x) + abs(existing.rotation.w - fit.third.w) > 1e-4f) {
            existing.entity.setLocal(position = fit.first, rotation = fit.third)
            existing.center = fit.first
            existing.rotation = fit.third
        }
    }

    fun buildBackstop() {
        if (backstop != null) return
        val shell = Entity()
        runCatching { shell.setName("backstop") }
        root.addChild(shell)
        val h = 9f
        val t = 0.2f
        val cy = 1.5f
        val slabs =
            listOf(
                Triple("px", Vec3(h, cy, 0f), Vec3(t, 2 * h, 2 * h)), Triple("nx", Vec3(-h, cy, 0f), Vec3(t, 2 * h, 2 * h)),
                Triple("pz", Vec3(0f, cy, h), Vec3(2 * h, 2 * h, t)), Triple("nz", Vec3(0f, cy, -h), Vec3(2 * h, 2 * h, t)),
                Triple("py", Vec3(0f, cy + h, 0f), Vec3(2 * h, t, 2 * h)), Triple("ny", Vec3(0f, cy - h, 0f), Vec3(2 * h, t, 2 * h)),
            )
        for ((tag, center, size) in slabs) {
            val slab = Entity()
            runCatching { slab.setName("backstop:$tag") }
            slab.components.set(collision(size))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.setLocal(position = center)
        }
        backstop = shell
    }

    fun clear() {
        colliders.values.forEach { runCatching { it.entity.destroy(true) } }
        colliders.clear()
        ids.clear()
        runCatching { backstop?.destroy(true) }
        backstop = null
    }

    private fun collision(size: Vec3) = CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))), physicsMaterial = PhysicsMaterialResource())

    /** Oriented box around the polygon in its own plane: centre, size (6 mm thick), rotation. */
    private fun fit(s: PlaneSurface): Triple<Vec3, Vec3, Quat4>? {
        val v = s.vertices
        if (v.size < 3) return null
        val n = s.normal
        var u = Vec3.ZERO
        for (i in v.indices) {
            val e = (v[(i + 1) % v.size] - v[i]).rejectFrom(n)
            if (e.length > 0.01f) {
                u = e.normalized()
                break
            }
        }
        if (u.length < 0.5f) return null
        val w = n.cross(u).normalized()
        var u0 = Float.MAX_VALUE
        var u1 = -Float.MAX_VALUE
        var w0 = Float.MAX_VALUE
        var w1 = -Float.MAX_VALUE
        for (p in v) {
            val d = p - v[0]
            u0 = minOf(u0, d.dot(u))
            u1 = maxOf(u1, d.dot(u))
            w0 = minOf(w0, d.dot(w))
            w1 = maxOf(w1, d.dot(w))
        }
        if (u1 - u0 < 0.01f || w1 - w0 < 0.01f) {
            TraceLog.w("PLANES skip degenerate ${s.label}")
            return null
        }
        val center = v[0] + u * ((u0 + u1) / 2f) + w * ((w0 + w1) / 2f)
        return Triple(center, Vec3(u1 - u0, w1 - w0, 0.006f), Quat4.fromBasis(u, w, n))
    }
}
