package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Comfort
import io.embodit.yarddrive.domain.DriveSim
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Settings
import io.embodit.yarddrive.domain.Yard
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdUiState
import io.embodit.yarddrive.ui.RunView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** User on the headset, 2026-11-05: "very dizzy", "tapped Go and nothing happened". */
class ComfortTest {
    @Test
    fun theVignetteAnswersTheTurn() {
        assertEquals(0f, Comfort.vignette(0.0, 0.5, 0.0), 1e-6f)
        assertEquals(Comfort.STRAIGHT, Comfort.vignette(0.5, 0.5, 0.0), 1e-6f)
        val s = DriveSim(Yard.rightStart())
        s.gear = Gear.R
        s.go = true
        s.setWheel(Car.WHEEL_MAX)
        s.advance(2.0)
        assertTrue("yaw ${Comfort.yawDegS(s)}", Comfort.yawDegS(s) > 8.0)
        assertEquals(1f, Comfort.vignette(s.v, s.vSet, Comfort.yawDegS(s)), 1e-6f)
    }

    /** The world only turns about the vertical and slides: nothing in the car model pitches, rolls or shakes. */
    @Test
    fun aDriveChangesHeadingOnly() {
        val s = DriveSim(Pose(0.0, 0.0, 0.0))
        s.gear = Gear.R
        s.go = true
        s.setWheel(Car.WHEEL_MAX)
        var lastPsi = s.pose.psi
        var maxStep = 0.0
        repeat(2000) {
            s.advance(0.005)
            maxStep = maxOf(maxStep, kotlin.math.abs(s.pose.psi - lastPsi))
            lastPsi = s.pose.psi
        }
        // about 9°/s at most, no jump in heading from one step to the next
        assertTrue("step $maxStep", maxStep < Math.toRadians(0.06))
    }

    private fun state(screen: Screen, phase: RunPhase = RunPhase.DRIVING, gear: Gear = Gear.R, seg: SegId = SegId.I) =
        YdUiState(screen = screen, run = RunView(segs = listOf(seg, SegId.II), phase = phase, gear = gear, wrongGear = phase == RunPhase.READY && gear != seg.gear))

    /** Every state where Go can't move the car has a reason to say; the ones where it can have none. */
    @Test
    fun goAlwaysAnswers() {
        assertNull(state(Screen.RUN).goReason())
        assertNull(state(Screen.RUN, RunPhase.READY).goReason())
        assertNull(state(Screen.RUN, RunPhase.STOPPED).goReason())
        assertNotNull(state(Screen.RUN, RunPhase.READY, Gear.D).goReason())
        assertEquals("Shift to R first", state(Screen.RUN, RunPhase.READY, Gear.D).goReason()!!.en)
        assertEquals("Shift to D first", state(Screen.RUN, RunPhase.SEG_DONE).goReason()!!.en)
        for (p in listOf(RunPhase.LINE, RunPhase.FINISHED)) assertNotNull(state(Screen.RUN, p).goReason())
        for (s in listOf(Screen.POINT, Screen.TOUR, Screen.DEMO, Screen.REPLAY)) assertNotNull(state(s).goReason())
    }

    /** Easy to drive: a lesson from the first key press to the bay, keys only, one tap each. */
    @Test
    fun keysAloneDriveIntoTheBay() {
        val r = Run(RunConfig(mode = Mode.LESSON, lesson = 6, segments = listOf(SegId.I)))
        r.shift(Gear.R)
        r.setGo(true)
        var locked = false
        var straight = false
        var stopped = false
        var t = 0.0
        val tap = 0.5 // a person's half a second late on every key
        var pending = -1.0
        var action = 0
        while (t < 60 && r.phase != RunPhase.FINISHED && r.phase != RunPhase.LINE) {
            val p = r.pose
            if (pending < 0) {
                if (!locked && io.embodit.yarddrive.domain.Truth.toLock(SegId.I, p, r.lockX) <= 0.5 * tap) { pending = t + tap; action = 1; locked = true }
                else if (locked && !straight && io.embodit.yarddrive.domain.Truth.toStraighten(SegId.I, p, r.sim.vSet) <= 0.0) { pending = t + tap * 0.2; action = 2; straight = true }
                else if (straight && !stopped && io.embodit.yarddrive.domain.Truth.toStop(SegId.I, p) <= io.embodit.yarddrive.domain.Truth.brake(r.sim.vSet) + 0.5 * tap) { pending = t + tap; action = 3; stopped = true }
            }
            if (pending in 0.0..t) {
                when (action) {
                    1 -> r.steerButton(Car.WHEEL_MAX)
                    2 -> r.steerButton(0.0)
                    3 -> r.setGo(false)
                }
                pending = -1.0
            }
            r.tick(0.01)
            t += 0.01
        }
        assertEquals("touch ${r.touch} reason ${r.reason?.en}", RunPhase.FINISHED, r.phase)
        assertTrue(r.passed)
        assertTrue(Settings().comfort && Settings().hints)
    }
}
