package io.embodit.tracelight.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.platform.TraceLog
import kotlin.math.abs

/**
 * The controllers (`ControllerTrackingProvider`, Full Space only; SDK doc "Obtain controller input
 * actions"), ported from Furniture Fit's thumbstick: the thumbstick X turns the frame (taste profile D),
 * a trigger press while a controller's tip is on the desk puts the current corner there (design T4).
 * The action listener runs on the data source thread at the device rate: it only stores values; the ECS
 * system reads them once per frame. Hand gestures are untouched (another input path).
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null

    @Volatile private var x = 0f
    @Volatile private var atMs = 0L
    @Volatile var actions = 0L
        private set

    // trigger presses (rising edges) per hand, counted on the data thread, consumed per frame
    @Volatile private var leftDown = false
    @Volatile private var rightDown = false
    @Volatile private var leftPresses = 0
    @Volatile private var rightPresses = 0
    private var leftSeen = 0
    private var rightSeen = 0

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugUntilMs = 0L

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            x = if (abs(r) >= abs(l)) r else l
            if (a.left.triggerPressed && !leftDown) leftPresses++
            if (a.right.triggerPressed && !rightDown) rightPresses++
            leftDown = a.left.triggerPressed
            rightDown = a.right.triggerPressed
            atMs = SystemClock.elapsedRealtime()
            actions++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { TraceLog.w("STICK listener: $it") }
        val result = runCatching { p.start() }
        TraceLog.i("STICK start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        x = 0f
        actions = 0L
    }

    private fun supported(): Boolean = runCatching { provider?.supportState }.getOrNull() == DataProvider.SupportState.SUPPORTED

    /** Thumbstick X in [-1, 1] (right +); 0 without a controller or when the last report is stale. */
    fun x(nowMs: Long): Float {
        if (nowMs < debugUntilMs) return debugX
        if (provider == null || !supported()) return 0f
        if (nowMs - atMs > STALE_MS) return 0f
        return x.coerceIn(-1f, 1f)
    }

    /** A controller pose: tracking origin in Stage metres and its rotation (the tip offset is unconfirmed: logged, not assumed). */
    data class Pose(val position: Vec3, val rotation: Quat4)

    fun poses(): Pair<Pose?, Pose?> {
        val p = provider ?: return null to null
        if (!supported()) return null to null
        val d = runCatching { p.latestData }.getOrNull() ?: return null to null
        fun conv(c: com.pico.spatial.tracking.controller.ControllerPose?): Pose? {
            c ?: return null
            val v = Vec3(c.position.x, c.position.y, c.position.z)
            if (!v.isFinite() || v.length < 1e-4f) return null
            return Pose(v, Quat4(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w))
        }
        return runCatching { conv(d.left) to conv(d.right) }.getOrDefault(null to null)
    }

    /** Trigger presses since the last call: which hands (left, right). */
    fun takePresses(): Pair<Boolean, Boolean> {
        val l = leftPresses
        val r = rightPresses
        val out = (l != leftSeen) to (r != rightSeen)
        leftSeen = l
        rightSeen = r
        return out
    }

    /** Debug `stick`: holds [value] for [ms]. */
    fun debugHold(value: Float, ms: Long) {
        debugX = value.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actions x=${"%.2f".format(x)}"

    private companion object {
        const val STALE_MS = 500L
    }
}
