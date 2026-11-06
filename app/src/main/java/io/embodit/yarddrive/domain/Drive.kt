package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

enum class Gear { R, D }

/**
 * The car (design model.car.kinematics): single-track model, idle-speed creep, steering with ramps. Pure and
 * deterministic: the frame system, the predictor, the demo autopilot and the unit tests all run this one class.
 * The car pose is written here only (design ecs.rule).
 */
class DriveSim(start: Pose) {
    var x = start.x
    var z = start.z
    var psi = start.psi
    var gear = Gear.D

    /** Steering wheel angle, degrees, + right; ±540 = full lock. */
    var wheel = 0.0
        private set

    /** Where the wheel is being turned to (buttons / X key), or null. */
    var wheelTarget: Double? = null
        private set
    var wheelRate = Car.BUTTON_RATE
        private set

    /** Joystick rate, degrees per second (+ right). */
    var stickRate = 0.0

    /** Go / Stop (P2 key, A key: a tap each). */
    var go = false
    var vSet = Car.SPEED_DEFAULT

    /** Where the car is on its start / stop curve, 0..1. */
    private var ramp = 0.0

    /** Current speed magnitude, m/s (>= 0); the direction follows the gear. */
    var v = 0.0
        private set

    /** Distance left in the current step (step mode), m. */
    var stepLeft = 0.0
        private set

    /** Total distance driven forward / backward (for D-ORDER), m. */
    var odoForward = 0.0
    var odoBackward = 0.0
    var time = 0.0
        private set

    val pose: Pose get() = Pose(x, z, psi)
    val moving: Boolean get() = v > 1e-6
    val stepping: Boolean get() = stepLeft > 0

    fun teleport(p: Pose, wheelDeg: Double = 0.0) {
        x = p.x
        z = p.z
        psi = p.psi
        v = 0.0
        ramp = 0.0
        stepLeft = 0.0
        go = false
        wheel = wheelDeg
        wheelTarget = null
        stickRate = 0.0
    }

    /** Smoothly turn the wheel to [deg] at [rate] (buttons: 0.4 s from centre to full lock). */
    fun steerTo(deg: Double, rate: Double = Car.BUTTON_RATE) {
        wheelTarget = deg.coerceIn(-Car.WHEEL_MAX, Car.WHEEL_MAX)
        wheelRate = rate
    }

    /** The wheel at an absolute angle (joystick, debug). */
    fun setWheel(deg: Double) {
        wheel = deg.coerceIn(-Car.WHEEL_MAX, Car.WHEEL_MAX)
        wheelTarget = null
    }

    /** Gear change works only while the car is still (design T6); returns false (beep) otherwise. */
    fun shift(g: Gear): Boolean {
        if (moving || stepping) return gear == g
        gear = g
        return true
    }

    /** Step mode: one press moves 0.30 m and stops (start / stop ramps 0.15 s). */
    fun step(): Boolean {
        if (moving || stepping) return false
        stepLeft = Car.STEP_M
        return true
    }

    fun stopStep() {
        stepLeft = 0.0
    }

    /** Tests and replay: set the speed directly (no ramp). */
    fun forceSpeed(speed: Double) {
        v = speed
        // the point on the curve that gives this speed
        val f = (speed / vSet).coerceIn(0.0, 1.0)
        ramp = 0.5 - kotlin.math.sin(kotlin.math.asin(1 - 2 * f) / 3)
    }

    /** Integrates [dt] seconds in sub-steps of at most 5 ms. */
    fun advance(dt: Double) {
        var left = dt
        while (left > 1e-9) {
            val h = min(left, SUB)
            sub(h)
            left -= h
        }
    }

    private fun sub(h: Double) {
        time += h
        // steering
        val t = wheelTarget
        if (abs(stickRate) > 1e-6) {
            wheelTarget = null
            wheel = (wheel + stickRate * h).coerceIn(-Car.WHEEL_MAX, Car.WHEEL_MAX)
        } else if (t != null) {
            val d = t - wheel
            val m = wheelRate * h
            if (abs(d) <= m) {
                wheel = t
                wheelTarget = null
            } else wheel += sign(d) * m
        }
        // speed
        val dist: Double
        if (stepLeft > 0) {
            val acc = vSet / Car.STEP_RAMP_S
            val brake = v * v / (2 * acc)
            v = if (stepLeft <= brake) maxOf(STEP_CREEP, v - acc * h) else min(vSet, v + acc * h)
            dist = min(stepLeft, v * h)
            stepLeft -= dist
            if (stepLeft <= 1e-9) {
                stepLeft = 0.0
                v = 0.0
                ramp = 0.0
            }
        } else {
            // a smooth curve between stopped and rolling: no sudden start, no sudden stop (the user felt sick at 2.7 m/s²)
            ramp = if (go) min(1.0, ramp + h / Car.ACCEL_S) else maxOf(0.0, ramp - h / Car.DECEL_S)
            v = vSet * ramp * ramp * (3 - 2 * ramp)
            dist = v * h
        }
        if (dist <= 0) return
        val s = if (gear == Gear.R) -dist else dist
        if (s > 0) odoForward += dist else odoBackward += dist
        val delta = Math.toRadians(Car.delta(wheel))
        // midpoint heading for a better arc
        val dpsi = -s * tan(delta) / Car.WHEELBASE
        val mid = psi + dpsi / 2
        x += s * cos(mid)
        z += -s * sin(mid)
        psi += dpsi
    }

    fun copy(): DriveSim =
        DriveSim(pose).also {
            it.gear = gear
            it.wheel = wheel
            it.wheelTarget = wheelTarget
            it.wheelRate = wheelRate
            it.go = go
            it.vSet = vSet
            it.v = v
            it.ramp = ramp
            it.stepLeft = stepLeft
        }

    companion object {
        const val SUB = 0.005
        private const val STEP_CREEP = 0.03
    }
}
