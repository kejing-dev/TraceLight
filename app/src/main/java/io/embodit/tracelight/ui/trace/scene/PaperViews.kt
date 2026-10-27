package io.embodit.tracelight.ui.trace.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import io.embodit.tracelight.domain.Mm
import io.embodit.tracelight.domain.P2
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.Vec3
import kotlin.math.sin

/** Paper-local mesh coordinates: x = paper x, z = -paper y (metres), so a mesh hangs under [sheetRotation]. */
internal fun Mm.local(): Vec3 = Vec3(x / 1000f, 0f, -y / 1000f)

/** Rotation that turns paper-local meshes onto a sheet: local x -> the sheet's right, local -z -> its top. */
internal fun sheetRotation(pose: PaperPose): Quat4 {
    val r = pose.right.at(0f)
    val back = (pose.away * -1f).at(0f)
    return Quat4.fromBasis(r, Vec3.UP, back)
}

/** A mesh in paper-local coordinates placed on a sheet (calibration crosses, test figure, guide layers). */
internal class SheetMesh(parent: Entity, private val mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var on = true
    private var material = material

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        show(false)
    }

    fun place(pose: PaperPose, lift: Float) {
        entity.setLocal(position = pose.center + Vec3(0f, lift, 0f), rotation = sheetRotation(pose))
        show(true)
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
        runCatching { mesh.close() }
    }
}

/**
 * One corner handle (design `entities3d.cornerHandle`): an 18 mm ring with a cross in the sheet's axes,
 * lying on the desk; a 48 mm sphere collider makes it easy to pinch. Amber + breathing = the corner to
 * drag now, jade = placed, white = not yet. The frame's move handle (index 4) is the same, bigger.
 */
internal class CornerHandle(parent: Entity, meshes: TraceMeshes, private val m: TraceMaterials, val index: Int, private val ringM: Float = RING_M, private val armM: Float = ARM_M, colliderR: Float = COLLIDER_R) {
    private val ring: Entity = ModelEntity(meshes.ring, m.white)
    private val armA = Strip(parent, meshes.band, m.white, "cornerArmA:$index")
    private val armB = Strip(parent, meshes.band, m.white, "cornerArmB:$index")
    val collider: Entity = Entity()
    private var ringMaterial: Material = m.white
    private var on = true

    init {
        runCatching { ring.setName("cornerRing:$index") }
        parent.addChild(ring)
        runCatching { collider.setName("corner:$index") }
        collider.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(colliderR)), physicsMaterial = PhysicsMaterialResource()))
        collider.components.set(InteractableComponent())
        parent.addChild(collider)
        show(false)
    }

    fun owns(e: Entity): Boolean = e.id == collider.id || e.id == ring.id

    /** At [at] (desk height [y]); [axes] = the sheet's right / away for the cross; [look] picks the colour. */
    fun draw(at: P2, y: Float, right: P2, away: P2, look: Look, nowMs: Long) {
        val p = at.at(y)
        val pulse = if (look == Look.ACTIVE) 1f + 0.18f * (0.5f + 0.5f * sin(nowMs / 1000f * 2f * Math.PI.toFloat() * 0.9f)) else 1f
        ring.setLocal(position = p, scale = Vec3(ringM * pulse, 1f, ringM * pulse))
        val mat =
            when (look) {
                Look.ACTIVE -> m.amber
                Look.PLACED -> m.jade
                Look.OFF -> m.coral
                Look.IDLE -> m.white
            }
        if (mat !== ringMaterial) {
            ringMaterial = mat
            ring.setMaterial(mat)
            armA.material(mat)
            armB.material(mat)
        }
        val r = right.at(0f)
        val a = away.at(0f)
        armA.draw(p - r * armM, p + r * armM, CROSS_W_M, Vec3.UP)
        armB.draw(p - a * armM, p + a * armM, CROSS_W_M, Vec3.UP)
        collider.setLocal(position = p)
        show(true)
    }

    fun show(visible: Boolean) {
        if (visible == on) return
        on = visible
        ring.enabled = visible
        collider.enabled = visible
        if (!visible) {
            armA.hide()
            armB.hide()
        }
    }

    enum class Look { ACTIVE, PLACED, OFF, IDLE }

    companion object {
        // v0.2.0: bigger than the design's 14 mm / 30 mm: on the device most pinches missed the corners
        const val RING_M = 0.018f
        const val ARM_M = 0.012f
        const val CROSS_W_M = 0.0007f
        const val COLLIDER_R = 0.024f
    }
}

/** A rectangle of four strips through four points (frame edges, the ghost outline). */
internal class Outline(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val sides = List(4) { Strip(parent, mesh, material, "$name:$it") }

    fun draw(points: List<P2>, y: Float, width: Float) {
        for (k in 0 until 4) sides[k].draw(points[k].at(y), points[(k + 1) % 4].at(y), width, Vec3.UP)
    }

    fun material(m: Material) = sides.forEach { it.material(m) }

    fun hide() = sides.forEach { it.hide() }
}
