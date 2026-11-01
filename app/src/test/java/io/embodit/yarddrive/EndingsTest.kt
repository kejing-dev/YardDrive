package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.Rules
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.SegId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * build.md gate: the time-out and the "judge at the end" rules work in every branch of the run, not just the happy one
 * (dog-manners QA #1). The 210 s limit fires while driving, stopped mid-way, waiting after a segment, ready with the next
 * gear, and when the last segment finishes late. Lesson rules never time out; pause and lost tracking freeze the clock.
 */
class EndingsTest {
    private val dt = 0.05
    private val exam = RunConfig(mode = Mode.MOCK, rules = Rules.EXAM, timed = true)

    private fun until(r: Run, maxS: Double, ap: Autopilot? = null, each: (Run) -> Unit = {}): Run {
        var t = 0.0
        while (t < maxS && r.phase != RunPhase.FINISHED) {
            ap?.drive(r, dt)
            each(r)
            r.tick(dt)
            t += dt
        }
        return r
    }

    @Test
    fun timeoutWhileDriving() {
        // creeping back and forth in the lane, never finishing
        val r = Run(exam)
        r.shift(Gear.R)
        r.setGo(true)
        r.sim.vSet = 0.05
        until(r, 260.0)
        assertEquals(Outcome.TIMEOUT, r.outcome)
    }

    @Test
    fun timeoutWhileStoppedMidWay() {
        val r = Run(exam)
        r.shift(Gear.R)
        r.setGo(true)
        until(r, 1.0)
        r.setGo(false)
        until(r, 260.0)
        assertEquals(RunPhase.FINISHED, r.phase)
        assertEquals(Outcome.TIMEOUT, r.outcome)
        assertTrue(r.stops >= 1)
    }

    @Test
    fun timeoutWaitingAfterASegment() {
        // the ideal script up to the end of I, then nobody shifts
        val s = Run(exam)
        val a2 = Autopilot()
        var t = 0.0
        while (t < 300 && s.phase != RunPhase.FINISHED) {
            if (s.phase != RunPhase.SEG_DONE) a2.drive(s, dt)
            s.tick(dt)
            t += dt
        }
        assertEquals(Outcome.TIMEOUT, s.outcome)
        assertEquals(1, s.segOk.count { it })
    }

    @Test
    fun timeoutReadyInTheNextGear() {
        val s = Run(exam)
        val ap = Autopilot()
        var t = 0.0
        while (t < 300 && s.phase != RunPhase.FINISHED) {
            if (s.phase == RunPhase.READY && s.segIndex == 1) s.setGo(false) else ap.drive(s, dt)
            s.tick(dt)
            t += dt
        }
        assertEquals(Outcome.TIMEOUT, s.outcome)
        assertEquals(1, s.segIndex)
    }

    @Test
    fun lateFinishIsATimeoutNotAPass() {
        // every segment is clean, but you sit 52 s after each of the first three: the run ends out of time, not passed
        val s = Run(exam)
        val ap = Autopilot()
        var t = 0.0
        var waitedFor = -1
        var since = 0.0
        while (t < 400 && s.phase != RunPhase.FINISHED) {
            if (s.phase == RunPhase.SEG_DONE && waitedFor != s.segIndex) {
                since += dt
                if (since >= 52.0) { waitedFor = s.segIndex; since = 0.0 }
            } else ap.drive(s, dt)
            s.tick(dt)
            t += dt
        }
        assertEquals(Outcome.TIMEOUT, s.outcome)
        assertTrue(s.elapsed > Run.LIMIT_S)
        assertTrue(!s.passed)
    }

    @Test
    fun strictSwitchFailsOnAMidStop() {
        val r = Run(exam.copy(strict = true))
        r.shift(Gear.R)
        r.setGo(true)
        until(r, 1.0)
        r.setGo(false)
        until(r, 4.0)
        assertEquals(Outcome.STOP, r.outcome)
        val lenient = Run(exam)
        lenient.shift(Gear.R)
        lenient.setGo(true)
        until(lenient, 1.0)
        lenient.setGo(false)
        until(lenient, 4.0)
        assertNotEquals(RunPhase.FINISHED, lenient.phase)
    }

    @Test
    fun lessonRulesNeverTimeOut() {
        val r = Run(RunConfig(mode = Mode.LESSON, lesson = 6, rules = Rules.LESSON))
        r.shift(Gear.R)
        r.setGo(true)
        until(r, 1.0)
        r.setGo(false)
        until(r, 400.0)
        assertNotEquals(RunPhase.FINISHED, r.phase)
    }

    @Test
    fun pauseAndLostFreezeTheClock() {
        val r = Run(exam)
        r.shift(Gear.R)
        r.setGo(true)
        until(r, 2.0)
        val e = r.elapsed
        var t = 0.0
        while (t < 300) { r.tick(dt, paused = true); t += dt }
        t = 0.0
        while (t < 300) { r.tick(dt, lost = true); t += dt }
        assertEquals(e, r.elapsed, 1e-9)
        assertEquals(0, r.stops)
        assertNotEquals(RunPhase.FINISHED, r.phase)
    }

    @Test
    fun lessonOutLineThenBackToPoint() {
        val r = Run(RunConfig(mode = Mode.LESSON, lesson = 3, rules = Rules.LESSON, segments = listOf(SegId.I)))
        until(r, 60.0, Autopilot(lockEarly = -0.30)) { if (it.phase == RunPhase.LINE) it.frozen = true }
        assertEquals(RunPhase.LINE, r.phase)
        r.frozen = false
        r.backToPoint()
        assertEquals(RunPhase.STOPPED, r.phase)
        // now the ideal script from the last point gets in and the lesson run still passes
        val ap = Autopilot()
        var t = 0.0
        while (t < 60 && r.phase != RunPhase.FINISHED && r.phase != RunPhase.LINE) { ap.drive(r, dt); r.tick(dt); t += dt }
        assertEquals(RunPhase.FINISHED, r.phase)
        assertTrue(r.passed)
        assertEquals(1, r.backs)
    }

    @Test
    fun carryOnIsUnscored() {
        val r = Run(RunConfig(mode = Mode.LESSON, lesson = 6, rules = Rules.LESSON, segments = listOf(SegId.I)))
        until(r, 60.0, Autopilot(lockEarly = -0.30)) { if (it.phase == RunPhase.LINE) it.carryOn() }
        assertTrue(r.unscored)
        assertTrue(!r.passed)
        assertTrue(Car.WHEEL_MAX > 0)
    }
}
