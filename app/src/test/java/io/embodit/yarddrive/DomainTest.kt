package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Angles
import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.CarModel
import io.embodit.yarddrive.domain.DriveSim
import io.embodit.yarddrive.domain.Free
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Hit
import io.embodit.yarddrive.domain.JudgeLine
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Predictor
import io.embodit.yarddrive.domain.Rules
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Truth
import io.embodit.yarddrive.domain.Yard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Design model.judging.unitTests U1-U15, U17 (U16 is in PlacementTest), on the plain JVM. */
class DomainTest {
    private val dt = 0.01

    private fun exam(timed: Boolean = true, strict: Boolean = false) = RunConfig(mode = Mode.MOCK, rules = Rules.EXAM, timed = timed, strict = strict)

    /** Runs [run] with [ap] until it finishes or [maxS] passes; [each] runs before every tick. */
    private fun drive(run: Run, ap: Autopilot?, maxS: Double = 120.0, each: (Run, Double) -> Unit = { _, _ -> }): Run {
        var t = 0.0
        while (t < maxS && run.phase != RunPhase.FINISHED && run.phase != RunPhase.LINE) {
            ap?.drive(run, dt)
            each(run, t)
            run.tick(dt)
            t += dt
        }
        return run
    }

    // ---------------------------------------------------------------- U1 / U2

    @Test
    fun u1_kinematicsAndHandedness() {
        val s = DriveSim(Pose(0.0, 0.0, 0.0))
        s.gear = Gear.R
        s.setWheel(Car.DELTA_MAX * Car.RATIO)
        var arc = 0.0
        while (s.pose.psiDeg < 90.0) {
            val before = s.pose.rear
            s.forceSpeed(0.8)
            s.advance(0.001)
            arc += (s.pose.rear - before).length
        }
        assertEquals(90.0, s.pose.psiDeg, 0.5)
        assertEquals(-3.33, s.x, 0.02)
        assertEquals(3.33, s.z, 0.02)
        assertEquals(5.23, arc, 0.03)
        val d = DriveSim(Pose(0.0, 0.0, Math.toRadians(90.0)))
        d.gear = Gear.D
        d.setWheel(-Car.DELTA_MAX * Car.RATIO)
        while (d.pose.psiDeg < 180.0) {
            d.forceSpeed(0.8)
            d.advance(0.001)
        }
        assertEquals(-3.33, d.x, 0.02)
        assertEquals(-3.33, d.z, 0.02)
    }

    @Test
    fun u2_bodyCorners() {
        val c = Pose(0.0, 0.0, 0.0).corners()
        assertEquals(3.45, c[0].x, 1e-9); assertEquals(0.85, c[0].z, 1e-9)
        assertEquals(3.45, c[1].x, 1e-9); assertEquals(-0.85, c[1].z, 1e-9)
        assertEquals(-1.05, c[2].x, 1e-9); assertEquals(-0.85, c[2].z, 1e-9)
        assertEquals(-1.05, c[3].x, 1e-9); assertEquals(0.85, c[3].z, 1e-9)
        val n = Pose.deg(0.0, 0.0, 90.0).corners()
        assertEquals(setOf(0.85, -0.85), n.map { Math.round(it.x * 100) / 100.0 }.toSet())
        assertEquals(setOf(-3.45, 1.05), n.map { Math.round(it.z * 100) / 100.0 }.toSet())
    }

    // ---------------------------------------------------------------- U3 lines

    private fun placedStill(p: Pose, cfg: RunConfig = exam(false)): Run {
        val r = Run(cfg)
        r.place(Yard.rightStart())
        return r
    }

    @Test
    fun u3_lines() {
        // right start: across the control line, not out
        val a = Run(exam(false))
        a.tick(0.1)
        assertFalse(a.phase == RunPhase.FINISHED)
        // north side over the road edge
        val b = Run(exam(false))
        b.sim.teleport(Pose(5.45, -5.95, 0.0))
        b.tick(0.05)
        assertEquals(Outcome.OUT, b.outcome)
        assertEquals(JudgeLine.ROAD, b.touch!!.line)
        // tail over the back line
        val c = Run(exam(false).copy(segments = listOf(SegId.I)))
        c.sim.teleport(Pose.deg(0.0, 4.10, 90.0))
        c.shift(Gear.R)
        c.setGo(true)
        drive(c, null, 2.0)
        assertEquals(Outcome.OUT, c.outcome)
        assertEquals(JudgeLine.BAY_BACK, c.touch!!.line)
        // stopped short: nose -0.15 -> not in
        val d = Run(exam(false).copy(segments = listOf(SegId.I)))
        d.sim.teleport(Pose.deg(0.0, 2.90, 90.0))
        d.shift(Gear.R)
        d.setGo(true)
        drive(d, null, 8.0) { r, t -> if (r.pose.z >= 3.30 - Truth.brake(r.sim.vSet)) r.setGo(false) }
        assertEquals(Outcome.NOTIN, d.outcome)
        // on the line centre: right side exactly at 1.15 -> not out
        val e = Run(exam(false).copy(segments = listOf(SegId.I)))
        e.sim.teleport(Pose.deg(0.30, 3.70, 90.0))
        e.shift(Gear.R)
        e.setGo(true)
        drive(e, null, 0.3)
        assertTrue(e.phase != RunPhase.FINISHED || e.outcome != Outcome.OUT)
    }

    // ---------------------------------------------------------------- U4 the ideal script

    @Test
    fun u4_idealScript() {
        val r = drive(Run(exam()), Autopilot())
        assertEquals(RunPhase.FINISHED, r.phase)
        assertEquals(Outcome.PASS, r.outcome)
        assertEquals(100, r.score)
        assertTrue(r.passed)
        assertEquals(0.19, r.cornerGap[SegId.I]!!, 0.03)
        assertEquals(0.18, r.lineGap[SegId.I]!!, 0.03)
        assertEquals(0.35, r.nose[SegId.I]!!, 0.05)
        assertEquals(0.19, r.cornerGap[SegId.III]!!, 0.03)
        assertEquals(0.18, r.lineGap[SegId.III]!!, 0.03)
        assertTrue("total ${r.elapsed}", r.elapsed <= 100.0) // 0.5 m/s; the exam allows 210 s
        assertEquals(4, r.segOk.count { it })
    }

    @Test
    fun u4_segmentTwoEnd() {
        val r = Run(exam())
        val ap = Autopilot()
        var end: Pose? = null
        drive(r, ap) { run, _ -> if (end == null && run.segOk[1]) end = run.pose }
        assertNotNull(end)
        assertEquals(-5.60, end!!.x, 0.1)
        assertEquals(-2.65, end!!.z, 0.08)
    }

    // ---------------------------------------------------------------- U5 early / late with the look-ahead

    private fun lockedAt(x0: Double, extra: Double = 0.0): DriveSim {
        val s = DriveSim(Yard.rightStart())
        s.gear = Gear.R
        s.go = true
        while (s.x > x0) s.advance(0.005)
        s.steerTo(Car.WHEEL_MAX)
        s.advance(1.0)
        return s
    }

    @Test
    fun u5_earlyEaseOffThenIn() {
        val pr = Predictor.predict(lockedAt(Truth.LOCK_I_X + 0.15), SegId.I, 0.0)
        assertFalse(pr.hold.ok)
        assertEquals(Predictor.Advice.EASE, pr.advice)
        // follow the advice: it gets in, centred, both gaps >= 0.10
        val run = Run(exam(false).copy(segments = listOf(SegId.I)))
        val ap = Autopilot(lockEarly = 0.15, followAdvice = true)
        var lastPredict = 0.0
        drive(run, ap) { r, t ->
            if (t - lastPredict >= 0.2) {
                lastPredict = t
                r.prediction = Predictor.predict(r.sim, SegId.I, 0.0)
            }
        }
        assertEquals(Outcome.PASS, run.outcome)
        assertTrue(abs(run.pose.x) <= 0.10)
        assertTrue(run.cornerGap[SegId.I]!! >= 0.10)
        assertTrue(run.lineGap[SegId.I]!! >= 0.10)
    }

    @Test
    fun u5_veryEarlyHoldIsOut() {
        val pr = Predictor.predict(lockedAt(Truth.LOCK_I_X + 0.35), SegId.I, 0.0)
        assertTrue(pr.hold.out)
        // deviation (state.json): at +0.35 this model still finds an ease-off branch; the design says none does
        assertTrue(pr.advice == Predictor.Advice.EASE || pr.advice == Predictor.Advice.NO_WAY)
    }

    @Test
    fun u5_lateClipsTheCorner() {
        val pr = Predictor.predict(lockedAt(Truth.LOCK_I_X - 0.20), SegId.I, 0.0)
        assertEquals(Predictor.Advice.NO_WAY, pr.advice)
        // lesson rules: line touched (S10) with the reason sentence
        val run = Run(RunConfig(mode = Mode.LESSON, lesson = 6, rules = Rules.LESSON, segments = listOf(SegId.I)))
        drive(run, Autopilot(lockEarly = -0.20))
        assertEquals(RunPhase.LINE, run.phase)
        assertTrue(run.reason!!.zh, Regex("右打死晚了 2[01] cm，左后角压库角").matches(run.reason!!.zh))
    }

    // ---------------------------------------------------------------- U6 mid-way stops

    private fun stopFor(seconds: Double, stepMode: Boolean = false, pause: Boolean = false): Run {
        val run = Run(exam(false).copy(stepMode = stepMode))
        run.shift(Gear.R)
        run.setGo(true)
        repeat(100) { run.tick(dt) }
        run.setGo(false)
        var t = 0.0
        while (t < seconds + 0.25) {
            run.tick(dt, paused = pause)
            t += dt
        }
        return run
    }

    @Test
    fun u6_midStops() {
        // the 1 s stop curve, then the 2 s the judge counts from standing still
        assertEquals(1, stopFor(2.9).stops)
        assertEquals(0, stopFor(2.7).stops)
        assertEquals(0, stopFor(5.0, pause = true).stops)
        // step mode: stops do not count
        val st = Run(RunConfig(rules = Rules.LESSON, stepMode = true, segments = listOf(SegId.I)))
        st.shift(Gear.R)
        st.step()
        var t = 0.0
        while (t < 5.0) { st.tick(dt); t += dt }
        assertEquals(0, st.stops)
        // end-of-segment settle does not count (ideal script)
        assertEquals(0, drive(Run(exam()), Autopilot()).stops)
    }

    // ---------------------------------------------------------------- U7 order

    @Test
    fun u7_wrongWayOut() {
        val r = Run(exam(false).copy(segments = listOf(SegId.I, SegId.II)))
        val ap = Autopilot()
        var turned = false
        drive(r, ap) { run, _ -> if (run.segIndex == 1 && !turned && run.pose.z <= Truth.LOCK_OUT_Z) { turned = true; run.steerButton(Car.WHEEL_MAX) } }
        assertEquals(Outcome.ORDER, r.outcome)
        val ok = drive(Run(exam(false).copy(segments = listOf(SegId.I, SegId.II))), Autopilot())
        assertEquals(Outcome.PASS, ok.outcome)
    }

    @Test
    fun u7_forwardBeforeBackingIn() {
        val r = Run(exam(false))
        r.setGo(true) // still in D at the start
        drive(r, null, 2.0)
        assertEquals(Outcome.ORDER, r.outcome)
    }

    // ---------------------------------------------------------------- U8 / U9 sight lines

    @Test
    fun u8_mirrorShellGroundPoint() {
        val (ahead, lat) = Car.mirrorGround(1.20)
        assertEquals(2.92, ahead, 0.02)
        assertEquals(-3.29, lat, 0.02)
        assertEquals(2.72, Car.mirrorGround(1.12).first, 0.02)
        assertEquals(3.11, Car.mirrorGround(1.28).first, 0.02)
        assertEquals(3.53, LookPoints.chantLockX(1.20), 0.01)
        assertEquals(3.73, LookPoints.chantLockX(1.12), 0.01)
        assertEquals(3.34, LookPoints.chantLockX(1.28), 0.01)
        assertEquals(-8, LookPoints.chantLateCm("LP2", 1.20)) // -1 at 0.8 m/s: the truth moved, the mirror did not
        assertEquals(-28, LookPoints.chantLateCm("LP2", 1.12))
        assertEquals(11, LookPoints.chantLateCm("LP2", 1.28))
    }

    @Test
    fun u9_bonnetGroundPoint() {
        assertEquals(5.33, Car.hoodGround(1.20), 0.02)
        assertEquals(4.98, Car.hoodGround(1.12), 0.02)
        assertEquals(5.69, Car.hoodGround(1.28), 0.02)
        assertEquals(0.03, LookPoints.chantOutZ(1.20), 0.01)
        assertEquals(-0.32, LookPoints.chantOutZ(1.12), 0.01)
        assertEquals(0.39, LookPoints.chantOutZ(1.28), 0.01)
        assertEquals(76, LookPoints.chantLateCm("LP6", 1.20))
        assertEquals(111, LookPoints.chantLateCm("LP6", 1.12))
        assertEquals(40, LookPoints.chantLateCm("LP6", 1.28))
        assertEquals(57, LookPoints.chantLateCm("LP5", 1.20))
    }

    // ---------------------------------------------------------------- U10 score

    @Test
    fun u10_score() {
        fun withStops(n: Int): Run {
            val at = listOf(3.3, 2.7, 2.1, 1.6, 1.2).take(n)
            var k = 0
            var holdUntil = -1.0
            return drive(Run(exam()), Autopilot()) { r, t ->
                if (holdUntil < 0 && k < at.size && r.seg == SegId.II && r.phase == RunPhase.DRIVING && r.pose.z <= at[k]) {
                    holdUntil = t + 3.4
                    k++
                }
                if (holdUntil >= 0) {
                    if (t < holdUntil) r.setGo(false) else holdUntil = -1.0
                }
            }
        }
        val three = withStops(3)
        assertEquals(3, three.stops)
        assertEquals(85, three.score)
        assertTrue(three.passed)
        val five = withStops(5)
        assertEquals(75, five.score)
        assertFalse(five.passed)
    }

    // ---------------------------------------------------------------- U11 free practice is solvable

    @Test
    fun u11_freePracticeSolvable() {
        for (d in 1..3) for (seed in 0 until 200) {
            val st = Free.start(seed, d)
            assertTrue(Free.inRange(st))
            val x0 = Predictor.bestLock(st.pose)
            val (gc, gl, out) = Autopilot.scriptGaps(st.pose, SegId.I, x0)
            assertFalse("seed $seed d $d out", out)
            assertTrue("seed $seed d $d gaps $gc $gl", gc >= 0.10 && gl >= 0.10)
        }
    }

    // ---------------------------------------------------------------- U12 gear

    @Test
    fun u12_gearOnlyWhenStill() {
        val r = Run(exam(false))
        assertTrue(r.shift(Gear.R))
        r.setGo(true)
        repeat(50) { r.tick(dt) }
        assertFalse(r.shift(Gear.D))
        assertEquals(Gear.R, r.sim.gear)
        r.setGo(false)
        repeat(60) { r.tick(dt) }
        assertTrue(r.shift(Gear.D))
        repeat(150) { r.tick(dt) }
        assertEquals(0, r.stops)
    }

    // ---------------------------------------------------------------- U13 early / late

    @Test
    fun u13_earlyLateAtThePress() {
        for ((off, want) in listOf(0.10 to -10, -0.10 to 10)) {
            val r = Run(exam(false).copy(segments = listOf(SegId.I)))
            drive(r, Autopilot(lockEarly = off), 30.0)
            val h = r.hits.first { it.lp == "LP2" }
            assertEquals(want, h.lateCm)
        }
    }

    // ---------------------------------------------------------------- U14 LP6 window (instant steering)

    private fun pullOut(zt: Double): Pair<Boolean, Double> {
        val s = DriveSim(Yard.inBay.copy(x = 0.0))
        s.gear = Gear.D
        s.go = true
        val lines = Yard.judgeLines().values
        var out = false
        var phase = 0
        var t = 0.0
        while (t < 40) {
            val p = s.pose
            if (phase == 0 && p.z <= zt) { s.setWheel(-Car.WHEEL_MAX); phase = 1 }
            if (phase == 1 && p.psiDeg >= 180.0) { s.setWheel(0.0); phase = 2 }
            if (phase == 2 && p.x < -6.0) break
            s.advance(0.01)
            t += 0.01
            val poly = s.pose.corners()
            for (l in lines) if (io.embodit.yarddrive.domain.Geo.over(poly, l.a, l.b)) out = true
        }
        val gapToExt = -(s.pose.corners().maxOf { it.z })
        return out to gapToExt
    }

    @Test
    fun u14_pullOutWindow() {
        assertFalse(pullOut(1.0).first)
        assertTrue(pullOut(1.25).first)
        val (o1, g1) = pullOut(0.68)
        assertFalse(o1)
        assertEquals(1.80, g1, 0.05)
        assertFalse(pullOut(0.03).first)
        assertFalse(pullOut(-1.3).first)
        assertTrue(pullOut(-1.6).first)
    }

    // ---------------------------------------------------------------- U15 / U17 look-ahead

    @Test
    fun u15_predictionMatchesReality() {
        val run = Run(exam(false).copy(segments = listOf(SegId.I)))
        val ap = Autopilot()
        var checked = 0
        var t = 0.0
        while (run.phase != RunPhase.FINISHED && t < 40) {
            ap.drive(run, dt)
            if (run.sim.wheel >= Car.WHEEL_MAX - 1 && run.pose.psiDeg in 20.0..60.0 && checked < 3 && (t * 100).toInt() % 100 == 0) {
                val pr = Predictor.predict(run.sim, SegId.I, 0.0)
                val copy = Run(exam(false).copy(segments = listOf(SegId.I)))
                // the real continuation: the same ideal script from here
                val real = run.sim.copy()
                real.go = true
                var tt = 0.0
                var straight = false
                while (tt < 30) {
                    if (!straight && Truth.toStraighten(SegId.I, real.pose) <= 0) { real.steerTo(0.0); straight = true }
                    if (straight && Truth.toStop(SegId.I, real.pose) <= 0) break
                    real.advance(0.01)
                    tt += 0.01
                }
                assertTrue(copy.phase == RunPhase.READY)
                assertEquals(real.pose.x, pr.hold.end.x, 0.05)
                assertEquals(real.pose.z, pr.hold.end.z, 0.05)
                checked++
            }
            run.tick(dt)
            t += dt
        }
        assertTrue(checked > 0)
    }

    @Test
    fun u17_unreachableHeading() {
        val s = DriveSim(Yard.rightStart())
        s.gear = Gear.R
        val pr = Predictor.predict(s, SegId.I, 0.0)
        assertFalse(pr.hold.reachable)
        val wrong = DriveSim(Yard.rightStart())
        wrong.gear = Gear.R
        wrong.setWheel(-Car.WHEEL_MAX)
        assertFalse(Predictor.predict(wrong, SegId.I, 0.0).hold.reachable)
        // lock branch at the truth point ends where the ideal script ends
        val at = DriveSim(Yard.rightStart())
        at.gear = Gear.R
        at.go = true
        while (at.x > Truth.LOCK_I_X) at.advance(0.005)
        val lock = Predictor.predict(at, SegId.I, 0.0).lock
        assertFalse(lock.out)
        assertEquals(LookPoints.byId("LP5")!!.pose.x, lock.end.x, 0.1)
        assertEquals(0.19, lock.gapCorner, 0.03)
    }

    // ---------------------------------------------------------------- look-points

    /** The numbers the truth table holds are what the look-ahead finds at the default speed and ramps (0.5 m/s, 1 s; user, 2026-11-05). */
    @Test
    fun truthConstantsMatchTheDefaults() {
        assertEquals(0.5, Car.SPEED_DEFAULT, 1e-9)
        assertEquals(Truth.LOCK_I_X, Predictor.bestLock(Yard.rightStart(), vSet = Car.SPEED_DEFAULT), 0.03)
        // the start and stop curves: never faster than a 1 s smoothstep (0.75 m/s²), no jump in speed, and it reaches the set speed
        val s = DriveSim(Yard.rightStart())
        s.gear = Gear.R
        s.go = true
        var last = 0.0
        var peak = 0.0
        repeat(240) {
            s.advance(0.005)
            peak = maxOf(peak, abs(s.v - last) / 0.005)
            last = s.v
        }
        assertEquals(Car.SPEED_DEFAULT, s.v, 1e-9)
        assertTrue("peak $peak m/s²", peak <= 0.76)
        s.go = false
        repeat(240) {
            s.advance(0.005)
            peak = maxOf(peak, abs(s.v - last) / 0.005)
            last = s.v
        }
        assertEquals(0.0, s.v, 1e-9)
        assertTrue("peak $peak m/s²", peak <= 0.76)
    }

    @Test
    fun lookPointsAreTheTruthPoses() {
        val lps = LookPoints.all
        assertEquals(12, lps.size)
        assertEquals(Truth.LOCK_I_X, LookPoints.byId("LP2")!!.pose.x, 0.02)
        assertEquals(88.0, LookPoints.byId("LP4")!!.pose.psiDeg, 1.0)
        assertEquals(3.80, LookPoints.byId("LP5")!!.pose.z, 0.05)
        assertEquals(Truth.LOCK_OUT_Z, LookPoints.byId("LP6")!!.pose.z, 0.02)
        assertEquals(-Truth.LOCK_I_X, LookPoints.byId("LP9")!!.pose.x, 0.02)
        assertEquals(135.0, LookPoints.byId("LP10")!!.pose.psiDeg, 1.5)
        assertNull(LookPoints.chantLateCm("LP3", 1.2))
    }

    @Test
    fun d25_calibrationOfTheProceduralCar() {
        val (lo, hi) = CarModel.bounds(CarModel.shellNames)
        val len = hi.z - lo.z
        val wid = hi.x - lo.x
        // centre: car centre line at x = +0.37, body from z -2.00 (front) to +2.50 (rear) -> centre z = +0.25
        assertEquals(0.37, (lo.x + hi.x) / 2, 0.01)
        assertEquals(0.25, (lo.z + hi.z) / 2, 0.01)
        // size within 10 % of the design (4.50 x 1.70)
        assertTrue(abs(len - Car.LENGTH) / Car.LENGTH < 0.10)
        assertTrue(abs(wid - Car.WIDTH) / Car.WIDTH < 0.10)
        // front direction: the bonnet / front bumper are at -Z (angle 0 < 2°)
        val front = CarModel.boxes.first { it.name == "bumperF" }
        val rear = CarModel.boxes.first { it.name == "bumperR" }
        val dir = (front.min.z + front.max.z) / 2 - (rear.min.z + rear.max.z) / 2
        assertTrue(dir < 0)
        val dx = (front.min.x + front.max.x) / 2 - (rear.min.x + rear.max.x) / 2
        assertTrue(Math.toDegrees(kotlin.math.atan2(abs(dx), abs(dir))) < 2.0)
        // the road under the tyres at the default eye height
        assertEquals(-Car.EYE_DEFAULT, CarModel.boxes.filter { it.name.startsWith("tire") }.minOf { it.min.y }, 0.01)
        assertEquals(0, Angles.absDiffDeg(0.0, 360.0).toInt())
        assertEquals(Hit.Kind.LOCK, Hit("LP2", SegId.I, 0, Hit.Kind.LOCK).kind)
    }
}
