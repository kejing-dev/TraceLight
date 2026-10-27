package io.embodit.tracelight.platform

import android.content.Context
import io.embodit.tracelight.data.PrefsTraceStore
import io.embodit.tracelight.data.TraceStore
import io.embodit.tracelight.platform.anchor.WorldAnchorGateway
import io.embodit.tracelight.platform.camera.PaperCamera
import io.embodit.tracelight.platform.sense.PlaneTracker
import io.embodit.tracelight.platform.tracking.ControllerInput
import io.embodit.tracelight.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops the sensing services (every start has a
 * stop: `TraceScene.attach` / `detach`); the ViewModel only sees interfaces and lambdas.
 */
object TraceGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()
    val camera =
        PaperCamera().apply {
            headSource = { head.pose()?.let { floatArrayOf(it.position.x, it.position.y, it.position.z, it.rotation.x, it.rotation.y, it.rotation.z, it.rotation.w) } }
        }

    @Volatile private var store: TraceStore? = null

    fun store(context: Context): TraceStore = store ?: synchronized(this) { store ?: PrefsTraceStore(context).also { store = it } }
}
