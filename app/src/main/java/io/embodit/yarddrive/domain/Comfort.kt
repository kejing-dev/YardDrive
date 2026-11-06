package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.tan

/** The comfort vignette (user, 2026-11-05: "very dizzy"). It answers how fast the world turns, not only how fast the car goes. */
object Comfort {
    /** Half strength rolling straight; full when the world turns this fast (full lock at the default speed is 8.6 °/s). */
    const val STRAIGHT = 0.5f
    const val FULL_YAW_DEG_S = 8.0

    fun yawDegS(sim: DriveSim): Double = Math.toDegrees(sim.v * tan(Math.toRadians(Car.delta(sim.wheel))) / Car.WHEELBASE)

    /** Vignette strength 0..1 for this speed and turning rate. */
    fun vignette(v: Double, vSet: Double, yawDegS: Double): Float {
        val rolling = (v / vSet.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f)
        val turning = (abs(yawDegS) / FULL_YAW_DEG_S).toFloat().coerceIn(0f, 1f)
        return maxOf(STRAIGHT * rolling, turning)
    }
}
