package io.embodit.tracelight.domain

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperFitTest {
    private val pose = PaperPose(Vec3(0.3f, 0.75f, -0.4f), 2.5f)

    @Test
    fun exactA4FitsWithZeroResidual() {
        val v = PaperFit.judge(PaperFit.draftFromMm(pose, Sheet.A4.corners))
        assertTrue(v.pass)
        assertEquals(0f, v.a4.maxMm, 0.05f)
        assertEquals(pose.center.x, v.a4.pose.center.x, 1e-4f)
        assertEquals(pose.center.z, v.a4.pose.center.z, 1e-4f)
        assertEquals(0f, angleBetween(pose.yaw, v.a4.pose.yaw), 1e-3f)
    }

    @Test
    fun oneCornerOffEightMmIsNamed() {
        val mm = Sheet.A4.corners.mapIndexed { i, c -> if (i == 2) Mm(c.x + 8f, c.y) else c }
        val v = PaperFit.judge(PaperFit.draftFromMm(pose, mm))
        assertFalse(v.pass)
        assertEquals(2, v.a4.worst)
        assertTrue(v.a4.maxMm > 4f)
        assertFalse(v.looksLetter)
    }

    @Test
    fun letterSheetIsRecognised() {
        val v = PaperFit.judge(PaperFit.draftFromMm(pose, Sheet.LETTER.corners))
        assertFalse(v.pass)
        assertTrue(v.looksLetter)
    }

    @Test
    fun allCornersShiftedTheSameWayStillPass() {
        // design `corners.pass`: a consistency check, not an accuracy guarantee
        val mm = Sheet.A4.corners.map { Mm(it.x + 6f, it.y - 3f) }
        assertTrue(PaperFit.judge(PaperFit.draftFromMm(pose, mm)).pass)
    }

    @Test
    fun paperCoordinatesRoundTrip() {
        val p = Mm(-80f, 120f)
        val back = pose.toPaper(P2.of(pose.toWorld(p)))
        assertEquals(p.x, back.x, 1e-2f)
        assertEquals(p.y, back.y, 1e-2f)
    }

    @Test
    fun awayIsTheLevelGazeAndRightIsToTheRight() {
        // looking down -z: the paper's top is away (-z), its right is +x
        val p = PaperPose(Vec3.ZERO, yawOf(P2(0f, -1f)))
        assertEquals(-1f, p.away.z, 1e-5f)
        assertEquals(1f, p.right.x, 1e-5f)
        val ur = p.toWorld(Sheet.A4.corners[2])
        assertTrue(ur.x > 0f && ur.z < 0f)
    }

    @Test
    fun turnedDraftKeepsItsShape() {
        val d = CornerDraft.of(pose).turned(0.3f)
        val v = PaperFit.judge(d)
        assertEquals(0f, v.a4.maxMm, 0.05f)
        assertEquals(0f, angleBetween(pose.yaw + 0.3f, v.a4.pose.yaw), 1e-3f)
    }

    @Test
    fun snapNearPreviousOnly() {
        val near = pose.copy(center = pose.center + Vec3(0.008f, 0f, -0.006f), yaw = pose.yaw + 0.05f)
        val snapped = FrameSnap.snap(near, pose)!!
        assertEquals(pose.center.x, snapped.center.x, 1e-6f)
        assertEquals(pose.yaw, snapped.yaw, 1e-6f)
        assertNull(FrameSnap.snap(near.copy(center = pose.center + Vec3(0.03f, 0f, 0f)), pose))
        assertNull(FrameSnap.snap(near.copy(yaw = pose.yaw + 0.2f), pose))
        // half a turn is the same rectangle
        assertTrue(FrameSnap.snap(pose.copy(yaw = pose.yaw + PI.toFloat()), pose) != null)
    }

    @Test
    fun stickHasDeadZoneAndSign() {
        assertEquals(0f, Stick.rateDeg(0.1f), 0f)
        assertTrue(Stick.rateDeg(1f) > 0f)
        assertTrue(Stick.rateDeg(-1f) < 0f)
        assertEquals(Stick.MAX_DEG_S, Stick.rateDeg(1f), 1e-4f)
    }

    @Test
    fun deskFilterKeepsDeskHeightHorizontalPlanes() {
        fun quad(id: String, sem: String, y: Float) = PlaneSurface(id, sem, listOf(Vec3(0f, y, 0f), Vec3(1f, y, 0f), Vec3(1f, y, 1f), Vec3(0f, y, 1f)))
        val planes = listOf(quad("f", "FLOOR", 0f), quad("t", "TABLE", 0.75f), quad("c", "TABLE", 0.35f), quad("u", "UNKNOWN", 0.72f))
        val desks = Desks.desks(planes, 1.2f)
        assertEquals(listOf("t", "u"), desks.map { it.id })
    }
}
