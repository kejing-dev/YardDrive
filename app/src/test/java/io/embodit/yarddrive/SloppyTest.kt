package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Free
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.Predictor
import io.embodit.yarddrive.domain.Rules
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Truth
import io.embodit.yarddrive.domain.Yard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * profile D27 + build.md gate: at least ten deliberately sloppy runs that must still pass. A "sloppy" driver here is the
 * ideal script with one human error at a time: the lock early / late inside the window, the straighten a few degrees off,
 * stopping short or deep, slow or fast speed, slow hands (manual 450°/s), crooked starts, hesitations under 2 s, the lane
 * lesson with the wheel a bit off centre. Lessons 3-5 judge the bay 0.2 m wider (gate B), which the last cases show.
 */
class SloppyTest {
    private val dt = 0.01

    /** A sloppy ideal driver: [lock] offset (+ early, m), [straightenDeg] offset (+ late), [stopOffset] (+ deeper, m). */
    private class Sloppy(val lock: Double = 0.0, val straightenDeg: Double = 0.0, val stopOffset: Double = 0.0, val rate: Double = Car.BUTTON_RATE, val hesitate: Double = 0.0) {
        private var seg = -1
        private var locked = false
        private var straight = false
        private var stopped = false
        private var holdUntil = -1.0
        private var hesitated = false
        private var clock = 0.0

        fun drive(r: Run, dt: Double) {
            clock += dt
            if (r.segIndex != seg) { seg = r.segIndex; locked = false; straight = false; stopped = false; hesitated = false }
            when (r.phase) {
                RunPhase.FINISHED, RunPhase.LINE -> return
                RunPhase.SEG_DONE -> { r.nextSeg?.let { if (r.sim.gear != it.gear) r.shift(it.gear) }; return }
                RunPhase.READY -> { if (r.sim.gear != r.seg.gear) r.shift(r.seg.gear); r.setGo(true); return }
                else -> Unit
            }
            if (holdUntil > 0) { if (clock < holdUntil) { r.setGo(false); return } else holdUntil = -1.0 }
            if (stopped) return
            val s = r.seg
            val p = r.pose
            if (!locked) {
                if (Truth.toLock(s, p, r.lockX) <= lock) {
                    if (rate >= Car.BUTTON_RATE) r.steerButton(s.lockSign * Car.WHEEL_MAX)
                    else {
                        r.handWheel(s.lockSign * (Car.WHEEL_DEAD + 1))
                        r.sim.steerTo(s.lockSign * Car.WHEEL_MAX, rate)
                    }
                    locked = true
                }
            } else if (!straight) {
                if (Truth.toStraighten(s, p) <= -straightenDeg) {
                    r.steerButton(0.0)
                    straight = true
                }
            } else {
                if (hesitate > 0 && !hesitated && s.bayIn && Truth.toStop(s, p) < 1.5) { hesitated = true; holdUntil = clock + hesitate; r.setGo(false); return }
                if (Truth.toStop(s, p) <= Truth.brake(r.sim.vSet) - (if (s.bayIn) stopOffset else 0.0)) { r.setGo(false); stopped = true; return }
            }
            r.setGo(true)
        }
    }

    private fun run(cfg: RunConfig, d: Sloppy, maxS: Double = 150.0): Run {
        val r = Run(cfg)
        var t = 0.0
        while (t < maxS && r.phase != RunPhase.FINISHED && r.phase != RunPhase.LINE) {
            d.drive(r, dt)
            r.tick(dt)
            t += dt
        }
        return r
    }

    private val exam = RunConfig(mode = Mode.MOCK, rules = Rules.EXAM, timed = true)
    private val lesson3 = RunConfig(mode = Mode.LESSON, lesson = 3, rules = Rules.LESSON, lenient = true, stepByStep = false, segments = listOf(SegId.I))

    private fun assertPass(r: Run, what: String) {
        assertEquals("$what: phase ${r.phase} touch ${r.touch} reason ${r.reason}", RunPhase.FINISHED, r.phase)
        assertEquals(what, Outcome.PASS, r.outcome)
        assertTrue(what, r.passed)
    }

    @Test fun s01_lockTwelveEarly() = assertPass(run(exam, Sloppy(lock = 0.12)), "lock 12 cm early")

    @Test fun s02_lockTwelveLate() = assertPass(run(exam, Sloppy(lock = -0.12)), "lock 12 cm late")

    @Test fun s03_straightenTwoDegreesEarly() = assertPass(run(exam, Sloppy(straightenDeg = -2.0)), "straighten 2° early")

    @Test fun s04_straightenTwoDegreesLate() = assertPass(run(exam, Sloppy(straightenDeg = 2.0, lock = 0.05)), "straighten 2° late")

    @Test fun s05_stopShort() {
        val r = run(exam, Sloppy(stopOffset = -0.20))
        assertPass(r, "stop 20 cm short")
        assertTrue(r.nose[SegId.I]!! in 0.10..0.20)
    }

    @Test fun s06_stopDeep() = assertPass(run(exam, Sloppy(stopOffset = 0.22)), "stop 22 cm deep")

    @Test fun s07_slowSpeed() = assertPass(run(exam.copy(speed = Car.SPEED_MIN, lockX = Predictor.bestLock(Yard.rightStart(), vSet = Car.SPEED_MIN)), Sloppy(lock = 0.08)), "0.5 m/s")

    @Test fun s08_fastSpeed() = assertPass(run(exam.copy(speed = Car.SPEED_MAX, lockX = Predictor.bestLock(Yard.rightStart(), vSet = Car.SPEED_MAX)), Sloppy(lock = -0.05)), "1.2 m/s")

    @Test fun s09_crookedStart() {
        // level 3, worst heading offset: the lock point from the look-ahead
        val st = (0 until 50).map { Free.start(it, 3) }.maxByOrNull { kotlin.math.abs(it.dPsiDeg) }!!
        val x0 = Predictor.bestLock(st.pose)
        val cfg = exam.copy(mode = Mode.FREE, start = st.pose, lockX = x0, segments = listOf(SegId.I))
        assertPass(run(cfg, Sloppy(lock = 0.04)), "crooked start ${st.dPsiDeg}°")
    }

    @Test fun s10_hesitateUnderTwoSeconds() {
        val r = run(exam, Sloppy(hesitate = 1.8))
        assertPass(r, "hesitate 1.8 s")
        assertEquals(0, r.stops)
    }

    @Test fun s11_slowHands() {
        // manual lock at 450°/s: lock earlier, as the look-ahead says (design: truth 3.86 at 1.2 s to full lock)
        assertPass(run(exam.copy(segments = listOf(SegId.I)), Sloppy(lock = 0.27, rate = Car.MANUAL_RATE)), "manual 450°/s")
    }

    @Test fun s12_lessonLenientTwentyEarly() {
        // lessons 3-5 judge the bay 0.2 m wider: 20 cm early passes here, and is out under the exam rule
        assertPass(run(lesson3, Sloppy(lock = 0.20)), "lesson 3, 20 cm early")
        val ex = run(exam.copy(segments = listOf(SegId.I)), Sloppy(lock = 0.24))
        assertEquals(Outcome.OUT, ex.outcome)
        assertPass(run(lesson3, Sloppy(lock = 0.24)), "lesson 3, 24 cm early")
    }

    @Test fun s13_lessonLenientLate() = assertPass(run(lesson3, Sloppy(lock = -0.22)), "lesson 3, 22 cm late")

    @Test fun s14_pullOutLateAndEarly() {
        val two = exam.copy(segments = listOf(SegId.II), start = Yard.inBay, startGear = Gear.D)
        assertPass(run(two, Sloppy(lock = -0.30)), "LP6 30 cm late")
        assertPass(run(two, Sloppy(lock = 0.15)), "LP6 15 cm early")
    }

    @Test fun s15_laneWheelOffCentre() {
        val cfg = RunConfig(mode = Mode.LESSON, lesson = 2, rules = Rules.LESSON, stepMode = false, segments = listOf(SegId.LANE1, SegId.LANE2))
        val r = Run(cfg)
        r.steerButton(Car.WHEEL_MAX)
        repeat(60) { r.tick(dt) }
        r.steerButton(0.0)
        repeat(60) { r.tick(dt) }
        r.handWheel(15.0) // a slightly crooked hand, inside the 30° dead zone
        val ap = Autopilot()
        var t = 0.0
        while (t < 60 && r.phase != RunPhase.FINISHED && r.phase != RunPhase.LINE) { ap.drive(r, dt); r.tick(dt); t += dt }
        assertEquals(RunPhase.FINISHED, r.phase)
        assertEquals(0, r.touches)
        assertTrue(r.sawLock && r.sawCentre)
    }
}
