package io.embodit.tracelight.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.tracelight.domain.HeadPose
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.platform.TraceLog

/**
 * HMD pose source (`HMDTrackingProvider`), read once per frame from the ECS system; the last valid
 * pose is kept so that a missing sample does not make the panels jump.
 *
 * The provider's support state is `NOT_IN_FULL_SPACE` while the Stage is still opening and turns to
 * `SUPPORTED` by itself (Spatial Ruler, SDK 6.1.9 on the emulator): [blockReason] reads it live and the
 * ViewModel gives it a grace period before the session counts as blocked.
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: HeadPose? = null
    private var lastValidMs = 0L

    /** Debug: tracking counts as lost. */
    var forceLost = false

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        val support = runCatching { p.supportState }.getOrNull()
        TraceLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=$support")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        last = null
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { p -> runCatching { p.supportState }.getOrNull() }

    /** Null while the head can be tracked (or it is not yet known); else why not. */
    fun blockReason(): String? =
        when (supportState()) {
            DataProvider.SupportState.NOT_IN_FULL_SPACE -> "not-in-full-space"
            DataProvider.SupportState.DEVICE_NOT_SUPPORTED -> "device-not-supported"
            else -> null
        }

    /** True when tracking has given no valid pose for a while. */
    fun lost(nowMs: Long): Boolean = forceLost || (provider != null && last != null && nowMs - lastValidMs > LOST_AFTER_MS)

    /** Current head pose, or the last one that was valid, or null before the first sample. */
    fun pose(): HeadPose? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val position = pose.position
        val rotation = pose.rotation
        val sample = HeadPose(Vec3(position.x, position.y, position.z), Quat4(rotation.x, rotation.y, rotation.z, rotation.w))
        if (!sample.position.isFinite()) return last
        last = sample
        lastValidMs = SystemClock.elapsedRealtime()
        return sample
    }

    private companion object {
        const val LOST_AFTER_MS = 1500L
    }
}
