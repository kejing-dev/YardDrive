package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * Look-ahead (design model.yard.predictor): from the current pose, gear and wheel, runs the same [DriveSim] (ramps
 * included) to the end of the segment along four fixed branches: (1) hold the wheel to the target heading, then
 * straighten; (2) full lock now; (3) straighten now; (4) ease off to half a turn for 0.5 / 1.0 / 1.5 / 2.0 s, then full
 * lock. Each branch reports the gaps, the end pose, whether the body crossed a judged line, and whether the target
 * heading can be reached at all (it cannot when the heading is moving away from it, U17).
 */
object Predictor {
    const val STEP = 0.01
    const val MAX_T = 60.0
    const val OK_GAP = 0.10
    val HOLD_DTS = doubleArrayOf(0.5, 1.0, 1.5, 2.0)

    enum class Kind { HOLD, LOCK, CENTRE, EASE }

    data class Branch(
        val kind: Kind,
        val dt: Double,
        val reachable: Boolean,
        val out: Boolean,
        val gapCorner: Double,
        val gapLine: Double,
        val end: Pose,
        val path: List<P2>,
    ) {
        val ok: Boolean get() = reachable && !out && gapCorner >= OK_GAP && gapLine >= OK_GAP
    }

    data class Result(val seg: SegId, val hold: Branch, val lock: Branch, val centre: Branch, val ease: List<Branch>, val wheel: Double = 0.0, val settled: Boolean = false) {
        /** Made with the wheel resting at full lock (the LP3 / LP10 advice only means something then). */
        val atLock: Boolean get() = settled && kotlin.math.abs(wheel) >= Car.WHEEL_MAX - 1

        val all: List<Branch> get() = listOf(hold, lock, centre) + ease

        /** The advice for LP3 / LP10 (design cueTiming): keep going, ease off, lock again, or "not this time". */
        val advice: Advice
            get() =
                when {
                    hold.ok -> Advice.KEEP
                    ease.any { it.ok } && hold.gapCorner < hold.gapLine -> Advice.EASE
                    lock.ok -> Advice.LOCK_AGAIN
                    else -> Advice.NO_WAY
                }

        val anyOk: Boolean get() = all.any { it.ok }
    }

    enum class Advice { KEEP, EASE, LOCK_AGAIN, NO_WAY }

    /** [rate] is the user's turning rate (buttons 1350°/s). */
    fun predict(from: DriveSim, seg: SegId, extra: Double, rate: Double = Car.BUTTON_RATE, keepPath: Boolean = false): Result {
        val sign = seg.lockSign
        val hold = run(from, seg, extra, Kind.HOLD, 0.0, keepPath) { _, _ -> }
        val lock = run(from, seg, extra, Kind.LOCK, 0.0, false) { s, t -> if (t == 0.0) s.steerTo(sign * Car.WHEEL_MAX, rate) }
        val centre = run(from, seg, extra, Kind.CENTRE, 0.0, false) { s, t -> if (t == 0.0) s.steerTo(0.0, rate) }
        val ease =
            HOLD_DTS.map { dt ->
                var relocked = false
                run(from, seg, extra, Kind.EASE, dt, false) { s, t ->
                    if (t == 0.0) s.steerTo(sign * Car.WHEEL_MAX * 2 / 3, rate)
                    if (!relocked && t >= dt - 1e-9) {
                        relocked = true
                        s.steerTo(sign * Car.WHEEL_MAX, rate)
                    }
                }
            }
        return Result(seg, hold, lock, centre, ease, from.wheel, from.wheelTarget == null)
    }

    /** Runs one branch: [plan] gets the sim and the branch time (called every step until the auto-straighten). */
    fun run(from: DriveSim, seg: SegId, extra: Double, kind: Kind, dt: Double, keepPath: Boolean, plan: (DriveSim, Double) -> Unit): Branch {
        val s = from.copy()
        s.stickRate = 0.0
        s.go = true
        val lines = Yard.judgeLines(extra).values.toList()
        val corner = Truth.innerCorner(seg, extra)
        val far = Truth.farLine(seg, extra)
        val target = seg.targetDeg
        var t = 0.0
        var straightened = false
        var out = false
        var gapC = Double.MAX_VALUE
        var gapL = Double.MAX_VALUE
        var reachable = true
        var lastErr = abs(Angles.wrapDeg(s.pose.psiDeg - target))
        var lastCheck = 0.0
        val path = if (keepPath) ArrayList<P2>() else null
        var tPlanTick = 0.0
        while (t < MAX_T) {
            if (!straightened) {
                plan(s, t)
                val err = abs(Angles.wrapDeg(s.pose.psiDeg - target))
                if (err <= Truth.straightenEarly(s.vSet)) {
                    s.steerTo(0.0, Car.BUTTON_RATE)
                    straightened = true
                } else if (t - lastCheck >= 1.0) {
                    if (err > lastErr - 0.5) {
                        reachable = false
                        break
                    }
                    lastErr = err
                    lastCheck = t
                }
            }
            s.advance(STEP)
            t += STEP
            tPlanTick += STEP
            val poly = s.pose.corners()
            for (l in lines) if (Geo.over(poly, l.a, l.b)) out = true
            if (seg.bayIn) {
                gapC = min(gapC, Geo.pointPoly(corner, poly))
                gapL = min(gapL, Geo.polySeg(poly, far.a, far.b))
            }
            if (path != null && tPlanTick >= 0.1) {
                tPlanTick = 0.0
                path += s.pose.rear
            }
            if (straightened && Truth.toStop(seg, s.pose) <= 0) break
        }
        if (!seg.bayIn) {
            gapC = 9.0
            gapL = 9.0
        }
        return Branch(kind, dt, reachable, out, gapC, gapL, s.pose, path ?: emptyList())
    }

    /**
     * The lock point that centres the car in the bay for a given start (free practice, U11): searched with the same
     * branch runs (coarse 5 cm, then 1 cm), maximising the smaller of the two gaps.
     */
    fun bestLock(start: Pose, seg: SegId = SegId.I, extra: Double = 0.0, vSet: Double = Car.SPEED_DEFAULT): Double {
        fun score(x0: Double): Double = Autopilot.scriptGaps(start, seg, x0, extra, vSet).let { (c, l, o) -> if (o) -1.0 else min(c, l) }
        var best = Truth.LOCK_I_X
        var bestS = -2.0
        var x = 3.0
        while (x <= 4.2001) {
            val sc = score(x)
            if (sc > bestS) {
                bestS = sc
                best = x
            }
            x += 0.05
        }
        var lo = best - 0.05
        val hi = best + 0.05
        while (lo <= hi + 1e-9) {
            val sc = score(lo)
            if (sc > bestS) {
                bestS = sc
                best = lo
            }
            lo += 0.01
        }
        return best
    }
}
