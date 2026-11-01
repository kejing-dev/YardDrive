package io.embodit.yarddrive.domain

import kotlin.math.min

/**
 * The design's ideal script (model.yard.idealScript) as a driver: it presses the same inputs a person does (shift, hold
 * Go, the lock / centre buttons), so the demo S05, the debug `script=…` commands and the U4 test all go through the
 * judge exactly like a user (factory rule: no shortcut state writes).
 *
 * [lockEarly] moves the lock point of the reverse-in segments (+ = early, metres), for `script=early:0.15` / `late:0.2`.
 * [followAdvice] makes it act on the look-ahead (ease off half a turn, lock again), for U5.
 */
class Autopilot(private val lockEarly: Double = 0.0, private val followAdvice: Boolean = false, private val waitS: Double = 0.4) {
    private var segIndex = -1
    private var locked = false
    private var straight = false
    private var stopped = false
    private var clock = 0.0
    private var easeUntil = -1.0
    private var eased = false
    var finishedAt: Double? = null
        private set

    fun drive(run: Run, dt: Double) {
        clock += dt
        if (run.segIndex != segIndex) {
            segIndex = run.segIndex
            locked = false
            straight = false
            stopped = false
            eased = false
            easeUntil = -1.0
            clock = 0.0
        }
        val s = run.seg
        when (run.phase) {
            RunPhase.FINISHED -> {
                if (finishedAt == null) finishedAt = run.elapsed
                run.setGo(false)
                return
            }
            RunPhase.LINE -> return
            RunPhase.SEG_DONE -> {
                val next = run.nextSeg ?: return
                if (run.sim.gear != next.gear) run.shift(next.gear)
                return
            }
            RunPhase.READY -> {
                if (run.sim.gear != s.gear) run.shift(s.gear)
                if (clock >= waitS) run.setGo(true)
                return
            }
            else -> Unit
        }
        if (stopped) return
        val p = run.pose
        val sign = s.lockSign
        if (s.isLane) {
            val done = Truth.atEnd(s, p, if (s == SegId.LANE1) run.cfg.start else run.cfg.start)
            if (done) {
                run.setGo(false)
                stopped = true
            } else run.setGo(true)
            return
        }
        if (!locked) {
            val early = if (s.bayIn) lockEarly else 0.0
            if (Truth.toLock(s, p, run.lockX) <= early) {
                run.steerButton(sign * Car.WHEEL_MAX)
                locked = true
            }
        } else if (!straight) {
            if (followAdvice && s.bayIn) {
                val pr = run.prediction
                if (!eased && pr != null && pr.atLock && pr.advice == Predictor.Advice.EASE) {
                    val b = pr.ease.filter { it.ok }.maxByOrNull { min(it.gapCorner, it.gapLine) }!!
                    run.steerButton(sign * Car.WHEEL_MAX * 2 / 3)
                    easeUntil = clock + b.dt
                    eased = true
                }
                if (easeUntil > 0 && clock >= easeUntil) {
                    run.steerButton(sign * Car.WHEEL_MAX)
                    easeUntil = -1.0
                }
            }
            if (Truth.toStraighten(s, p) <= 0) {
                run.steerButton(0.0)
                straight = true
            }
        } else if (Truth.toStop(s, p) <= Truth.brake(run.sim.vSet)) {
            run.setGo(false)
            stopped = true
        }
        if (!stopped) run.setGo(true)
    }

    companion object {
        /**
         * The ideal script on a bare [DriveSim] for one reverse-in segment, locking at [x0] (mirrored for Ⅲ): returns
         * (inner corner gap, far line gap, crossed a line).
         */
        fun scriptGaps(start: Pose, seg: SegId, x0: Double, extra: Double = 0.0, vSet: Double = Car.SPEED_DEFAULT): Triple<Double, Double, Boolean> {
            val s = DriveSim(start)
            s.gear = Gear.R
            s.vSet = vSet
            s.go = true
            val lines = Yard.judgeLines(extra).values.toList()
            val corner = Truth.innerCorner(seg, extra)
            val far = Truth.farLine(seg, extra)
            var gapC = 9.0
            var gapL = 9.0
            var out = false
            var phase = 0
            var t = 0.0
            val sign = seg.lockSign
            while (t < 60.0) {
                val p = s.pose
                if (phase == 0 && Truth.toLock(seg, p, x0) <= 0) {
                    s.steerTo(sign * Car.WHEEL_MAX)
                    phase = 1
                }
                if (phase == 1 && Truth.toStraighten(seg, p) <= 0) {
                    s.steerTo(0.0)
                    phase = 2
                }
                if (phase == 2 && Truth.toStop(seg, p) <= Truth.brake(vSet)) {
                    s.go = false
                    phase = 3
                }
                s.advance(0.01)
                t += 0.01
                if (phase >= 1) {
                    val poly = s.pose.corners()
                    gapC = min(gapC, Geo.pointPoly(corner, poly))
                    gapL = min(gapL, Geo.polySeg(poly, far.a, far.b))
                    for (l in lines) if (Geo.over(poly, l.a, l.b)) out = true
                }
                if (phase == 3 && !s.moving) break
            }
            return Triple(gapC, gapL, out)
        }
    }
}
