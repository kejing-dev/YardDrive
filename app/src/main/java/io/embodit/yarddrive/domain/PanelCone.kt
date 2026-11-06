package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Design D20 "car parts that fall into the eye -> P1 / P2 line of sight fade to 25 %". Only the steering wheel can get
 * there: the dash top (y -0.38) stays below the bottom edge of P1 (y -0.37) and P2 (y -0.36) at the same depth, so no eye
 * above them can see it across a panel; the A pillars stay 5° outside P2 even with the head 10 cm to the side.
 * Everything here is in cabin coordinates (origin = the eye at the moment you were seated).
 */
object PanelCone {
    /** A panel placed from the seated eye, turned back to face it: [c] centre, half sizes in metres. */
    data class Rect(val c: Vec3, val halfW: Float, val halfH: Float) {
        val n: Vec3 get() = (Vec3.ZERO - c).normalized()
        val right: Vec3 get() = Vec3.UP.cross(n).normalized()
        val up: Vec3 get() = n.cross(right).normalized()
    }

    private const val M_PER_DP = 0.0008f
    private const val PANEL_SCALE = 0.8f

    fun p1(): Rect = Rect(Seat.p1Local(), 800f * M_PER_DP * PANEL_SCALE / 2, 600f * M_PER_DP * PANEL_SCALE / 2)

    fun p2(pitchDeg: Double): Rect = Rect(Seat.p2Local(pitchDeg), 1160f * M_PER_DP * PANEL_SCALE / 2, 240f * M_PER_DP * PANEL_SCALE / 2)

    /** Rim tube + the 12 o'clock mark: what sticks out of the centre-line circle, in the plane and along the column. */
    private const val RIM_OUT = 0.016
    private const val RIM_ALONG = 0.020

    /** Points of the wheel's silhouette (any wheel angle: the tube and the mark are taken all the way round). */
    val rim: List<Vec3> by lazy {
        val c = CarModel.WHEEL_CENTRE
        val n = CarModel.WHEEL_AXIS
        val u = V3(1.0, 0.0, 0.0)
        val v = n.cross(u).normalized()
        val out = ArrayList<Vec3>()
        val r = CarModel.WHEEL_R + RIM_OUT
        for (i in 0 until 72) {
            val a = 2 * Math.PI * i / 72
            val p = c + u * (r * cos(a)) + v * (r * sin(a))
            for (s in doubleArrayOf(-RIM_ALONG, RIM_ALONG)) {
                val q = p + n * s
                out += Vec3(q.x.toFloat(), q.y.toFloat(), q.z.toFloat())
            }
        }
        out
    }

    /** Elevation of the highest rim point seen straight ahead from [eye], degrees. */
    fun wheelTopDeg(eye: Vec3 = Vec3.ZERO): Double = rim.maxOf { p -> (p - eye).let { Math.toDegrees(kotlin.math.atan2(it.y.toDouble(), kotlin.math.hypot(it.x.toDouble(), it.z.toDouble()))) } }

    /**
     * Clear angle between the wheel and the panel's bottom edge as seen from [eye], degrees: + the wheel stays below the
     * edge by that much, - a rim point is over the panel. Large when the wheel is nowhere near (off to the side).
     */
    fun clearDeg(eye: Vec3, panel: Rect): Double {
        val n = panel.n
        val right = panel.right
        val up = panel.up
        var best = 90.0
        for (p in rim) {
            val d = p - eye
            val denom = d.dot(n)
            if (abs(denom) < 1e-5f) continue
            val t = (panel.c - eye).dot(n) / denom
            if (t < 1f) continue // the panel is nearer than this rim point: it cannot cover the panel
            val h = eye + d * t - panel.c
            val u = h.dot(right)
            val v = h.dot(up)
            if (abs(u) > panel.halfW + 0.02f) continue
            val edge = panel.c + right * u - up * panel.halfH - eye
            val ray = eye + d * t - eye
            val cosA = ray.normalized().dot(edge.normalized()).toDouble().coerceIn(-1.0, 1.0)
            val ang = Math.toDegrees(acos(cosA))
            val m = if (v < -panel.halfH) ang else -ang
            if (m < best) best = m
        }
        return best
    }

    /** Fades when closer than this (degrees), back when clearer than [CLEAR_DEG]; the gap keeps it from flickering. */
    const val NEAR_DEG = 1.5
    const val CLEAR_DEG = 2.5
    const val FADED = 0.25f
    private const val FADE_S = 0.2f

    /** The wheel's opacity over time (design D20): 1 = solid, [FADED] while it is in the line of sight of a panel. */
    class Fade {
        var opacity = 1f
            private set
        var faded = false
            private set

        fun step(clear: Double, dt: Float): Float {
            if (clear < NEAR_DEG) faded = true else if (clear > CLEAR_DEG) faded = false
            val want = if (faded) FADED else 1f
            val d = dt / FADE_S * (1f - FADED)
            opacity = if (abs(want - opacity) <= d) want else opacity + Math.signum(want - opacity) * d
            return opacity
        }
    }
}
