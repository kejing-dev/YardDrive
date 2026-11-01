package io.embodit.yarddrive.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The exam car (design model.car). All lengths in metres. Cabin frame: origin = your eye E0, -Z = car front, +X = your
 * right, +Y up; the road is at y = -eyeHeight. Yard frame: origin = middle of the bay front line, +X east, +Z south
 * (into the bay), +Y up.
 */
object Car {
    const val LENGTH = 4.50
    const val WIDTH = 1.70
    const val HEIGHT = 1.45
    const val WHEELBASE = 2.60
    const val FRONT_OVERHANG = 0.85
    const val REAR_OVERHANG = 1.05
    const val HALF_W = 0.85

    /** Rear axle to the front bumper. */
    const val FRONT = WHEELBASE + FRONT_OVERHANG

    /** Rear axle to the rear bumper. */
    const val REAR = REAR_OVERHANG

    /** Your eye is 1.45 m ahead of the rear axle and 0.37 m left of the centre line. */
    const val EYE_AHEAD_OF_REAR = 1.45
    const val EYE_LEFT_OF_CENTRE = 0.37

    const val RATIO = 14.2
    const val WHEEL_MAX = 540.0
    const val DELTA_MAX = 38.0
    const val BUTTON_RATE = 1350.0
    const val MANUAL_RATE = 450.0
    const val STICK_RATE = 540.0
    const val STICK_DEAD = 0.15
    const val WHEEL_DEAD = 30.0

    const val SPEED_DEFAULT = 0.8
    const val SPEED_MIN = 0.5
    const val SPEED_MAX = 1.2
    const val ACCEL_S = 0.3
    const val DECEL_S = 0.2
    const val STEP_M = 0.30
    const val STEP_RAMP_S = 0.15

    /** Seat heights low / mid / high = eye above the road (design settings). */
    val EYE_HEIGHTS = doubleArrayOf(1.12, 1.20, 1.28)
    const val EYE_DEFAULT = 1.20

    /** Front wheel angle for a steering wheel angle, degrees (+ right), limited to the full lock. */
    fun delta(wheelDeg: Double): Double = (wheelDeg / RATIO).coerceIn(-DELTA_MAX, DELTA_MAX)

    /** Turning radius of the rear axle centre at full lock (3.33 m). */
    val R_FULL: Double = WHEELBASE / kotlin.math.tan(Math.toRadians(DELTA_MAX))

    // ---- direct sight lines (design model.sightlines, U8 / U9): eye -> point on the car -> road

    /** Lower edge of the left mirror housing relative to the eye. */
    val MIRROR_EDGE_L = V3(-0.70, -0.255, -0.62)
    val MIRROR_EDGE_R = V3(1.44, -0.255, -0.62)

    /** Front edge of the bonnet relative to the eye. */
    val HOOD_EDGE = V3(0.0, -0.45, -2.00)

    /** Where the ray eye -> [p] meets the road: (ahead m, lateral m, + right). */
    fun groundHit(p: V3, eyeH: Double): Pair<Double, Double> {
        val k = eyeH / -p.y
        return -p.z * k to p.x * k
    }

    fun mirrorGround(eyeH: Double, left: Boolean = true): Pair<Double, Double> = groundHit(if (left) MIRROR_EDGE_L else MIRROR_EDGE_R, eyeH)

    fun hoodGround(eyeH: Double): Double = groundHit(HOOD_EDGE, eyeH).first
}

/** Plain double vector (domain maths; the scene converts to floats). */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = kotlin.math.sqrt(dot(this))
    fun normalized(): V3 = length.let { if (it < 1e-12) this else this * (1 / it) }
    fun f(): Vec3 = Vec3(x.toFloat(), y.toFloat(), z.toFloat())
}

/** A 2D point on the yard (x east, z south). */
data class P2(val x: Double, val z: Double) {
    operator fun plus(o: P2) = P2(x + o.x, z + o.z)
    operator fun minus(o: P2) = P2(x - o.x, z - o.z)
    operator fun times(s: Double) = P2(x * s, z * s)
    fun dot(o: P2) = x * o.x + z * o.z
    val length: Double get() = hypot(x, z)
}

/**
 * Car pose: rear axle centre (x, z) on the yard and heading [psi] (radians): f = (cos psi, -sin psi); psi = 0 faces
 * east, 90° faces north (towards the lane), 180° west. The right side vector is r = (-f.z, f.x).
 */
data class Pose(val x: Double, val z: Double, val psi: Double) {
    val f: P2 get() = P2(cos(psi), -sin(psi))
    val r: P2 get() = P2(sin(psi), cos(psi))
    val psiDeg: Double get() = Math.toDegrees(psi)
    val rear: P2 get() = P2(x, z)

    /** Body corners: front-right, front-left, rear-left, rear-right. */
    fun corners(halfW: Double = Car.HALF_W): List<P2> {
        val f = f
        val r = r
        fun p(a: Double, b: Double) = P2(x + a * f.x + b * r.x, z + a * f.z + b * r.z)
        return listOf(p(Car.FRONT, halfW), p(Car.FRONT, -halfW), p(-Car.REAR, -halfW), p(-Car.REAR, halfW))
    }

    val frontAxle: P2 get() = P2(x + Car.WHEELBASE * f.x, z + Car.WHEELBASE * f.z)

    /** Your eye on the yard (x, z). */
    val eye: P2 get() = P2(x + Car.EYE_AHEAD_OF_REAR * f.x - Car.EYE_LEFT_OF_CENTRE * r.x, z + Car.EYE_AHEAD_OF_REAR * f.z - Car.EYE_LEFT_OF_CENTRE * r.z)

    /** A yard point (x, height, z) in the cabin frame for this pose and eye height. */
    fun toCabin(px: Double, py: Double, pz: Double, eyeH: Double): V3 {
        val e = eye
        val d = P2(px - e.x, pz - e.z)
        return V3(d.dot(r), py - eyeH, -d.dot(f))
    }

    /** A cabin point back to the yard (x, height, z). */
    fun toYard(c: V3, eyeH: Double): V3 {
        val e = eye
        val f = f
        val r = r
        val ahead = -c.z
        return V3(e.x + c.x * r.x + ahead * f.x, c.y + eyeH, e.z + c.x * r.z + ahead * f.z)
    }

    /**
     * The yardRoot transform for this pose (cabin space = R * yard + t): a rotation about +Y by [yardYaw] (pi/2 - psi,
     * right-handed: x' = x cos + z sin, z' = -x sin + z cos) and the translation of the yard origin. No reflection (D10).
     */
    val yardYaw: Double get() = PI / 2 - psi

    fun yardOrigin(eyeH: Double): V3 = toCabin(0.0, 0.0, 0.0, eyeH)

    fun withPsiDeg(d: Double) = copy(psi = Math.toRadians(d))

    override fun toString(): String = String.format(java.util.Locale.US, "(%.3f, %.3f, %.1f°)", x, z, psiDeg)

    companion object {
        fun deg(x: Double, z: Double, psiDeg: Double) = Pose(x, z, Math.toRadians(psiDeg))
    }
}

object Angles {
    fun wrapDeg(a: Double): Double {
        var d = a % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** Signed angle from [from] to [to] (radians, -pi..pi). */
    fun diff(to: Double, from: Double): Double = atan2(sin(to - from), cos(to - from))

    fun absDiffDeg(a: Double, b: Double): Double = abs(wrapDeg(a - b))
}
