package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Rules
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.StopView
import io.embodit.yarddrive.domain.Truth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profile D31: what the ring and the "settled" line show is the judge's state; the deduction shown = the score card. */
class StopViewTest {
    private val dt = 0.02
    private val exam = RunConfig(mode = Mode.MOCK, rules = Rules.EXAM, timed = true)

    private fun tick(r: Run, s: Double, each: (Run) -> Unit = {}) {
        var t = 0.0
        while (t < s && r.phase != RunPhase.FINISHED) {
            each(r)
            r.tick(dt)
            t += dt
        }
    }

    /** Stopped mid-way under test rules: blue, amber, then "-5" exactly when the score drops by 5. */
    @Test
    fun midStopUnderTestRules() {
        val r = Run(exam)
        r.shift(Gear.R)
        r.setGo(true)
        tick(r, 2.0)
        r.setGo(false)
        var seenAmber = false
        tick(r, 4.0) {
            val v = it.stopView
            if (v.kind == StopView.Kind.TIMER_AMBER) seenAmber = true
            // the score card and the ring always agree
            assertEquals(v.kind == StopView.Kind.PENALTY, it.stops == 1)
            assertEquals(if (it.stops == 1) 95 else 100, it.score)
        }
        assertTrue(seenAmber)
        assertEquals(StopView.Kind.PENALTY, r.stopView.kind)
        assertEquals(1, r.stops)
        // moving off clears it; the next stop starts from zero, not from "-5"
        r.setGo(true)
        tick(r, 1.0)
        assertEquals(StopView.Kind.NONE, r.stopView.kind)
        r.setGo(false)
        tick(r, 1.5)
        assertEquals(StopView.Kind.TIMER, r.stopView.kind)
        assertEquals(1, r.stops)
    }

    /** Stopped where the segment ends: "settled", never a red ring, nothing charged (the critic's R1). */
    @Test
    fun stopAtTheEndOfTheSegmentIsNotCharged() {
        val r = Run(exam)
        val ap = Autopilot()
        var t = 0.0
        while (t < 90 && r.stopView.kind != StopView.Kind.SETTLING && r.phase != RunPhase.SEG_DONE) {
            ap.drive(r, dt)
            r.tick(dt)
            t += dt
        }
        assertEquals(StopView.Kind.SETTLING, r.stopView.kind)
        assertEquals(0, r.stops)
        assertEquals(100, r.score)
        var left = r.stopView.left
        while (r.phase == RunPhase.STOPPED) {
            r.tick(dt)
            if (r.phase == RunPhase.STOPPED) {
                assertEquals(StopView.Kind.SETTLING, r.stopView.kind)
                assertTrue(r.stopView.left <= left)
                left = r.stopView.left
            }
        }
        assertEquals(RunPhase.SEG_DONE, r.phase)
        assertEquals(0, r.stops)
    }

    /** Stopped at the bay with the nose still out: it says how far for the 3 s, no "-5"; then NOTIN, no deduction shown. */
    @Test
    fun noseStillOutHesitation() {
        val r = Run(exam)
        val ap = Autopilot()
        var t = 0.0
        while (t < 60 && !(Truth.atEnd(r.seg, r.pose, exam.start) && Truth.nose(r.pose) < -0.25)) {
            ap.drive(r, dt)
            r.tick(dt)
            t += dt
        }
        r.setGo(false)
        tick(r, 1.5)
        assertEquals(StopView.Kind.NOSE_OUT, r.stopView.kind)
        assertTrue(r.stopView.noseOutCm > 0)
        assertEquals(0, r.stops)
        tick(r, 8.0) { if (it.phase == RunPhase.STOPPED) assertNotEquals(StopView.Kind.PENALTY, it.stopView.kind) }
        assertEquals(Outcome.NOTIN, r.outcome)
        assertEquals(0, r.stops)
    }

    /** Lesson rules: the ring goes amber, never "-5" (design S08). */
    @Test
    fun lessonRulesNeverShowAPenalty() {
        val r = Run(RunConfig(mode = Mode.LESSON, rules = Rules.LESSON))
        r.shift(Gear.R)
        r.setGo(true)
        tick(r, 2.0)
        r.setGo(false)
        tick(r, 3.0) { assertNotEquals(StopView.Kind.PENALTY, it.stopView.kind) }
        assertEquals(StopView.Kind.TIMER_AMBER, r.stopView.kind)
    }

    private fun stopsThenFinish(rules: Rules, n: Int): Run {
        val r = Run(RunConfig(mode = Mode.LESSON, rules = rules))
        val ap = Autopilot()
        var t = 0.0
        var stops = 0
        var pausedFor = 0.0
        while (t < 300 && r.phase != RunPhase.FINISHED) {
            // n stops, each long enough to count, while reversing in the first segment
            if (stops < n && r.phase == RunPhase.DRIVING && r.sim.moving && r.seg == io.embodit.yarddrive.domain.SegId.I && r.pose.x > 4.0 && pausedFor <= 0.0) {
                stops++
                pausedFor = 2.6
            }
            if (pausedFor > 0) {
                r.setGo(false)
                pausedFor -= dt
                if (pausedFor <= 0 && r.sim.v == 0.0) r.setGo(true)
            } else ap.drive(r, dt)
            r.tick(dt)
            t += dt
        }
        return r
    }

    /** Critic B2: lesson rules note the stops and take nothing off; the card, the score and "passed" agree. */
    @Test
    fun lessonStopsAreNotedNotDeducted() {
        for (n in listOf(3, 5)) {
            val r = stopsThenFinish(Rules.LESSON, n)
            assertEquals(RunPhase.FINISHED, r.phase)
            assertEquals(n, r.stops)
            assertEquals(100, r.score)
            assertTrue(r.passed)
        }
    }

    @Test
    fun testRulesStillDeduct() {
        val r = stopsThenFinish(Rules.EXAM, 3)
        assertEquals(3, r.stops)
        assertEquals(85, r.score)
        assertTrue(r.passed)
        val r5 = stopsThenFinish(Rules.EXAM, 5)
        assertEquals(75, r5.score)
        assertTrue(!r5.passed)
    }

    /** "Back to the last point" stands the car still with no timer running: no ring. */
    @Test
    fun noRingAfterBackToPoint() {
        val r = Run(RunConfig(mode = Mode.LESSON, rules = Rules.LESSON))
        r.place(Pose(5.0, -2.65, 0.0))
        r.shift(Gear.R)
        r.setGo(true)
        tick(r, 1.0)
        r.backToPoint()
        tick(r, 3.0)
        assertEquals(StopView.Kind.NONE, r.stopView.kind)
    }
}
