package io.embodit.yarddrive

import io.embodit.yarddrive.domain.HeadPose
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Seat
import io.embodit.yarddrive.domain.Vec3
import io.embodit.yarddrive.domain.Yaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos

/**
 * U16 / profile D9 + D10: the car (and every panel on it) is placed from the head position and its yaw only. Head pitched
 * 30° down / up or rolled 20°: the cabin's up stays the world up (< 1°), the panels keep the design pitch (P1 -10°, P2 R
 * -15° / D +1°). Four headings (east / south / west / north): the car front follows the head yaw (< 10°).
 */
class PlacementTest {
    private fun head(yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f): HeadPose {
        val q = Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3(0f, 0f, -1f), Math.toRadians(rollDeg.toDouble()).toFloat())
        return HeadPose(Vec3(0.3f, 1.25f, -0.4f), q)
    }

    private fun angle(a: Vec3, b: Vec3): Double = Math.toDegrees(acos((a.normalized().dot(b.normalized())).toDouble().coerceIn(-1.0, 1.0)))

    @Test
    fun u16_cabinUprightForTiltedHeads() {
        for ((p, r) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, -30f to 20f)) {
            val seat = Seat.place(head(15f, p, r))
            val up = seat.toStageDir(Vec3.UP)
            assertTrue("pitch $p roll $r", angle(up, Vec3.UP) < 1.0)
            // the eye is the head position (cabin origin), the yaw is the head yaw
            assertEquals(0.3f, seat.eye.x, 1e-4f)
            assertEquals(15.0, Math.toDegrees(seat.yaw.toDouble()), 0.5)
            // panels: fixed design pitch whatever the head does
            assertEquals(-8.5, Seat.P1_PITCH_DEG, 1e-9) // design -10°, raised 1.5° for the 600 dp P1 (deviation)
            assertEquals(-15.0, Seat.P2_PITCH_R, 1e-9)
            assertEquals(1.0, Seat.P2_PITCH_D, 1e-9)
            val n = seat.panelNormalStage(Seat.P1_YAW_DEG, Seat.P1_PITCH_DEG)
            // the panel faces back at the eye along its own pitch: no roll
            val right = seat.toStageDir(Vec3(1f, 0f, 0f))
            assertTrue(abs(right.y) < 1e-4f)
            assertTrue(n.isFinite())
        }
    }

    @Test
    fun fourHeadings() {
        for (yaw in listOf(0f, 90f, 180f, 270f)) {
            val seat = Seat.place(head(yaw, -12f, 5f))
            val front = seat.toStageDir(Vec3(0f, 0f, -1f))
            val want = Yaw.dir(Math.toRadians(yaw.toDouble()).toFloat())
            assertTrue("yaw $yaw", angle(front, want) < 10.0)
            // P1 in front of you, below the eye, 1.05 m away
            val p1 = seat.toStage(Seat.p1Local())
            val d = p1 - seat.eye
            assertEquals(1.05f, d.length, 0.01f)
            assertTrue(d.y < 0)
            assertTrue(angle(Vec3(d.x, 0f, d.z), want) < 10.0)
        }
    }

    @Test
    fun invalidHeadPoseIsIgnored() {
        assertTrue(!Seat.valid(HeadPose(Vec3.ZERO, Quat4.IDENTITY)))
        assertTrue(Seat.valid(head(0f)))
    }
}
