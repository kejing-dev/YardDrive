package io.embodit.yarddrive

import io.embodit.yarddrive.domain.HeadPose
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Seat
import io.embodit.yarddrive.domain.SeatFlow
import io.embodit.yarddrive.domain.StartSeat
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
            assertEquals(-10.0, Seat.P1_PITCH_DEG, 1e-9)
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

    /** Head [rightDeg] to the right of a car at stage yaw [carYawDeg]. */
    private fun looking(carYawDeg: Float, rightDeg: Float) = head(carYawDeg - rightDeg)

    private fun axisError(s: Seat, carYawDeg: Float): Double = abs(Math.toDegrees(io.embodit.yarddrive.domain.Yaw.wrap(s.yaw - Math.toRadians(carYawDeg.toDouble()).toFloat()).toDouble()))

    /** A whole flow at 50 fps with the head at stage yaw [headYaw] after the press: the seat it places, and when. */
    private fun flow(headYaw: Float, page: String = "HOME"): Pair<Seat, Long> {
        val f = SeatFlow()
        val samples = ArrayList<Pair<Long, HeadPose>>()
        var now = 10_000L
        samples += now to head(headYaw)
        f.start(now, page)
        while (now < 20_000L) {
            now += 20
            samples += now to head(headYaw)
            samples.removeAll { now - it.first > 1000 }
            when (val r = f.step(now, samples, page, driving = false)) {
                is SeatFlow.Step.Place -> return r.seat to now - 10_000L
                else -> Unit
            }
        }
        throw AssertionError("never placed")
    }

    /** Profile D29: the car axis off by any amount, the head looks ahead: the car ends up there. The dead-zone bugs were all in here. */
    @Test
    fun d29_axisOffAnyAmount() {
        for (off in listOf(5f, -5f, 8f, -8f, 10f, -10f, 12f, -12f, 14f, -14f, 20f, -20f, 90f, 180f)) {
            for (car in listOf(0f, 37f, -120f)) {
                val ahead = car - off
                val (s, took) = flow(ahead)
                assertTrue("off $off car $car: ${axisError(s, ahead)}", axisError(s, ahead) < 2.0)
                assertTrue(took >= SeatFlow.MIN_MS)
            }
        }
    }

    /** Profile D29's own case: pressed looking at the button (+9°), then the head turns ahead and stops. */
    @Test
    fun d29_pressAtTheButtonThenLookAhead() {
        for (button in listOf(9f, -9f, 19f)) {
            val car = 0f
            val f = SeatFlow()
            val samples = ArrayList<Pair<Long, HeadPose>>()
            var now = 10_000L
            f.start(now, "HOME")
            var placed: Seat? = null
            while (placed == null && now < 20_000L) {
                now += 20
                samples += now to looking(car, if (now - 10_000L < 400) button else 0f)
                samples.removeAll { now - it.first > 1000 }
                (f.step(now, samples, "HOME", false) as? SeatFlow.Step.Place)?.let { placed = it.seat }
            }
            assertTrue("button $button: ${axisError(placed!!, car)}", axisError(placed!!, car) < 2.0)
            assertTrue(now - 10_000L >= SeatFlow.MIN_MS)
        }
    }

    /** Tracking stops during the wait (no new samples): nothing placed from a stale pose, the page says why. */
    @Test
    fun d29_staleSamplesCancel() {
        val f = SeatFlow()
        val samples = ArrayList<Pair<Long, HeadPose>>()
        f.start(0L, "HOME")
        var now = 0L
        while (now < 400L) {
            now += 20
            samples += now to head(5f)
            assertTrue(f.step(now, samples, "HOME", false) is SeatFlow.Step.Waiting)
        }
        // no new samples from here on
        assertTrue(f.step(now + 400, samples, "HOME", false) is SeatFlow.Step.Waiting)
        assertEquals(SeatFlow.Step.Cancelled(lost = true), f.step(now + 501, samples, "HOME", false))
        assertTrue(!f.active)
    }

    /** R2: the car's first place is made once, from valid poses; looking around on S01 does not move it. */
    @Test
    fun d1_firstPlaceOnceFromValidPoses() {
        val st = StartSeat()
        // tracking not ready yet: all zeros, or nothing
        assertEquals(null, st.offer(0L, HeadPose(Vec3.ZERO, Quat4.IDENTITY)))
        assertEquals(null, st.offer(100L, null))
        assertEquals(null, st.offer(200L, head(10f)))
        assertEquals(null, st.offer(300L, head(10f)))
        val seat = st.offer(500L, head(10f))
        assertTrue(seat != null && axisError(seat, 10f) < 0.01)
        // the head turns 40° in the next 2 s (S01, looking around)
        var now = 500L
        while (now < 2500L) {
            now += 20
            assertEquals(null, st.offer(now, head(10f + 40f * (now - 500) / 2000)))
        }
    }

    /** The head does not move, three flows in a row: the axis does not move either. */
    @Test
    fun d29_threeFlowsDoNotAddUp() {
        repeat(3) { assertTrue(axisError(flow(25f).first, 25f) < 0.01) }
    }

    /** After the flow P1 stands at +6° of the new axis, at its distance, the HUB on the new axis too. */
    @Test
    fun d29_p1ComesBackOnTheNewAxis() {
        for (yaw in listOf(0f, 12f, -12f, 90f, 180f)) {
            val s = flow(yaw).first
            val d = s.toStage(Seat.p1Local()) - s.eye
            assertEquals(Seat.P1_YAW_DEG, s.lookRightDeg(io.embodit.yarddrive.domain.Yaw.of(d)), 0.2)
            assertEquals(Seat.P1_DIST.toFloat(), d.length, 0.01f)
        }
    }

    /** The head turning: not before it is still. Never still: at the time-out, wherever it points. */
    @Test
    fun d29_waitsForAStillHeadUpToTheTimeOut() {
        val f = SeatFlow()
        var now = 0L
        var yaw = 0f
        val samples = ArrayList<Pair<Long, HeadPose>>()
        f.start(now, "HOME")
        var placed: Seat? = null
        while (placed == null && now < 10_000L) {
            now += 20
            yaw += 0.4f // 20°/s
            samples += now to head(yaw)
            samples.removeAll { now - it.first > 1000 }
            (f.step(now, samples, "HOME", false) as? SeatFlow.Step.Place)?.let { placed = it.seat }
        }
        assertEquals(SeatFlow.MAX_MS.toDouble(), now.toDouble(), 40.0)
        assertTrue(axisError(placed!!, yaw) < 6.0)
        // turns for 2 s, then holds still: placed 0.5 s after it stopped
        val g = SeatFlow()
        now = 0L
        yaw = 0f
        samples.clear()
        g.start(now, "HOME")
        var doneAt = -1L
        var placedTurn: Seat? = null
        while (doneAt < 0 && now < 10_000L) {
            now += 20
            if (now < 2000) yaw += 0.4f
            samples += now to head(yaw)
            samples.removeAll { now - it.first > 1000 }
            (g.step(now, samples, "HOME", false) as? SeatFlow.Step.Place)?.let { doneAt = now; placedTurn = it.seat }
        }
        assertTrue("done $doneAt", doneAt in 2400L..2700L)
        assertTrue(axisError(placedTurn!!, yaw) < 2.0)
    }

    /** Leaving the page or driving ends it with nothing placed; a press during a wait changes nothing. */
    @Test
    fun d29_cancels() {
        val samples = listOf(0L to head(5f))
        val f = SeatFlow()
        f.start(0L, "HOME")
        assertEquals(SeatFlow.Step.Cancelled(), f.step(100L, samples, "LESSONS", false))
        assertTrue(!f.active)
        assertTrue(f.step(200L, samples, "LESSONS", false) === SeatFlow.Step.Idle)
        f.start(300L, "RUNtrue")
        assertEquals(SeatFlow.Step.Cancelled(), f.step(400L, listOf(400L to head(5f)), "RUNfalse", false))
        f.start(500L, "HOME")
        assertEquals(SeatFlow.Step.Cancelled(), f.step(600L, listOf(600L to head(5f)), "HOME", true))
        f.start(700L, "HOME")
        f.start(1500L, "SETTINGS") // ignored: same flow, first page
        assertTrue(f.step(1600L, listOf(1600L to head(5f)), "HOME", false) is SeatFlow.Step.Waiting)
    }

    /** "Still": slower than 5°/s for 0.5 s; a turn or too short a history is not. */
    @Test
    fun d29_stillHead() {
        fun track(vararg yawAt: Pair<Long, Float>) = yawAt.map { (t, y) -> t to head(y) }
        assertTrue(Seat.still(track(0L to 10f, 300L to 10.5f, 600L to 11f), 600L))
        assertTrue(!Seat.still(track(0L to 10f, 300L to 20f, 600L to 30f), 600L))
        assertTrue(!Seat.still(track(300L to 10f, 600L to 10f), 600L))
        // at about 1 frame per second (the emulator)
        assertTrue(Seat.still(track(0L to 5f, 1000L to 5f), 1000L))
        assertTrue(!Seat.still(track(0L to 5f, 1000L to 25f), 1000L))
    }

    /** A glance away in the last moment does not count: the median of the samples. */
    @Test
    fun d29_aGlanceDoesNotCount() {
        val samples = List(8) { looking(0f, 6f) } + listOf(looking(0f, 60f), looking(0f, 60f))
        assertTrue(axisError(Seat.settled(samples), -6f) < 2.0)
    }

    /** The eye follows the head. */
    @Test
    fun d29_eyeMovesWithTheHead() {
        val moved = HeadPose(Vec3(0.3f, 1.05f, -0.4f), looking(0f, 6f).rotation)
        assertEquals(1.05f, Seat.settled(listOf(moved)).eye.y, 1e-4f)
    }

    @Test
    fun invalidHeadPoseIsIgnored() {
        assertTrue(!Seat.valid(HeadPose(Vec3.ZERO, Quat4.IDENTITY)))
        assertTrue(Seat.valid(head(0f)))
    }
}
