package io.embodit.yarddrive.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The three mirrors (design model.mirrors): an equivalent curved mirror. A virtual camera sits at the reflection E' of
 * your current eye in the mirror plane (so head moves give parallax), looks along the fixed aim with a fixed field of view,
 * and its picture, flipped left-right like a mirror, fills the mirror rectangle. It draws only geometry: the painted
 * lines, the corner poles and your own car's side, wheel arch and bumper outline. Not used by the judge (B27).
 */
/**
 * Everything that turns with a mirror gets its normal here, in the same call: the mirror face, its frame plate, its bar
 * (profile D35, critic5-B1). [apply] is the scene; [step] once a frame. [reset] after anything else placed them.
 */
class MirrorFollower(private val apply: (Mirrors.Spec, V3) -> Unit) {
    private var aim: Pair<Int, Int>? = null

    fun step(outDeg: Int, pitchDeg: Int) {
        val now = outDeg to pitchDeg
        if (now == aim) return
        aim = now
        for (spec in Mirrors.all) apply(spec, Mirrors.normal(spec, Mirrors.aim(spec, outDeg.toDouble(), pitchDeg.toDouble())))
    }

    fun reset() {
        aim = null
    }
}

object Mirrors {
    enum class Id { L, R, I }

    data class Spec(val id: Id, val pos: V3, val wM: Double, val hM: Double, val fovH: Double, val fovV: Double)

    val L = Spec(Id.L, V3(-0.70, -0.185, -0.62), 0.18, 0.11, 33.0, 20.0)
    val R = Spec(Id.R, V3(1.44, -0.185, -0.62), 0.18, 0.11, 33.0, 20.0)
    val I = Spec(Id.I, V3(0.33, 0.12, -0.48), 0.26, 0.07, 40.0, 11.0)
    val all = listOf(L, R, I)

    /** Aim (cabin) for the side mirrors: back, [outDeg] outwards, [pitchDeg] (negative = down). The inside mirror: back, -3°. */
    fun aim(spec: Spec, outDeg: Double, pitchDeg: Double): V3 {
        if (spec.id == Id.I) return V3(0.0, -sin(Math.toRadians(3.0)), cos(Math.toRadians(3.0)))
        val o = Math.toRadians(outDeg) * if (spec.id == Id.L) -1 else 1
        val p = Math.toRadians(pitchDeg)
        return V3(sin(o) * cos(p), sin(p), cos(o) * cos(p))
    }

    /** Mirror normal n = normalize(toEye + aim), from the design eye E0 (the mirror is fixed to the car). */
    fun normal(spec: Spec, aim: V3): V3 = ((V3(0.0, 0.0, 0.0) - spec.pos).normalized() + aim).normalized()

    data class Camera(val eye: V3, val fwd: V3, val right: V3, val up: V3, val tx: Double, val ty: Double) {
        /** Panel coordinates 0..1 (x right, y down, as the eye sees the mirror), or null behind the camera. */
        fun project(p: V3): Pair<Double, Double>? {
            val d = p - eye
            val zc = d.dot(fwd)
            if (zc < 0.05) return null
            val u = d.dot(right) / zc / tx
            val v = d.dot(up) / zc / ty
            return (0.5 - 0.5 * u) to (0.5 - 0.5 * v)
        }

        /** Where the horizon crosses the picture (0..1 from the top). */
        val horizon: Double
            get() {
                val pitch = kotlin.math.asin(fwd.y.coerceIn(-1.0, 1.0))
                return 0.5 - 0.5 * (tan(-pitch) / ty)
            }
    }

    fun camera(spec: Spec, eye: V3, outDeg: Double, pitchDeg: Double): Camera {
        val a = aim(spec, outDeg, pitchDeg)
        val n = normal(spec, a)
        val e = eye - n * (2 * (eye - spec.pos).dot(n))
        val right = a.cross(V3(0.0, 1.0, 0.0)).normalized()
        val up = right.cross(a).normalized()
        return Camera(e, a, right, up, tan(Math.toRadians(spec.fovH / 2)), tan(Math.toRadians(spec.fovV / 2)))
    }

    /** One filled polygon of the picture, panel coordinates 0..1 interleaved (x, y). */
    class Poly(val pts: FloatArray, val color: Int)

    class Picture(val horizon: Float, val polys: List<Poly>)

    const val YELLOW = 0xFFF2C94C.toInt()
    const val WHITE = 0xFFF2F2EE.toInt()
    const val POLE_DARK = 0xFF1E2125.toInt()
    const val BODY = 0xFFC9CDD2.toInt()
    const val BODY_DARK = 0xFF2B2E33.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val CYAN = 0xFF5BD6C8.toInt()
    const val FLASH_YELLOW = 0xFFFFE94A.toInt()
    const val FLASH_WHITE = 0xFFFFFFFF.toInt()

    /**
     * The picture of one mirror for car [pose], eye height [eyeH] and the current eye position [eye] (cabin). [poles] and
     * [aux] switch the corner poles and the dashed control-line extensions; [lane] draws the lesson-2 lane instead of
     * the bay; [flash] colours one judged line red; [point] colours the lines the yard tour is naming (lesson 1).
     */
    fun picture(spec: Spec, pose: Pose, eyeH: Double, eye: V3, outDeg: Double, pitchDeg: Double, poles: Boolean, lane: Boolean, gap: Double, flash: JudgeLine?, point: List<Seg2> = emptyList(), pointYellow: Boolean = false): Picture {
        val cam = camera(spec, eye, outDeg, pitchDeg)
        val polys = ArrayList<Poly>()
        val ground = if (lane) lanePaint(gap) else Yard.paint.filter { poles || !it.aux }
        val flashSeg = flash?.let { (if (lane) Yard.laneLines(gap) else Yard.judgeLines())[it] }
        for (pt in ground) {
            val red = flashSeg != null && near(pt.seg, flashSeg)
            val named = point.any { near(pt.seg, it) }
            val named2 = if (named) pt.copy(width = pt.width * 2) else pt
            stripe(cam, pose, eyeH, named2, if (red) RED else if (named) (if (pointYellow) FLASH_YELLOW else FLASH_WHITE) else if (pt.yellow) YELLOW else WHITE, polys)
        }
        if (poles && !lane) for (p in Yard.poles) pole(cam, pose, eyeH, p, polys)
        body(cam, spec, eyeH, polys)
        return Picture(cam.horizon.toFloat(), polys)
    }

    private fun near(a: Seg2, b: Seg2): Boolean = (a.a - b.a).length + (a.b - b.b).length < 0.5 || ((a.a - b.b).length + (a.b - b.a).length < 0.5)

    fun lanePaint(gap: Double): List<Paint> =
        Yard.laneLines(gap).values.map { Paint(it, false) } + Yard.laneMarks(gap).map { Paint(it, false, width = 0.10) }

    private fun stripe(cam: Camera, pose: Pose, eyeH: Double, pt: Paint, color: Int, out: MutableList<Poly>) {
        val a = pt.seg.a
        val b = pt.seg.b
        val len = (b - a).length
        if (len < 1e-6) return
        val dir = (b - a) * (1 / len)
        val side = P2(-dir.z, dir.x) * (pt.width / 2)
        var s = 0.0
        val piece = if (pt.dashed) 0.6 else 0.5
        val gapM = if (pt.dashed) 0.4 else 0.0
        while (s < len) {
            val e = min(len, s + piece)
            val p0 = a + dir * s
            val p1 = a + dir * e
            val c = listOf(p0 - side, p1 - side, p1 + side, p0 + side)
            val pts = FloatArray(8)
            var ok = true
            for ((k, q) in c.withIndex()) {
                val pr = cam.project(pose.toCabin(q.x, 0.0, q.z, eyeH)) ?: run { ok = false; null } ?: break
                pts[k * 2] = pr.first.toFloat()
                pts[k * 2 + 1] = pr.second.toFloat()
            }
            if (ok && visible(pts)) out += Poly(pts, color)
            s = e + gapM
        }
    }

    private fun visible(pts: FloatArray): Boolean {
        var minX = 9f
        var maxX = -9f
        var minY = 9f
        var maxY = -9f
        for (i in pts.indices step 2) {
            minX = min(minX, pts[i]); maxX = max(maxX, pts[i]); minY = min(minY, pts[i + 1]); maxY = max(maxY, pts[i + 1])
        }
        return maxX > -0.2f && minX < 1.2f && maxY > -0.2f && minY < 1.2f && maxX - minX < 40f && maxY - minY < 40f
    }

    private fun pole(cam: Camera, pose: Pose, eyeH: Double, p: P2, out: MutableList<Poly>) {
        val r = 0.025
        val c = pose.toCabin(p.x, 0.0, p.z, eyeH)
        val toCam = (cam.eye - c).let { V3(it.x, 0.0, it.z) }.normalized()
        val side = V3(-toCam.z, 0.0, toCam.x) * r
        for (k in 0 until 5) {
            val y0 = 0.3 * k
            val y1 = y0 + 0.3
            val q = listOf(c + side + V3(0.0, y0, 0.0), c - side + V3(0.0, y0, 0.0), c - side + V3(0.0, y1, 0.0), c + side + V3(0.0, y1, 0.0))
            val pts = FloatArray(8)
            var ok = true
            for ((i, v) in q.withIndex()) {
                val pr = cam.project(v)
                if (pr == null) { ok = false; break }
                pts[i * 2] = pr.first.toFloat()
                pts[i * 2 + 1] = pr.second.toFloat()
            }
            if (ok && visible(pts)) out += Poly(pts, if (k % 2 == 0) YELLOW else POLE_DARK)
        }
    }

    /** Your own car in the side mirrors: the body side from the mirror back, the rear wheel arch, the bumper corner. */
    private fun body(cam: Camera, spec: Spec, eyeH: Double, out: MutableList<Poly>) {
        if (spec.id == Id.I) {
            // inside mirror: the rear window frame (roof, C pillars, parcel shelf) as a dark border
            frame(cam, out)
            return
        }
        val x = if (spec.id == Id.L) -0.48 else 1.22
        val g = -eyeH
        fun poly(vararg v: V3, color: Int) {
            val pts = FloatArray(v.size * 2)
            for ((i, p) in v.withIndex()) {
                val pr = cam.project(p) ?: return
                pts[i * 2] = pr.first.toFloat()
                pts[i * 2 + 1] = pr.second.toFloat()
            }
            out += Poly(pts, color)
        }
        poly(V3(x, -0.22, -0.55), V3(x, -0.22, 2.40), V3(x, g + 0.30, 2.50), V3(x, g + 0.30, -0.55), color = BODY)
        // rear wheel arch around the rear axle (z 1.45), radius 0.38
        val n = 10
        val arch = ArrayList<V3>()
        for (i in 0..n) {
            val a = Math.PI * i / n
            arch += V3(x, g + 0.31 + 0.38 * sin(a), 1.45 + 0.38 * cos(a))
        }
        poly(*arch.toTypedArray(), color = BODY_DARK)
        poly(V3(x, g + 0.30, 2.42), V3(x, g + 0.52, 2.48), V3(x, g + 0.52, 2.55), V3(x, g + 0.30, 2.55), color = BODY_DARK)
    }

    private fun frame(cam: Camera, out: MutableList<Poly>) {
        fun quad(a: V3, b: V3, c: V3, d: V3) {
            val pts = FloatArray(8)
            for ((i, p) in listOf(a, b, c, d).withIndex()) {
                val pr = cam.project(p) ?: return
                pts[i * 2] = pr.first.toFloat()
                pts[i * 2 + 1] = pr.second.toFloat()
            }
            out += Poly(pts, BODY_DARK)
        }
        quad(V3(-0.48, 0.22, 1.92), V3(1.22, 0.22, 1.92), V3(1.22, 0.40, 1.92), V3(-0.48, 0.40, 1.92))
        quad(V3(-0.48, -0.30, 2.30), V3(1.22, -0.30, 2.30), V3(1.22, -0.60, 2.30), V3(-0.48, -0.60, 2.30))
        quad(V3(-0.48, 0.30, 1.90), V3(-0.30, 0.30, 1.90), V3(-0.30, -0.40, 2.32), V3(-0.48, -0.40, 2.32))
        quad(V3(1.04, 0.30, 1.90), V3(1.22, 0.30, 1.90), V3(1.22, -0.40, 2.32), V3(1.04, -0.40, 2.32))
    }
}
