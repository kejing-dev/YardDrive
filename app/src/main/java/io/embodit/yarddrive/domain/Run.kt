package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class Rules { LESSON, EXAM }

enum class Mode { LESSON, MOCK, FREE }

/** Why a run did not pass (design judging.deductions; STOP = the gate-B "strict" switch: a mid-way stop fails). */
enum class Outcome { PASS, OUT, NOTIN, TIMEOUT, ORDER, STOP }

/** Phase of a run = design states S06 / S07 / S08 / S09 / S10 / S11. */
enum class RunPhase { READY, DRIVING, STOPPED, SEG_DONE, LINE, FINISHED }

data class RunConfig(
    val mode: Mode = Mode.LESSON,
    val lesson: Int = 6,
    val rules: Rules = Rules.LESSON,
    val lenient: Boolean = false,
    val strict: Boolean = false,
    val segments: List<SegId> = listOf(SegId.I, SegId.II, SegId.III, SegId.IV),
    val start: Pose = Yard.rightStart(),
    val startGear: Gear = Gear.D,
    val gap: Double = Yard.GAP_DEFAULT,
    val stepByStep: Boolean = false,
    val stepMode: Boolean = false,
    val timed: Boolean = false,
    val speed: Double = Car.SPEED_DEFAULT,
    val lockX: Double = Truth.LOCK_I_X,
    val seed: Int = 0,
    val difficulty: Int = 0,
    /** Free practice level 3: this run's seat (0..2); null = your own from the settings (profile D30). */
    val seat: Int? = null,
) {
    val extra: Double get() = if (lenient) Yard.LENIENT_EXTRA else 0.0
    val lane: Boolean get() = segments.firstOrNull()?.isLane == true

    /** Eye height of this run: its own seat if it has one, else the saved one. */
    fun eyeH(s: Settings): Double = seat?.let { Car.EYE_HEIGHTS[it.coerceIn(0, 2)] } ?: s.eyeH
}

/**
 * What the stop indicators show (profile D31): read from the judge, never a clock of their own. SETTLING / NOSE_OUT: the
 * car is where the segment ends and nothing is being charged. TIMER: a mid-way stop is running up to 2 s. PENALTY: this
 * stop has really been charged under test rules. NONE: nothing is being judged.
 */
data class StopView(val kind: Kind = Kind.NONE, val t: Double = 0.0, val left: Double = 0.0, val noseOutCm: Int = 0) {
    enum class Kind { NONE, SETTLING, NOSE_OUT, TIMER, TIMER_AMBER, PENALTY }
}

/** One look-point hit: + = late, - = early, in cm (design cueTiming: the moment you start the action). */
data class Hit(val lp: String, val seg: SegId, val lateCm: Int, val kind: Kind, val at: Double = 0.0) {
    enum class Kind { LOCK, STRAIGHTEN, STOP }

    /** Inside the design window (green <= 15, amber <= 30, red beyond; result page 2). */
    val band: Int get() = abs(lateCm).let { if (it <= 15) 0 else if (it <= 30) 1 else 2 }
}

/** A line touched: which line, which part of the body (design S10). */
data class Touch(val line: JudgeLine, val part: Part, val at: P2, val seg: SegId)

enum class Part(val zh: String, val en: String) {
    FRONT_RIGHT("右前角", "front right"),
    FRONT_LEFT("左前角", "front left"),
    REAR_LEFT("左后角", "rear left"),
    REAR_RIGHT("右后角", "rear right"),
    RIGHT_SIDE("右侧车身", "right side"),
    LEFT_SIDE("左侧车身", "left side"),
    NOSE("车头", "nose"),
    TAIL("车尾", "tail"),
}

/** Events for sound, glow and the panels; drained by the controller every frame. */
sealed class RunEvent {
    data class Cue(val lp: String, val seg: SegId) : RunEvent()
    data class AutoStop(val lp: String) : RunEvent()
    data class Touched(val touch: Touch, val outcome: Outcome) : RunEvent()
    data class MidStop(val count: Int) : RunEvent()
    data class SegDone(val seg: SegId) : RunEvent()
    data class NextSeg(val seg: SegId) : RunEvent()
    data object GearRefused : RunEvent()
    data object GearChanged : RunEvent()
    data class Finished(val outcome: Outcome) : RunEvent()
}

/** One 10 Hz sample for replay and the path page (design metrics). */
data class Sample(val t: Double, val x: Double, val z: Double, val psi: Double, val wheel: Double, val gear: Gear, val seg: Int, val look: Int)

/**
 * One practice run: the judge (design judging) on top of [DriveSim]. Exam rules end on the first fail; lesson rules
 * stop the car and offer "back to the last point" (S10). Every check runs in every phase that can reach it: the 210 s
 * limit in READY / DRIVING / STOPPED / SEG_DONE alike, the order rule in every segment, the end check on the last
 * segment too (factory lesson, dog-manners QA #1).
 */
class Run(val cfg: RunConfig) {
    val sim = DriveSim(cfg.start).also {
        it.gear = cfg.startGear
        it.vSet = cfg.speed
    }

    var segIndex = 0
        private set
    var phase = RunPhase.READY
        private set
    val seg: SegId get() = cfg.segments[segIndex.coerceAtMost(cfg.segments.size - 1)]
    val nextSeg: SegId? get() = cfg.segments.getOrNull(segIndex + 1)

    /** Seconds since the first move of segment 1 (frozen while paused or tracking is lost). */
    var elapsed = 0.0
        private set
    var started = false
        private set
    private var segMoved = false

    /** Seconds the car has been still in this stop. */
    var stoppedFor = 0.0
        private set
    private var stopCounted = false

    /** This stop has been charged as a mid-way stop (stays until the car moves again). */
    private var stopCharged = false
    var stops = 0
        private set
    var outcome: Outcome = Outcome.PASS
        private set
    var touch: Touch? = null
        private set
    var reason: Reason? = null
        private set
    var unscored = false
        private set
    var backs = 0
        private set
    val hits = ArrayList<Hit>()
    val segTime = DoubleArray(cfg.segments.size)
    val segOk = BooleanArray(cfg.segments.size)
    private var segStartElapsed = 0.0
    private var segStartPose = cfg.start
    private val lockDone = BooleanArray(cfg.segments.size)
    private val straightDone = BooleanArray(cfg.segments.size)
    private val fwdAtSegStart = DoubleArray(cfg.segments.size)
    private var cueFired = HashSet<String>()

    /** Min gaps of the reverse-in segments (design metrics): inner bay corner, far bay line. */
    val cornerGap = HashMap<SegId, Double>()
    val lineGap = HashMap<SegId, Double>()
    val nose = HashMap<SegId, Double>()
    val past = HashMap<SegId, Double>()

    /** Lesson 2: did the wheel reach full lock, and come back to centre after it? */
    var sawLock = false
        private set
    var sawCentre = false
        private set
    var touches = 0
        private set

    /** Last look-point pose reached (design "back to the last point"). */
    var lastPoint: Pair<String, Pose>? = null
        private set
    private var lastPointWheel = 0.0
    private var lastPointGear = cfg.startGear
    private var touching = HashSet<JudgeLine>()
    val trail = ArrayList<Sample>()
    private var trailClock = 0.0
    val events = ArrayList<RunEvent>()

    /** Latest look-ahead (fed by the controller every 0.2 s, by tests directly). */
    var prediction: Predictor.Result? = null
        set(v) {
            field = v
            val settled = sim.wheelTarget == null && kotlin.math.abs(sim.wheel) >= Car.WHEEL_MAX - 1
            noWayStreak = if (v != null && settled && !v.anyOk && v.hold.out) noWayStreak + 1 else 0
        }

    /** Consecutive "no branch gets in" predictions at full lock (two in a row stop a lesson run, design T9). */
    var noWayStreak = 0
        private set

    /** The turning rate the predictor assumes (°/s). */
    var userRate = Car.BUTTON_RATE

    /** Where you looked (gaze classes, 10 Hz counts), set by the controller. */
    val looks = IntArray(Look.entries.size)
    var look: Look = Look.AHEAD

    /** Debug and replay: hold the car without judging. */
    var frozen = false

    /** Step mode (S23): B / "one step" moves 0.3 m; stops do not count. Toggled from P2 under lesson rules. */
    var stepMode = cfg.stepMode

    private val lines: Map<JudgeLine, Seg2> = if (cfg.lane) Yard.laneLines(cfg.gap) else Yard.judgeLines(cfg.extra)

    init {
        lastPoint = (cfg.segments.first().let { if (it == SegId.I) "LP1" else it.lockLp }) to cfg.start
        trail += sample()
    }

    val pose: Pose get() = sim.pose
    val lockX: Double get() = cfg.lockX
    val rules: Rules get() = cfg.rules

    /** Mid-way stops cost points under test rules only; lesson rules note them (design lessonMode, profile D31). */
    val score: Int get() = if (cfg.rules == Rules.EXAM) 100 - 5 * stops else 100
    val passed: Boolean get() = phase == RunPhase.FINISHED && outcome == Outcome.PASS && score >= 80 && !unscored
    val wrongGear: Boolean get() = phase == RunPhase.READY && sim.gear != seg.gear

    /** The stop ring and the "settled" line read this (profile D31). */
    val stopView: StopView
        get() {
            if (phase != RunPhase.STOPPED || stepMode || stoppedFor <= 0.05) return StopView()
            if (Truth.atEnd(seg, pose, segStartPose)) {
                val n = Truth.nose(pose)
                return if (seg.bayIn && n < 0) StopView(StopView.Kind.NOSE_OUT, stoppedFor, (NOTIN_SETTLE_S - stoppedFor).coerceAtLeast(0.0), (-n * 100).roundToInt())
                else StopView(StopView.Kind.SETTLING, stoppedFor, (SETTLE_S - stoppedFor).coerceAtLeast(0.0))
            }
            if (stopCharged && cfg.rules == Rules.EXAM) return StopView(StopView.Kind.PENALTY, stoppedFor)
            // a stop that is not being timed (just after "back to the last point", "carry on", the next segment)
            if (stopCounted && !stopCharged) return StopView()
            return StopView(if (stoppedFor >= 1.0) StopView.Kind.TIMER_AMBER else StopView.Kind.TIMER, stoppedFor)
        }

    // ------------------------------------------------------------------ inputs (the user, the autopilot and debug commands all use these)

    fun setGo(on: Boolean) {
        if (phase == RunPhase.LINE || phase == RunPhase.FINISHED) {
            sim.go = false
            return
        }
        sim.go = on
    }

    fun step(): Boolean = if (phase == RunPhase.LINE || phase == RunPhase.FINISHED || !stepMode) false else sim.step()

    fun shift(g: Gear): Boolean {
        if (phase == RunPhase.FINISHED) return false
        val was = sim.gear
        val ok = sim.shift(g)
        if (!ok) events += RunEvent.GearRefused else if (was != g) events += RunEvent.GearChanged
        if (ok) onGearForNext()
        return ok
    }

    /** P2 buttons / X key: lock or centre, 0.4 s ramp. The action time is the press (design U13). */
    fun steerButton(target: Double) {
        if (phase == RunPhase.FINISHED) return
        noteAction(target)
        sim.steerTo(target, Car.BUTTON_RATE)
    }

    /** The wheel set directly (joystick, debug): the action time is when the wheel leaves the dead zone. */
    fun handWheel(deg: Double) {
        val before = sim.wheel
        sim.setWheel(deg)
        detectManual(before, sim.wheel)
    }

    private fun detectManual(before: Double, after: Double) {
        if (abs(before) <= Car.WHEEL_DEAD && abs(after) > Car.WHEEL_DEAD) noteAction(if (after > 0) Car.WHEEL_MAX else -Car.WHEEL_MAX)
        if (abs(before) >= Car.WHEEL_MAX - Car.WHEEL_DEAD && abs(after) < abs(before) - 1.0 && abs(after) < Car.WHEEL_MAX - Car.WHEEL_DEAD) noteAction(0.0)
    }

    private fun noteAction(target: Double) {
        if (phase == RunPhase.FINISHED || phase == RunPhase.LINE || cfg.lane) {
            if (cfg.lane) {
                if (abs(target) >= Car.WHEEL_MAX - 1) sawLock = true
                if (target == 0.0 && sawLock) sawCentre = true
            }
            return
        }
        val i = segIndex
        val s = seg
        val p = pose
        if (s.isLane) return
        if (abs(target) > 1 && target * s.lockSign > 0 && !lockDone[i]) {
            lockDone[i] = true
            val truth = Truth.lockAt(s, cfg.lockX)
            val late =
                when (s) {
                    SegId.I -> truth - p.x
                    SegId.III -> p.x - truth
                    else -> truth - p.z
                }
            hits += Hit(s.lockLp, s, (late * 100).roundToInt(), Hit.Kind.LOCK, elapsed)
        } else if (abs(target) < 1 && lockDone[i] && !straightDone[i]) {
            straightDone[i] = true
            val err = when (s) {
                SegId.I, SegId.II -> p.psiDeg - Truth.straightenAt(s, sim.vSet)
                else -> Truth.straightenAt(s, sim.vSet) - Angles.wrapDeg(p.psiDeg)
            }
            hits += Hit(s.straightLp, s, (Math.toRadians(err) * Car.R_FULL * 100).roundToInt(), Hit.Kind.STRAIGHTEN, elapsed)
        }
    }

    // ------------------------------------------------------------------ lesson-rule recovery (S10 buttons)

    /** "Back to the last point": fade the car to the last look-point pose, phase S08 (design T12). */
    fun backToPoint() {
        val (_, p) = lastPoint ?: return
        sim.teleport(p, lastPointWheel)
        sim.gear = lastPointGear
        touching.clear()
        touch = null
        reason = null
        backs++
        phase = RunPhase.STOPPED
        stoppedFor = 0.0
        stopCounted = true
        // the fail is forgiven (lesson pass allows going back); hits of this segment after that point are redone
        val i = segIndex
        if (lastPoint!!.first == seg.lockLp || lastPoint!!.first == "LP1") {
            lockDone[i] = false
            straightDone[i] = false
            hits.removeAll { it.seg == seg }
        }
        if (lastPoint!!.first == seg.straightLp) {
            straightDone[i] = false
            hits.removeAll { it.seg == seg && it.kind != Hit.Kind.LOCK }
        }
        cueFired.removeAll { it.startsWith("$i:") }
        prediction = null
    }

    /** "Carry on (unscored)". */
    fun carryOn() {
        if (phase != RunPhase.LINE) return
        unscored = true
        phase = RunPhase.STOPPED
        stoppedFor = 0.0
        stopCounted = true
    }

    // ------------------------------------------------------------------ the clock

    /**
     * Advances the run by [dt]. [paused] and [lost] freeze the car and the clock (design S18 / S19: neither counts as a
     * mid-way stop).
     */
    fun tick(dt: Double, paused: Boolean = false, lost: Boolean = false) {
        if (phase == RunPhase.FINISHED || frozen) return
        if (paused || lost) {
            sim.go = false
            return
        }
        if (phase == RunPhase.LINE) {
            sim.go = false
            sim.advance(dt)
            return
        }
        val wheelBefore = sim.wheel
        sim.advance(dt)
        if (sim.stickRate != 0.0) detectManual(wheelBefore, sim.wheel)
        if (cfg.lane) {
            if (abs(sim.wheel) >= Car.WHEEL_MAX - 1) sawLock = true
            if (sawLock && abs(sim.wheel) < 1) sawCentre = true
        }
        val moving = sim.moving
        if (moving && !started && segIndex == 0) started = true
        if (started) elapsed += dt
        if (moving) {
            if (phase == RunPhase.SEG_DONE) advanceSegment()
            segMoved = true
            phase = RunPhase.DRIVING
            stoppedFor = 0.0
            stopCounted = false
            stopCharged = false
        }
        trailClock += dt
        if (trailClock >= 0.1) {
            trailClock = 0.0
            trail += sample()
            looks[look.ordinal]++
        }
        judgeLines()
        if (phase == RunPhase.LINE || phase == RunPhase.FINISHED) return
        if (!cfg.lane) {
            judgeOrder()
            if (phase == RunPhase.LINE || phase == RunPhase.FINISHED) return
            trackGaps()
            cues()
            if (phase == RunPhase.LINE || phase == RunPhase.FINISHED) return
        }
        if (!moving && !sim.stepping && segMoved && phase != RunPhase.SEG_DONE) {
            phase = RunPhase.STOPPED
            stoppedFor += dt
            val atEnd = Truth.atEnd(seg, pose, segStartPose)
            val noseOut = seg.bayIn && Truth.nose(pose) < 0
            if (atEnd && stoppedFor >= (if (noseOut) NOTIN_SETTLE_S else SETTLE_S) && !sim.go) segmentDone()
            else if (!atEnd && stoppedFor > MID_STOP_S && !stopCounted && !stepMode) {
                stopCounted = true
                stopCharged = true
                stops++
                events += RunEvent.MidStop(stops)
                if (cfg.strict && cfg.rules == Rules.EXAM) fail(Outcome.STOP, null)
            }
        }
        if (phase != RunPhase.FINISHED) checkTime()
    }

    private fun checkTime() {
        if (cfg.timed && cfg.rules == Rules.EXAM && elapsed > LIMIT_S) fail(Outcome.TIMEOUT, null)
    }

    private fun sample() = Sample(elapsed, sim.x, sim.z, sim.psi, sim.wheel, sim.gear, segIndex, look.ordinal)

    private fun judgeLines() {
        val poly = pose.corners()
        val now = HashSet<JudgeLine>()
        for ((id, l) in lines) if (Geo.over(poly, l.a, l.b)) now += id
        val fresh = now - touching
        touching = now
        if (fresh.isEmpty()) return
        val id = fresh.first()
        val l = lines[id]!!
        val at = Geo.touchPoint(poly, l.a, l.b)
        val t = Touch(id, partAt(at), at, seg)
        touches++
        if (cfg.lane && cfg.rules == Rules.LESSON) {
            touch = t
            events += RunEvent.Touched(t, Outcome.OUT)
            enterLine(t, Outcome.OUT)
            return
        }
        fail(Outcome.OUT, t)
    }

    /** Which part of the body is at [p] (yard). */
    fun partAt(p: P2): Part {
        val q = pose
        val d = p - q.rear
        val a = d.dot(q.f)
        val b = d.dot(q.r)
        val nearFront = a > Car.FRONT - 0.45
        val nearRear = a < -Car.REAR + 0.45
        val right = b > 0
        return when {
            nearFront && abs(b) > Car.HALF_W - 0.35 -> if (right) Part.FRONT_RIGHT else Part.FRONT_LEFT
            nearRear && abs(b) > Car.HALF_W - 0.35 -> if (right) Part.REAR_RIGHT else Part.REAR_LEFT
            nearFront -> Part.NOSE
            nearRear -> Part.TAIL
            else -> if (right) Part.RIGHT_SIDE else Part.LEFT_SIDE
        }
    }

    private fun judgeOrder() {
        val s = seg
        val i = segIndex
        val p = pose
        val d = p.psiDeg
        val wrongWay =
            when (s) {
                SegId.II -> segMoved && d < 45.0
                SegId.IV -> segMoved && Angles.wrapDeg(d) > 135.0
                else -> false
            }
        // reverse-in: driving forward more than 0.5 m outside the bay before backing in (design D-ORDER, this app's rule)
        val forwardOut = s.bayIn && sim.odoForward - fwdAtSegStart[i] > ORDER_FORWARD_M && p.z < 0.0
        if (wrongWay || forwardOut) {
            if (forwardOut) fwdAtSegStart[i] = sim.odoForward
            fail(Outcome.ORDER, null)
        }
    }

    private fun trackGaps() {
        val s = seg
        if (!s.bayIn) return
        val poly = pose.corners()
        val c = Truth.innerCorner(s, cfg.extra)
        val far = Truth.farLine(s, cfg.extra)
        cornerGap[s] = min(cornerGap[s] ?: 9.0, Geo.pointPoly(c, poly))
        lineGap[s] = min(lineGap[s] ?: 9.0, Geo.polySeg(poly, far.a, far.b))
    }

    /** Cue triggers (design cueTiming): the lock, the straighten and the stop points; step-by-step stops there. */
    private fun cues() {
        val s = seg
        val i = segIndex
        val p = pose
        val brake = if (cfg.stepByStep) Truth.brake(sim.vSet) else 0.0
        fun fire(lp: String, at: Pose) {
            val key = "$i:$lp"
            if (key in cueFired) return
            cueFired += key
            events += RunEvent.Cue(lp, s)
            lastPoint = lp to at
            lastPointWheel = sim.wheel
            lastPointGear = sim.gear
            if (cfg.stepByStep && sim.moving) {
                sim.go = false
                events += RunEvent.AutoStop(lp)
            }
        }
        if (!lockDone[i]) {
            if (Truth.toLock(s, p, cfg.lockX) <= brake && sim.gear == s.gear) fire(s.lockLp, p)
        } else if (!straightDone[i]) {
            val brakeDeg = if (cfg.stepByStep) Math.toDegrees(brake / Car.R_FULL) else 0.0
            if (Truth.toStraighten(s, p, sim.vSet) <= brakeDeg) fire(s.straightLp, p)
            // lesson rules: the look-ahead says no branch gets in -> stop now, before the line (design T9)
            val pr = prediction
            if (pr != null && s.bayIn && cfg.rules == Rules.LESSON && pr.seg == s && noWayStreak >= 2 && Truth.toStraighten(s, p) > 10) {
                reason = Reason.lockReason(this, s, noWay = true)
                enterLine(null, Outcome.OUT)
            }
        } else {
            if (Truth.toStop(s, p) <= Truth.brake(sim.vSet) && sim.moving) fire(s.stopLp, p)
        }
    }

    private fun fail(o: Outcome, t: Touch?) {
        if (t != null) touch = t
        if (cfg.rules == Rules.EXAM) {
            outcome = o
            reason = if (o == Outcome.OUT && t != null) Reason.lockReason(this, t.seg, touch = t) else null
            sim.go = false
            phase = RunPhase.FINISHED
            if (t != null) events += RunEvent.Touched(t, o)
            events += RunEvent.Finished(o)
            return
        }
        if (o == Outcome.TIMEOUT || o == Outcome.STOP) return
        if (t != null) events += RunEvent.Touched(t, o)
        reason = Reason.lockReason(this, seg, touch = t, outcome = o)
        enterLine(t, o)
    }

    private fun enterLine(t: Touch?, o: Outcome) {
        if (t != null) touch = t
        if (reason == null) reason = Reason.lockReason(this, seg, touch = t, outcome = o)
        lineOutcome = o
        sim.go = false
        sim.teleport(pose, sim.wheel)
        phase = RunPhase.LINE
    }

    /** What S10 is about (lesson rules). */
    var lineOutcome: Outcome = Outcome.OUT
        private set

    private fun segmentDone() {
        val s = seg
        val i = segIndex
        val p = pose
        segTime[i] = elapsed - segStartElapsed
        if (s.bayIn) {
            val n = Truth.nose(p)
            nose[s] = n
            if (n < 0) {
                if (cfg.rules == Rules.EXAM) {
                    fail(Outcome.NOTIN, null)
                    return
                }
                reason = Reason(zh = String.format("车头还在库外 %d cm，再往里倒一点", (-n * 100).roundToInt()), en = String.format("Nose still %d cm outside. Back in a bit more", (-n * 100).roundToInt()))
                enterLine(null, Outcome.NOTIN)
                return
            }
            hits += Hit(s.stopLp, s, ((n - Truth.NOSE_TARGET) * 100).roundToInt(), Hit.Kind.STOP, elapsed)
        } else if (!s.isLane) {
            val pc = Truth.pastControl(s, p)
            past[s] = pc
            hits += Hit(s.stopLp, s, ((pc - Truth.PAST_TARGET) * 100).roundToInt(), Hit.Kind.STOP, elapsed)
        }
        segOk[i] = true
        events += RunEvent.SegDone(s)
        if (segIndex >= cfg.segments.size - 1) {
            checkTime()
            if (phase == RunPhase.FINISHED) return
            outcome = if (cfg.rules == Rules.EXAM && cfg.strict && stops > 0) Outcome.STOP else Outcome.PASS
            phase = RunPhase.FINISHED
            events += RunEvent.Finished(outcome)
            return
        }
        phase = RunPhase.SEG_DONE
    }

    /** S09 -> S06: the next segment starts once you shift (or move off). */
    fun onGearForNext() {
        if (phase == RunPhase.SEG_DONE && nextSeg != null && sim.gear == nextSeg!!.gear) advanceSegment()
    }

    private fun advanceSegment() {
        if (phase != RunPhase.SEG_DONE) return
        segIndex++
        segStartElapsed = elapsed
        segStartPose = pose
        segMoved = sim.moving
        fwdAtSegStart[segIndex] = sim.odoForward
        stoppedFor = 0.0
        stopCounted = true
        touching.clear()
        prediction = null
        lastPoint = seg.lockLp.let { if (it.isEmpty()) "LP1" else it } to pose
        lastPointWheel = sim.wheel
        lastPointGear = sim.gear
        phase = if (sim.moving) RunPhase.DRIVING else RunPhase.READY
        events += RunEvent.NextSeg(seg)
    }

    /** Debug / library: put the car at [p] (and a wheel angle) without judging the jump. */
    fun place(p: Pose, wheel: Double = sim.wheel) {
        sim.teleport(p, wheel)
        touching = HashSet<JudgeLine>().also { set -> val poly = p.corners(); for ((id, l) in lines) if (Geo.over(poly, l.a, l.b)) set += id }
        trail += sample()
    }

    /** Debug: jump to segment [index] (the earlier ones count as done), car in the ready state. */
    fun jumpToSegment(index: Int, p: Pose) {
        for (k in 0 until index) segOk[k] = true
        segIndex = index.coerceIn(0, cfg.segments.size - 1)
        segStartPose = p
        segMoved = false
        fwdAtSegStart[segIndex] = sim.odoForward
        place(p, 0.0)
        phase = RunPhase.READY
        lastPoint = (if (index == 0) "LP1" else seg.lockLp) to p
    }

    /** Debug: pretend the car has driven and stopped (S08 at the current pose). */
    fun markMoved() {
        segMoved = true
        started = true
    }

    /** Debug (QA screenshots): hold the run in [p] with [stoppedS] on the stop ring and [midStops] counted. */
    fun debugHold(p: RunPhase, stoppedS: Double = 0.0, midStops: Int = stops, elapsedS: Double = elapsed) {
        markMoved()
        phase = p
        stoppedFor = stoppedS
        stopCharged = midStops > stops && stoppedS >= MID_STOP_S
        stopCounted = stopCharged
        stops = midStops
        elapsed = elapsedS
        frozen = true
    }

    companion object {
        const val LIMIT_S = 210.0
        const val MID_STOP_S = 2.0
        const val SETTLE_S = 1.0

        /** Stopped in the bay with the nose still out: a hesitation for 3 s, then judged (not in). */
        const val NOTIN_SETTLE_S = 3.0
        const val ORDER_FORWARD_M = 0.5
    }
}

/** "Where you looked" classes (design judging.lookRecording). */
enum class Look(val zh: String, val en: String) {
    AHEAD("前方", "Ahead"),
    MIRROR_L("左镜", "L mirror"),
    MIRROR_R("右镜", "R mirror"),
    MIRROR_I("内镜", "Inside"),
    REAR("后窗", "Rear"),
    WINDOW_L("左窗", "L window"),
    WINDOW_R("右窗", "R window"),
    OTHER("其他", "Other"),
}

/** The one-sentence reason of S10 / the result card (copy.reason: "{cue}{earlyLate} {cm} cm，{effect}"). */
data class Reason(val zh: String, val en: String) {
    companion object {
        fun lockReason(run: Run, s: SegId, touch: Touch? = null, outcome: Outcome = Outcome.OUT, noWay: Boolean = false): Reason? {
            if (outcome == Outcome.ORDER) {
                return if (s == SegId.II || s == SegId.IV) Reason(if (s == SegId.II) "这段该往左出" else "这段该往右出", if (s == SegId.II) "This one goes out to the left" else "This one goes out to the right")
                else Reason("库外别往前开，直接倒", "Don't pull forward out here. Just back in")
            }
            val hit = run.hits.lastOrNull { it.seg == s && it.kind == Hit.Kind.LOCK }
            val cueZh = if (s.lockSign > 0) "右打死" else "左打死"
            val cueEn = if (s.lockSign > 0) "Full lock right" else "Full lock left"
            if (hit != null && abs(hit.lateCm) >= 3 && s.bayIn) {
                val early = hit.lateCm < 0
                val cm = abs(hit.lateCm)
                val corner = if (s == SegId.III) "右后角" else "左后角"
                val cornerEn = if (s == SegId.III) "rear right" else "rear left"
                val effZh = if (early) "库角太近了" else "${corner}压库角"
                val effEn = if (early) "corner got too close" else "$cornerEn clips the corner"
                return Reason("${cueZh}${if (early) "早了" else "晚了"} $cm cm，$effZh", "$cueEn $cm cm ${if (early) "early" else "late"}, $effEn")
            }
            if (noWay) return Reason("打晚了，左后角会压库角".let { if (s == SegId.III) "打晚了，右后角会压库角" else it }, if (s == SegId.III) "Too late. Rear right will clip the corner" else "Too late. Rear left will clip the corner")
            return null
        }
    }
}
