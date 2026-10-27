package io.embodit.tracelight.platform.sense

import android.os.Handler
import android.os.Looper
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.tracelight.domain.PlaneSurface
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.platform.TraceLog

/**
 * Plane detection (`PlaneTrackingManager`, Full Space only). Planes arrive progressively (on the
 * emulator the first after about 20 s) and keep being re-reported; unchanged ones are dropped.
 * Vertices are converted to Stage metres here (anchor transform applied). Main thread only.
 */
class PlaneTracker {
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, PlaneSurface>()
    private var subscription: Cancellable? = null
    private var onChange: (() -> Unit)? = null

    val all: Collection<PlaneSurface>
        get() = surfaces.values

    fun start(onChange: () -> Unit) {
        if (subscription != null) return
        this.onChange = onChange
        // subscribe first, then start: no event is lost
        subscription = PlaneTrackingManager.subscribeAnchorUpdate { u -> if (Looper.myLooper() == Looper.getMainLooper()) handle(u) else main.post { handle(u) } }
        runCatching { PlaneTrackingManager.start() }.onFailure { TraceLog.e("PLANES start failed: $it", it) }
        TraceLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        onChange = null
        main.removeCallbacksAndMessages(null)
    }

    /** Debug: a plane that no tracker reported (a desk in front of the emulator camera); replaces one with the same id. */
    fun debugPut(surface: PlaneSurface) {
        surfaces[surface.id] = surface
        TraceLog.i("PLANES debug ${surface.label} center=${TraceLog.pos(surface.centroid)}")
        onChange?.invoke()
    }

    /** Planes stay known across a pause (they are re-reported on the next start). */
    fun clear() = surfaces.clear()

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val s = runCatching { toSurface(anchor, id) }.getOrNull() ?: return
                if (s.vertices.size < 3) return
                val prev = surfaces[id]
                if (prev != null && same(prev, s)) return
                surfaces[id] = s
                if (prev == null) TraceLog.i("PLANES add ${s.label} n=${surfaces.size} center=${TraceLog.pos(s.centroid)} normal=${TraceLog.pos(s.normal)}")
                onChange?.invoke()
            }
            AnchorUpdate.Event.REMOVED -> if (surfaces.remove(id) != null) onChange?.invoke()
            else -> Unit
        }
    }

    private fun toSurface(anchor: PlaneAnchor, id: String): PlaneSurface {
        val q = anchor.transform.quaternion
        val p = anchor.transform.position
        val stage =
            anchor.vertices.map { v ->
                val w = q.rotateVector(v) + p
                Vec3(w.x, w.y, w.z)
            }
        return PlaneSurface(id, anchor.semantics.name, stage)
    }

    private fun same(a: PlaneSurface, b: PlaneSurface): Boolean {
        if (a.vertices.size != b.vertices.size || a.semantic != b.semantic) return false
        for (i in a.vertices.indices) if (a.vertices[i].distanceTo(b.vertices[i]) > 0.0005f) return false
        return true
    }
}
