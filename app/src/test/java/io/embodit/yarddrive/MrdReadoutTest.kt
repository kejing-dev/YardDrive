package io.embodit.yarddrive

import io.embodit.yarddrive.domain.Autopilot
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.MrdReadout
import io.embodit.yarddrive.domain.Predictor
import io.embodit.yarddrive.domain.Run
import io.embodit.yarddrive.domain.RunConfig
import io.embodit.yarddrive.domain.RunPhase
import io.embodit.yarddrive.domain.Rules
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.domain.Yard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/** Profile D38, critic4-B1: MRD and HUB read the same state; no fixed "centred". Every look-point, both panels. */
class MrdReadoutTest {
    private fun placed(n: Int): Run {
        val lp = LookPoints.byN(n)
        val r = Run(RunConfig(mode = Mode.LESSON, segments = listOf(lp.seg), start = lp.pose, startGear = lp.gear))
        r.place(lp.pose, lp.wheel)
        r.frozen = true
        return r
    }

    /** What the wheel says, worked out here from the angle, not through the readout. */
    private fun expected(w: Double, zh: Boolean): String {
        val t = String.format(Locale.US, "%.1f", abs(w) / 360)
        return when {
            abs(w) < 15 -> if (zh) "回正" else "Centred"
            w < 0 -> if (zh) "左 $t 圈" else "L $t turns"
            else -> if (zh) "右 $t 圈" else "R $t turns"
        }
    }

    @Test
    fun everyPointReadsItsTruth() {
        for (n in 1..12) for (left in listOf(true, false)) {
            val lp = LookPoints.byN(n)
            val r = placed(n)
            for (eyeH in Car.EYE_HEIGHTS) {
                val c = MrdReadout.library(lp, left, eyeH, r.sim.wheel)
                assertEquals(lp.id, lp.wheel, c.wheel, 1e-9)
                val chant = LookPoints.chantLateCm(lp.id, eyeH)
                assertEquals(lp.id, chant, c.chantCm)
                if (chant != null) continue
                for (zh in listOf(true, false)) assertEquals(lp.id, expected(lp.wheel, zh), MrdReadout.turnsText(c.wheel, zh))
                val mine = if (left) lp.seg == SegId.III else lp.seg == SegId.I
                val truth = Predictor.predict(r.sim.copy(), lp.seg, 0.0).hold.gapCorner.takeIf { mine && it < 8 }
                if (truth == null) assertNull(lp.id, c.gap) else assertEquals(lp.id, truth, c.gap!!, 1e-9)
            }
        }
    }

    /** The design's sentences: "the MRD reading is how wide that fist is" (LP3), the corner in the left mirror (LP10). */
    @Test
    fun theCornerPointsHaveAGap() {
        val eye = Car.EYE_HEIGHTS[1]
        val lp3 = MrdReadout.library(LookPoints.byId("LP3")!!, false, eye, 540.0)
        assertNotNull(lp3.gap)
        assertTrue("LP3 ${lp3.gap}", lp3.gap!! in 0.10..0.30)
        assertNull(MrdReadout.library(LookPoints.byId("LP3")!!, true, eye, 540.0).gap)
        val lp10 = MrdReadout.library(LookPoints.byId("LP10")!!, true, eye, -540.0)
        assertNotNull(lp10.gap)
        assertTrue("LP10 ${lp10.gap}", lp10.gap!! in 0.10..0.30)
        assertNull(MrdReadout.library(LookPoints.byId("LP10")!!, false, eye, -540.0).gap)
        assertNull(MrdReadout.library(LookPoints.byId("LP7")!!, true, eye, -540.0).gap)
        assertNull(MrdReadout.library(LookPoints.byId("LP7")!!, false, eye, -540.0).gap)
    }

    @Test
    fun sameWordsAsTheHub() {
        assertEquals("右 1.5 圈", MrdReadout.turnsText(540.0, true))
        assertEquals("R 1.5 turns", MrdReadout.turnsText(540.0, false))
        assertEquals("左 1.5 圈", MrdReadout.turnsText(-540.0, true))
        assertEquals("回正", MrdReadout.turnsText(14.0, true))
        assertEquals("Centred", MrdReadout.turnsText(-14.0, false))
        assertEquals("右 0.0 圈", MrdReadout.turnsText(15.0, true))
    }

    private fun reading(side: SegId, r: Run): Double? {
        val pr = Predictor.predict(r.sim.copy(), r.seg, r.cfg.extra)
        return MrdReadout.cornerGap(side, r.cornerGap[side], r.seg, pr.seg, pr.hold.gapCorner)
    }

    private fun lesson(seg: SegId) = RunConfig(mode = Mode.LESSON, lesson = 6, rules = Rules.LESSON, segments = listOf(seg), start = if (seg == SegId.I) Yard.rightStart() else Yard.leftStart())

    /** QA #4 q4-2, profile D38: the same drive on the two sides reads the same at every moment, poses mirrored. */
    @Test
    fun mirroredDrivesReadTheSame() {
        for (early in listOf(0.0, 0.04, 0.2, -0.2)) {
            val a = Run(lesson(SegId.I))
            val b = Run(lesson(SegId.III))
            val pa = Autopilot(lockEarly = early)
            val pb = Autopilot(lockEarly = early)
            var t = 0.0
            var n = 0
            while (t < 40 && a.phase != RunPhase.FINISHED && a.phase != RunPhase.LINE) {
                pa.drive(a, 0.01)
                pb.drive(b, 0.01)
                a.tick(0.01)
                b.tick(0.01)
                t += 0.01
                if (++n % 25 != 0) continue
                assertEquals(a.pose.rear.x, -b.pose.rear.x, 1e-6)
                assertEquals(a.pose.rear.z, b.pose.rear.z, 1e-6)
                assertEquals(a.pose.psiDeg, 180 - b.pose.psiDeg, 1e-6)
                assertEquals(a.sim.wheel, -b.sim.wheel, 1e-6)
                val ga = reading(SegId.I, a)
                val gb = reading(SegId.III, b)
                assertEquals("t $t", ga == null, gb == null)
                if (ga != null) assertEquals("early $early t $t", ga, gb!!, 1e-6)
                assertEquals(MrdReadout.turnsText(a.sim.wheel, true).replace('右', '左'), MrdReadout.turnsText(b.sim.wheel, true))
            }
            assertTrue(n > 100)
        }
    }

    /** The same audit found P2's column reading the right side's number in segment IV, which comes out of the left bay. */
    @Test
    fun p2ReadsTheSegmentsOwnSide() {
        assertEquals(0.2, MrdReadout.topViewOffGap(SegId.I, 0.5, 0.2)!!, 1e-9)
        assertEquals(0.2, MrdReadout.topViewOffGap(SegId.II, 0.5, 0.2)!!, 1e-9)
        assertEquals(0.5, MrdReadout.topViewOffGap(SegId.III, 0.5, 0.2)!!, 1e-9)
        assertEquals(0.5, MrdReadout.topViewOffGap(SegId.IV, 0.5, 0.2)!!, 1e-9)
    }

    /** One function: the same numbers give the same answer whichever side it is asked for. */
    @Test
    fun bothSidesAreOneFunction() {
        for (seen in listOf(null, 1.72, 0.05)) for (hold in listOf(null, 0.0, 0.2, 9.0)) {
            val r = MrdReadout.cornerGap(SegId.I, seen, SegId.I, SegId.I, hold)
            assertEquals(r, MrdReadout.cornerGap(SegId.III, seen, SegId.III, SegId.III, hold))
            // QA's picture: seen 1.72 m, the look-ahead says it won't fit
            if (seen == 1.72 && hold == 0.0) assertEquals(0.0, r!!, 1e-9)
            // after its segment: what you saw
            assertEquals(seen, MrdReadout.cornerGap(SegId.III, seen, SegId.IV, SegId.IV, hold))
        }
    }

    /** "This one won't go in / the corner got too close": the MRD on that side says the same, not 1.72 m. */
    @Test
    fun tooCloseIsSaidOnBothPanels() {
        for (seg in listOf(SegId.I, SegId.III)) for (early in listOf(0.15, 0.2, 0.25, 0.3, 0.4)) {
            val r = Run(lesson(seg))
            val ap = Autopilot(lockEarly = early)
            var t = 0.0
            var said = false
            while (t < 40 && r.phase != RunPhase.FINISHED && r.phase != RunPhase.LINE) {
                ap.drive(r, 0.01)
                r.tick(0.01)
                t += 0.01
                val pr = if (r.sim.wheel.let { abs(it) >= Car.WHEEL_MAX - 1 }) Predictor.predict(r.sim.copy(), seg, 0.0) else continue
                if (pr.advice == Predictor.Advice.NO_WAY) {
                    said = true
                    val g = MrdReadout.cornerGap(seg, r.cornerGap[seg], seg, pr.seg, pr.hold.gapCorner)!!
                    assertTrue("$seg early $early t $t gap $g", g < Predictor.OK_GAP)
                    break
                }
            }
            assertTrue("$seg early $early never said it", said)
            assertTrue(reading(seg, r)!! < Predictor.OK_GAP)
        }
    }

    @Test
    fun drivingKeepsItsRules() {
        val d = MrdReadout.driving(false, SegId.I, Mode.LESSON, 0.5, 0.2, 540.0)
        assertEquals(0.2, d.gap!!, 1e-9)
        assertNull(MrdReadout.driving(true, SegId.I, Mode.LESSON, 0.5, 0.2, 540.0).gap)
        assertEquals(0.5, MrdReadout.driving(true, SegId.III, Mode.LESSON, 0.5, 0.2, -540.0).gap!!, 1e-9)
        assertNull("no corner gap in the mock exam", MrdReadout.driving(false, SegId.I, Mode.MOCK, 0.5, 0.2, 540.0).gap)
    }
}
