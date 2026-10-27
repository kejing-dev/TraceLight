package io.embodit.tracelight.ui.trace.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.platform.TraceLog
import io.embodit.tracelight.ui.theme.TraceColors

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler: deleting one pin closed the materials of all pins), so shared ones are made
 * global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials, one per look, shared by every entity. Unlit colours are exact only with
 * tone mapping off (Spatial Ruler: `#FFC72C` arrived as `#D1B132` with it on). Every line is unlit and
 * two-sided: it lies on the desk and is seen from above at any angle.
 */
internal class TraceMaterials(private val shared: SharedResources) {
    val amber: Material = unlit(TraceColors.PenAmber)
    val jade: Material = unlit(TraceColors.Jade)
    val coral: Material = unlit(TraceColors.Coral)
    val white: Material = unlit(TraceColors.White)
    val warm: Material = unlit(TraceColors.LightboxWarm)
    val traceBlue: Material = unlit(TraceColors.TraceBlue)
    val cinnabar: Material = unlit(TraceColors.Cinnabar)
    val ghost: Material = sheer(TraceColors.White, 0.6f)
    val faint: Material = sheer(TraceColors.White, 0.25f)
    val divider: Material = sheer(TraceColors.White, 0.45f)
    val debug: Material = sheer(TraceColors.Debug, 0.5f)
    /** Earlier steps of a drawing: the guide colour, faded. */
    val traceBluePast: Material = sheer(TraceColors.TraceBlue, 0.38f)
    val grid: Material = sheer(TraceColors.White, 0.3f)

    private fun unlit(color: Color): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through: the colour is opaque, the material's opacity carries the share (Spatial Ruler). */
    private fun sheer(color: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )
}

/** Programmatic meshes, built once per scene (creating a mesh costs about one rendered frame). */
internal object MeshFactory {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** A unit cube centred on the origin (scaled to any box). */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val faces =
            listOf(
                Vector3(1f, 0f, 0f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f),
                Vector3(0f, -1f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 0f, -1f),
            )
        for (n in faces) {
            val nn = Vec3(n.x, n.y, n.z)
            // two in-plane axes with u x v = n, so the quad winds counter-clockwise seen from outside
            val u = (if (kotlin.math.abs(n.y) > 0.5f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)).let { it.rejectFrom(nn).normalized() }
            val v = nn.cross(u)
            val base = positions.size
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = (nn + u * a + v * b) * 0.5f
                positions += Vector3(p.x, p.y, p.z)
                normals += n
            }
            indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** A unit square on the floor (local XZ, centred, facing +Y). */
    fun unitSquare(name: String): MeshResource {
        val positions = listOf(Vector3(-0.5f, 0f, -0.5f), Vector3(0.5f, 0f, -0.5f), Vector3(0.5f, 0f, 0.5f), Vector3(-0.5f, 0f, 0.5f))
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 2, 1, 0, 3, 2), normals = List(4) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z, cut into [dashes] (0 = solid): scaled to any length and width. */
    fun unitBand(name: String, dashes: Int = 0, fill: Float = 0.55f): MeshResource {
        val rects = if (dashes <= 0) listOf(0f to 1f) else List(dashes) { i -> val p = 1f / dashes; (i * p + p * (1f - fill) / 2f) to (i * p + p * (1f + fill) / 2f) }
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for ((x0, x1) in rects) {
            val b = positions.size
            positions += listOf(Vector3(x0, -0.5f, 0f), Vector3(x1, -0.5f, 0f), Vector3(x1, 0.5f, 0f), Vector3(x0, 0.5f, 0f))
            indices += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { FRONT })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)), name = name)
    }

    /** A flat ring on the desk (local XZ, facing +Y): outer radius 0.5, inner [inner] x 0.5; scaled to any size. */
    fun ring(name: String, inner: Float, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0..segments) {
            val a = 2.0 * Math.PI * i / segments
            val c = kotlin.math.cos(a).toFloat()
            val s = kotlin.math.sin(a).toFloat()
            positions += Vector3(0.5f * c, 0f, 0.5f * s)
            positions += Vector3(0.5f * inner * c, 0f, 0.5f * inner * s)
        }
        for (i in 0 until segments) {
            val o = i * 2
            indices += listOf(o, o + 2, o + 1, o + 1, o + 2, o + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /**
     * Polylines as one flat mesh on the desk (local XZ, facing +Y, metres): each segment a [width]-wide
     * quad, joints covered by a square cap. [dash] > 0 cuts every polyline into dashes of that length.
     */
    fun polylines(name: String, lines: List<List<Vec3>>, width: Float, dash: Float = 0f): MeshResource? {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val h = width / 2f
        fun quad(a: Vec3, b: Vec3) {
            val d = b - a
            val len = d.length
            if (len < 1e-5f) return
            val u = d / len
            val n = Vec3(-u.z, 0f, u.x) * h
            val a0 = a - u * h
            val b0 = b + u * h
            val o = positions.size
            positions += listOf((a0 - n).let { Vector3(it.x, it.y, it.z) }, (b0 - n).let { Vector3(it.x, it.y, it.z) }, (b0 + n).let { Vector3(it.x, it.y, it.z) }, (a0 + n).let { Vector3(it.x, it.y, it.z) })
            indices += listOf(o, o + 2, o + 1, o, o + 3, o + 2)
        }
        for (line in lines) {
            if (dash <= 0f) {
                for (i in 0 until line.size - 1) quad(line[i], line[i + 1])
                continue
            }
            // dashes along the whole polyline: on for [dash], off for [dash]
            var on = true
            var left = dash
            for (i in 0 until line.size - 1) {
                var a = line[i]
                val b = line[i + 1]
                var seg = (b - a).length
                while (seg > 1e-6f) {
                    val step = minOf(seg, left)
                    val c = a + (b - a) * (step / seg)
                    if (on) quad(a, c)
                    a = c
                    seg -= step
                    left -= step
                    if (left <= 1e-6f) {
                        on = !on
                        left = dash
                    }
                }
            }
        }
        if (positions.isEmpty()) return null
        var lo = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var hi = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in positions) {
            lo = Vec3(minOf(lo.x, p.x), minOf(lo.y, p.y), minOf(lo.z, p.z))
            hi = Vec3(maxOf(hi.x, p.x), maxOf(hi.y, p.y), maxOf(hi.z, p.z))
        }
        val c = (lo + hi) * 0.5f
        val e = (hi - lo) * 0.5f
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(e.x, 0.001f), maxOf(e.y, 0.001f), maxOf(e.z, 0.001f))), name = name)
    }
}

/** The shared meshes of the scene. */
internal class TraceMeshes(shared: SharedResources) {
    val cube: MeshResource = shared.keep(MeshFactory.unitCube("unitCube"))
    val square: MeshResource = shared.keep(MeshFactory.unitSquare("unitSquare"))
    val band: MeshResource = shared.keep(MeshFactory.unitBand("unitBand"))
    val dashes: MeshResource = shared.keep(MeshFactory.unitBand("unitDashes", dashes = 12))
    val ring: MeshResource = shared.keep(MeshFactory.ring("cornerRing", inner = 0.83f))
    val disc: MeshResource = shared.keep(MeshFactory.ring("disc", inner = 0f, segments = 20))
}

/** A box entity on the shared unit cube: placed by centre, size and rotation. */
internal class Box(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
    }

    fun place(center: Vec3, size: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        entity.setLocal(position = center, rotation = rotation, scale = size)
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
}

/**
 * A straight strip between two points with its face turned along a normal (made square to the strip):
 * the shared unit band scaled to length and width (tapes, footprint tape, outlines, stems).
 */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }
}
