package io.embodit.yarddrive.domain

import kotlin.math.roundToInt

/** One look-point (design yard.lookPoints): where the car is, which mirror to look at, which segment it belongs to. */
data class LookPoint(val id: String, val n: Int, val seg: SegId, val pose: Pose, val wheel: Double, val gear: Gear, val look: Look)

/**
 * The twelve look-points. Their poses are the design's truth poses, taken from one run of the ideal script through the
 * judge (so library teleports, "back to the last point" and the cues agree by construction).
 */
object LookPoints {
    val all: List<LookPoint> by lazy { capture() }

    fun byId(id: String): LookPoint? = all.firstOrNull { it.id == id }

    fun byN(n: Int): LookPoint = all[(n - 1).coerceIn(0, all.size - 1)]

    private val looks =
        mapOf(
            "LP1" to Look.WINDOW_L, "LP2" to Look.MIRROR_L, "LP3" to Look.MIRROR_R, "LP4" to Look.MIRROR_L, "LP5" to Look.MIRROR_L, "LP6" to Look.AHEAD,
            "LP7" to Look.AHEAD, "LP8" to Look.WINDOW_L, "LP9" to Look.MIRROR_R, "LP10" to Look.MIRROR_L, "LP11" to Look.MIRROR_R, "LP12" to Look.AHEAD,
        )

    private fun capture(): List<LookPoint> {
        val run = Run(RunConfig(mode = Mode.MOCK, rules = Rules.EXAM))
        val ap = Autopilot()
        val got = LinkedHashMap<String, LookPoint>()
        fun put(id: String, seg: SegId) {
            if (id in got) return
            got[id] = LookPoint(id, id.removePrefix("LP").toInt(), seg, run.pose, run.sim.wheel, run.sim.gear, looks[id] ?: Look.AHEAD)
        }
        got["LP1"] = LookPoint("LP1", 1, SegId.I, Yard.rightStart(), 0.0, Gear.R, Look.WINDOW_L)
        var t = 0.0
        while (t < 120 && run.phase != RunPhase.FINISHED) {
            ap.drive(run, 0.01)
            run.tick(0.01)
            t += 0.01
            for (e in run.events) {
                when (e) {
                    is RunEvent.Cue -> if (e.lp != "LP5" && e.lp != "LP8") put(e.lp, e.seg)
                    is RunEvent.SegDone -> when (e.seg) {
                        SegId.I -> put("LP5", SegId.I)
                        SegId.II -> put("LP8", SegId.II)
                        else -> Unit
                    }
                    else -> Unit
                }
            }
            run.events.clear()
            val d = run.pose.psiDeg
            if (run.seg == SegId.I && d >= 45 && "LP3" !in got) put("LP3", SegId.I)
            if (run.seg == SegId.III && d <= 135 && "LP10" !in got) put("LP10", SegId.III)
        }
        return (1..12).mapNotNull { got["LP$it"] }
    }

    /**
     * How far off the chant is for this seat (design sightlines, U8 / U9): + = the chant would be late. Null for the
     * points whose chant does not depend on the eye height.
     */
    fun chantLateCm(id: String, eyeH: Double): Int? =
        when (id) {
            "LP2" -> ((Truth.LOCK_I_X - chantLockX(eyeH)) * 100).roundToInt()
            "LP9" -> ((Truth.LOCK_I_X - chantLockX(eyeH)) * 100).roundToInt()
            "LP5" -> ((chantNose(eyeH) - Truth.NOSE_TARGET) * 100).roundToInt()
            "LP6", "LP12" -> ((Truth.LOCK_OUT_Z - chantOutZ(eyeH)) * 100).roundToInt()
            "LP8" -> ((chantPast() - Truth.PAST_TARGET) * 100).roundToInt()
            else -> null
        }

    /** LP8 chant: the mirror shell level with the control line -> front axle past it (0.53 m, inside the window). */
    fun chantPast(): Double = (Car.WHEELBASE - Car.EYE_AHEAD_OF_REAR) - -Car.MIRROR_EDGE_L.z

    /** LP2 chant: the control line meets the ground point under the left mirror shell -> rear axle x. */
    fun chantLockX(eyeH: Double): Double = Yard.CONTROL_X - Car.EYE_AHEAD_OF_REAR - Car.mirrorGround(eyeH).first

    /** LP6 chant: the bonnet edge hides the road edge -> rear axle z. */
    fun chantOutZ(eyeH: Double): Double = Yard.ROAD_Z + Car.EYE_AHEAD_OF_REAR + Car.hoodGround(eyeH)

    /** LP5 chant: the mirror shell's ground point meets the bay front line -> nose past the line. */
    fun chantNose(eyeH: Double): Double = Car.mirrorGround(eyeH).first + Car.EYE_AHEAD_OF_REAR - Car.FRONT
}

/**
 * "Where you looked" (design judging.lookRecording): the gaze (or, without eye tracking, the head direction) in the
 * cabin frame, against the three mirrors (50 % larger than drawn), the rear window, the side windows and the windscreen.
 * Recording only; never part of the judge (B27).
 */
object Gaze {
    fun classify(eye: V3, dir: V3): Look {
        val d = dir.normalized()
        for (m in Mirrors.all) {
            val to = m.pos - eye
            val dist = to.length
            val r = kotlin.math.atan(kotlin.math.hypot(m.wM, m.hM) * 0.75 / dist)
            val ang = kotlin.math.acos((to.normalized().dot(d)).coerceIn(-1.0, 1.0))
            if (ang <= kotlin.math.max(r, Math.toRadians(6.0))) {
                return when (m.id) { Mirrors.Id.L -> Look.MIRROR_L; Mirrors.Id.R -> Look.MIRROR_R; Mirrors.Id.I -> Look.MIRROR_I }
            }
        }
        return when {
            d.z > 0.3 && kotlin.math.abs(d.x) < 0.9 -> Look.REAR
            d.x < -0.55 -> Look.WINDOW_L
            d.x > 0.55 -> Look.WINDOW_R
            d.z < -0.6 -> Look.AHEAD
            else -> Look.OTHER
        }
    }
}
