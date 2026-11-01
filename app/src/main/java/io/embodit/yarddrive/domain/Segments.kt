package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The four exam segments (design yard.sequence) and the two lesson-2 lane legs. Every number here is a design-package
 * truth value (model.yard.idealScript / lookPoints), not recomputed.
 */
enum class SegId(val gear: Gear, val bayIn: Boolean, val targetDeg: Double, val zh: String, val en: String) {
    I(Gear.R, true, 90.0, "Ⅰ 右倒库", "I Reverse in, right"),
    II(Gear.D, false, 180.0, "Ⅱ 左出库", "II Pull out, left"),
    III(Gear.R, true, 90.0, "Ⅲ 左倒库", "III Reverse in, left"),
    IV(Gear.D, false, 0.0, "Ⅳ 右出库", "IV Pull out, right"),
    LANE1(Gear.R, false, 0.0, "① 倒 6 m", "① Reverse 6 m"),
    LANE2(Gear.D, false, 0.0, "② 回起点", "② Back to start");

    val isLane: Boolean get() = this == LANE1 || this == LANE2

    /** + = the lock this segment needs is to the right. */
    val lockSign: Double
        get() = when (this) {
            I, IV -> 1.0
            II, III -> -1.0
            else -> 0.0
        }

    /** The LP ids of this segment's lock / straighten / stop points (design lookPoints). */
    val lockLp: String get() = when (this) { I -> "LP2"; II -> "LP6"; III -> "LP9"; IV -> "LP12"; else -> "" }
    val straightLp: String get() = when (this) { I -> "LP4"; II -> "LP7"; III -> "LP11"; IV -> "LP7"; else -> "" }
    val stopLp: String get() = when (this) { I, III -> "LP5"; II, IV -> "LP8"; else -> "" }
    val cornerLp: String get() = when (this) { I -> "LP3"; III -> "LP10"; else -> "" }
}

object Truth {
    /** Ⅰ: reverse straight to rear axle x = 3.52 then full lock right (button ramp 0.4 s), window [3.37, 3.69]. */
    const val LOCK_I_X = 3.52
    const val LOCK_I_MIN = 3.37
    const val LOCK_I_MAX = 3.69

    /** Ⅱ / Ⅳ: forward to rear axle z = 0.85 then full lock (button ramp). */
    const val LOCK_OUT_Z = 0.85

    /** Straighten starts 2° early (the 0.4 s ramp adds about 2.7°). */
    const val STRAIGHTEN_EARLY_DEG = 2.0

    /** Stop in the bay: rear axle z = 3.80, nose 0.35 past the front line; window nose [0.10, 0.60]. */
    const val STOP_REAR_Z = 3.80
    const val NOSE_TARGET = 0.35
    const val NOSE_MIN = 0.10
    const val NOSE_MAX = 0.60

    /** Stop at the control line: front axle 0.30 past it; window [0.10, 0.60]. */
    const val PAST_TARGET = 0.30
    const val PAST_MIN = 0.10
    const val PAST_MAX = 0.60

    /** Braking distance from the set speed (0.2 s linear ramp). */
    fun brake(v: Double) = v * Car.DECEL_S / 2

    fun lockAt(seg: SegId, lockX: Double = LOCK_I_X): Double =
        when (seg) {
            SegId.I -> lockX
            SegId.III -> -lockX
            SegId.II, SegId.IV -> LOCK_OUT_Z
            else -> 0.0
        }

    fun straightenAt(seg: SegId): Double =
        when (seg) {
            SegId.I -> 90.0 - STRAIGHTEN_EARLY_DEG
            SegId.II -> 180.0 - STRAIGHTEN_EARLY_DEG
            SegId.III -> 90.0 + STRAIGHTEN_EARLY_DEG
            SegId.IV -> 0.0 + STRAIGHTEN_EARLY_DEG
            else -> 0.0
        }

    /** Metres left before the lock point (+ = not there yet). */
    fun toLock(seg: SegId, p: Pose, lockX: Double): Double =
        when (seg) {
            SegId.I -> p.x - lockX
            SegId.III -> -lockX - p.x
            SegId.II, SegId.IV -> p.z - LOCK_OUT_Z
            else -> 99.0
        }

    /** Degrees left before the straighten point (+ = not there yet). */
    fun toStraighten(seg: SegId, p: Pose): Double {
        val d = p.psiDeg
        return when (seg) {
            SegId.I -> straightenAt(seg) - d
            SegId.II -> straightenAt(seg) - d
            SegId.III -> d - straightenAt(seg)
            SegId.IV -> Angles.wrapDeg(d) - straightenAt(seg)
            else -> 99.0
        }
    }

    /** Nose of the car: the most northern body point (smallest z). */
    fun nose(p: Pose): Double = p.corners().minOf { it.z }

    fun tail(p: Pose): Double = p.corners().maxOf { it.z }

    /** Front axle past the control line the segment ends at (+ = past). */
    fun pastControl(seg: SegId, p: Pose): Double =
        when (seg) {
            SegId.II -> -Yard.CONTROL_X - p.frontAxle.x
            SegId.IV -> p.frontAxle.x - Yard.CONTROL_X
            else -> 0.0
        }

    /** Metres left before the stop point (+ = not there yet). */
    fun toStop(seg: SegId, p: Pose): Double =
        when (seg) {
            SegId.I, SegId.III -> STOP_REAR_Z - p.z
            SegId.II, SegId.IV -> PAST_TARGET - pastControl(seg, p)
            else -> 99.0
        }

    /** Is the car where this segment ends (stopped >= 1 s there = segment done, design T11)? */
    fun atEnd(seg: SegId, p: Pose, start: Pose): Boolean {
        val d = p.psiDeg
        return when (seg) {
            SegId.I, SegId.III -> p.z >= 1.5 && abs(p.x) < 1.6 && Angles.absDiffDeg(d, 90.0) < 20
            SegId.II -> pastControl(seg, p) >= 0 && Angles.absDiffDeg(d, 180.0) < 20
            SegId.IV -> pastControl(seg, p) >= 0 && Angles.absDiffDeg(d, 0.0) < 20
            SegId.LANE1 -> p.x <= start.x - 6.0 + LANE_SLACK
            SegId.LANE2 -> p.x >= start.x - LANE_SLACK
        }
    }

    /** Lesson 2 accepts 5.7 m as "6 m" and back within 0.3 m of the start (lenient, profile D27). */
    const val LANE_SLACK = 0.3

    /** Inner bay front corner of a reverse-in segment (the one in the mirror, LP3 / LP10). */
    fun innerCorner(seg: SegId, extra: Double): P2 = if (seg == SegId.III) P2(-Yard.BAY_HALF - extra / 2, 0.0) else P2(Yard.BAY_HALF + extra / 2, 0.0)

    /** The far bay line of a reverse-in segment (left for Ⅰ, right for Ⅲ). */
    fun farLine(seg: SegId, extra: Double): Seg2 {
        val l = Yard.judgeLines(extra)
        return if (seg == SegId.III) l[JudgeLine.BAY_RIGHT]!! else l[JudgeLine.BAY_LEFT]!!
    }

    fun clamp01(v: Double) = max(0.0, min(1.0, v))
}
