package io.embodit.tracelight.domain

import kotlin.math.abs

/**
 * The system pointer's drag, as measured in Spatial Ruler (round 5c, SDK 6.1.9 emulator): the system
 * reports how far the hand travelled in the view's plane; the pointing line turns by that travel over
 * an "equivalent depth" of the press point. The piece then follows where that line meets the
 * horizontal plane through the grab point (`rules.move`).
 */
object DragLine {
    const val HAND_OFFSET_M = 0.67f
    const val HAND_PER_M = 1.186f
    const val HAND_PER_PITCH_DEG = 0.0015f

    /** Metres of system drag travel per unit of view-plane displacement for a press [distance] metres away. */
    fun equivalentDepth(distance: Float, pitchDeg: Float): Float {
        if (distance <= 1e-3f) return 0f
        return (HAND_OFFSET_M + HAND_PER_M * distance) * (1f + HAND_PER_PITCH_DEG * abs(pitchDeg))
    }

    /** The pointing line after one hand step ([step], Stage metres) in the view's axes. */
    fun turn(direction: Vec3, step: Vec3, equivalentDepth: Float, right: Vec3, up: Vec3, forward: Vec3): Vec3 {
        val depth = direction.dot(forward)
        if (equivalentDepth <= 1e-3f || depth <= 1e-3f) return direction
        val x = direction.dot(right) / depth + step.dot(right) / equivalentDepth
        val y = direction.dot(up) / depth + step.dot(up) / equivalentDepth
        val to = right * x + up * y + forward
        return if (to.length < 1e-6f) direction else to.normalized()
    }

    /** Where the line from [origin] along [direction] meets the horizontal plane at height [y]; null if it does not (or beyond [maxM]). */
    fun onLevel(origin: Vec3, direction: Vec3, y: Float, maxM: Float = 8f): P2? {
        if (abs(direction.y) < 1e-4f) return null
        val t = (y - origin.y) / direction.y
        if (t <= 0f || t > maxM) return null
        val p = origin + direction * t
        return P2(p.x, p.z)
    }
}
