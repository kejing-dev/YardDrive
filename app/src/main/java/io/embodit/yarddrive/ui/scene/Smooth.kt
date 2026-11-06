package io.embodit.yarddrive.ui.scene

import android.graphics.Bitmap
import com.pico.spatial.core.math.Vector2
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Smooth shading for the round parts (user 2026-11-05: "the wheel is made of squares, can it be a ring?"; B12). The scene is
 * Unlit, so the light is baked per vertex: every palette colour has one row in this ramp, from its shady side (u = 0) to
 * its lit side with a soft sheen (u = 1). A vertex picks u from its own normal, and the texture interpolates between
 * vertices, so a torus or a lofted car body reads round with no facets, in the same colours as the rest of the car.
 */
internal object Ramp {
    // a short ramp with tall rows: on a silhouette u changes fast, the GPU picks a coarse mip level and an 8 px row bled
    // its neighbours' colour in as a thin light outline (seen on the v0.1.7 wheel); 64 px rows keep coarse levels in the row
    const val W = 64
    private const val ROW = 64

    val rows: Int
        get() = Palette.count

    fun bitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(W, rows * ROW, Bitmap.Config.ARGB_8888)
        val px = IntArray(W * rows * ROW)
        for (c in 0 until rows) {
            val argb = Palette.argb(c)
            val r0 = (argb shr 16) and 255
            val g0 = (argb shr 8) and 255
            val b0 = argb and 255
            val lum = (0.2126f * r0 + 0.7152f * g0 + 0.0722f * b0) / 255f
            // dark colours get a little more sheen, or a black rim would read as a flat silhouette
            val sheen = 255f * (0.05f + 0.22f * (1f - lum))
            for (i in 0 until W) {
                val s = i / (W - 1f)
                val k = 0.50f + 0.60f * s
                val h = sheen * s.pow(8)
                val r = (r0 * k + h).toInt().coerceIn(0, 255)
                val g = (g0 * k + h).toInt().coerceIn(0, 255)
                val b = (b0 * k + h).toInt().coerceIn(0, 255)
                val v = android.graphics.Color.argb(255, r, g, b)
                for (y in 0 until ROW) px[(c * ROW + y) * W + i] = v
            }
        }
        bmp.setPixels(px, 0, W, 0, 0, W, rows * ROW)
        return bmp
    }

    /** [s] 0 (shade) .. 1 (lit). v = 0 is the top row as drawn (flipped once on upload, like the atlas). */
    fun uv(color: Int, s: Float): Vector2 = Vector2((0.5f + s.coerceIn(0f, 1f) * (W - 1)) / W, (color + 0.5f) / rows)

    /** Half-Lambert, a little steeper: the side away from the light stays readable. */
    fun shade(n: Vec3, l: Vec3): Float = (0.5f + 0.5f * n.dot(l)).coerceIn(0f, 1f).pow(1.35f)
}

/**
 * A mesh builder with smooth normals: surfaces of revolution (torus, hub, knob, tyre), straight tubes with an elliptic
 * section (spokes) and lofted sections (car bodies). [light] gets each vertex position and normal in the frame being
 * built (after [at]) and returns 0..1 for [Ramp]. Quads are wound so that their front faces the vertex normals, so a
 * colour-only material with back-face culling draws the outside only.
 */
internal class Smooth(capacity: Int = 1024, private val light: (Vec3, Vec3) -> Float) {
    val b = MeshBuilder(capacity)
    private var rot = Quat4.IDENTITY
    private var off = Vec3.ZERO

    fun at(rotation: Quat4, offset: Vec3, block: Smooth.() -> Unit) {
        val r0 = rot
        val o0 = off
        // nested: the inner frame sits inside the outer one
        rot = r0 * rotation
        off = o0 + r0.rotate(offset)
        block()
        rot = r0
        off = o0
    }

    /** One vertex: local position and normal, coloured by [color]; a null [color] leaves the UV at the centre (ghosts). */
    fun vertex(color: Int?, p: Vec3, n: Vec3): Int {
        val wp = off + rot.rotate(p)
        val wn = rot.rotate(n).normalized()
        val uv = if (color == null) MeshBuilder.ZERO_UV else Ramp.uv(color, light(wp, wn))
        return b.vertex(wp, wn, uv)
    }

    /** a b c d around a quad: wound so its face normal agrees with the vertex normals. */
    fun quad(a: Int, c1: Int, c2: Int, d: Int) {
        val pa = b.positions[a]
        val pb = b.positions[c1]
        val pc = b.positions[c2]
        val e1 = Vec3(pb.x - pa.x, pb.y - pa.y, pb.z - pa.z)
        val e2 = Vec3(pc.x - pa.x, pc.y - pa.y, pc.z - pa.z)
        var f = e1.cross(e2)
        if (f.length < 1e-12f) {
            val pd = b.positions[d]
            f = e2.cross(Vec3(pd.x - pa.x, pd.y - pa.y, pd.z - pa.z))
        }
        val n = listOf(a, c1, c2, d).fold(Vec3.ZERO) { acc, i -> val v = b.normals[i]; acc + Vec3(v.x, v.y, v.z) }
        if (f.dot(n) >= 0f) b.quad(a, c1, c2, d) else b.quad(a, d, c2, c1)
    }

    fun tri(a: Int, c1: Int, c2: Int) {
        val pa = b.positions[a]
        val pb = b.positions[c1]
        val pc = b.positions[c2]
        val f = Vec3(pb.x - pa.x, pb.y - pa.y, pb.z - pa.z).cross(Vec3(pc.x - pa.x, pc.y - pa.y, pc.z - pa.z))
        val n = listOf(a, c1, c2).fold(Vec3.ZERO) { acc, i -> val v = b.normals[i]; acc + Vec3(v.x, v.y, v.z) }
        if (f.dot(n) >= 0f) b.tri(a, c1, c2) else b.tri(a, c2, c1)
    }

    /** A grid of vertices [rows] × [cols] (row-major, already emitted from index [base]) joined into quads. */
    private fun grid(base: Int, rows: Int, cols: Int) {
        for (j in 0 until rows - 1) for (i in 0 until cols - 1) {
            val a = base + j * cols + i
            quad(a, a + 1, a + cols + 1, a + cols)
        }
    }

    /** One point of a profile in the (radius, z) half-plane with its normal (nr, nz). */
    data class Prof(val r: Float, val z: Float, val nr: Float, val nz: Float)

    /**
     * Surface of revolution about local Z from angle [a0] to [a1] (radians, 0 = +X, counter-clockwise seen from +Z) in [seg]
     * steps; the profile's own normals make it smooth along the profile, the revolution makes it smooth around.
     */
    fun lathe(color: Int?, profile: List<Prof>, a0: Double, a1: Double, seg: Int) {
        val base = b.vertexCount
        for (j in 0..seg) {
            val a = a0 + (a1 - a0) * j / seg
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            for (p in profile) vertex(color, Vec3(p.r * ca, p.r * sa, p.z), Vec3(p.nr * ca, p.nr * sa, p.nz))
        }
        grid(base, seg + 1, profile.size)
    }

    /** The section of a torus: a circle of radius [tube] centred at radius [major], [n] steps (closed). */
    fun torusProfile(major: Float, tube: Float, n: Int): List<Prof> =
        (0..n).map { k ->
            val f = 2 * PI * k / n
            val c = cos(f).toFloat()
            val s = sin(f).toFloat()
            Prof(major + tube * c, tube * s, c, s)
        }

    /** A straight tube from [from] to [to] with an elliptic section: half-axes [halfA] along [aDir] and [halfB] across. */
    fun tube(color: Int?, from: Vec3, to: Vec3, aDir: Vec3, halfA: Float, halfB: Float, seg: Int = 12) {
        val axis = (to - from).normalized()
        val u = (aDir - axis * aDir.dot(axis)).normalized()
        val v = axis.cross(u).normalized()
        val base = b.vertexCount
        for (end in 0..1) {
            val c = if (end == 0) from else to
            for (k in 0..seg) {
                val f = 2 * PI * k / seg
                val cf = cos(f).toFloat()
                val sf = sin(f).toFloat()
                val p = c + u * (halfA * cf) + v * (halfB * sf)
                // the ellipse's normal: (cos / a, sin / b)
                val n = (u * (cf / halfA) + v * (sf / halfB)).normalized()
                vertex(color, p, n)
            }
        }
        grid(base, 2, seg + 1)
    }

    /**
     * A body lofted through [rings] (same point count each; each ring runs from one foot over the top to the other foot),
     * normals from the surface itself, pointing away from each ring's centre; floor and both ends closed.
     */
    fun loft(color: Int, rings: List<List<Vec3>>) {
        val nr = rings.size
        val np = rings[0].size
        val centre = rings.map { r -> r.reduce { acc, v -> acc + v } * (1f / np) }
        val base = b.vertexCount
        for (j in 0 until nr) for (i in 0 until np) {
            val p = rings[j][i]
            val du = rings[j][minOf(i + 1, np - 1)] - rings[j][maxOf(i - 1, 0)]
            val dv = rings[minOf(j + 1, nr - 1)][i] - rings[maxOf(j - 1, 0)][i]
            var n = du.cross(dv)
            if (n.length < 1e-6f) n = p - centre[j]
            n = n.normalized()
            if (n.dot(p - centre[j]) < 0f) n = n * -1f
            vertex(color, p, n)
        }
        grid(base, nr, np)
        // the ends: flat caps with their own normals
        for ((j, sign) in listOf(0 to -1f, nr - 1 to 1f)) {
            val d = (centre[minOf(nr - 1, j + 1)] - centre[maxOf(0, j - 1)]).normalized() * sign
            val cv = vertex(color, centre[j], d)
            val first = b.vertexCount
            for (p in rings[j]) vertex(color, p, d)
            for (i in 0 until np - 1) tri(cv, first + i, first + i + 1)
            tri(cv, first + np - 1, first)
        }
    }

    /** A flat disc of radius [r] (× [ry] across) at [c] facing [n], in the plane spanned by [u] and n × u. */
    fun disc(color: Int, c: Vec3, n: Vec3, u: Vec3, r: Float, ry: Float = r, seg: Int = 24) {
        val v = n.cross(u).normalized()
        val cv = vertex(color, c, n)
        val first = b.vertexCount
        for (k in 0..seg) {
            val f = 2 * PI * k / seg
            vertex(color, c + u * (r * cos(f).toFloat()) + v * (ry * sin(f).toFloat()), n)
        }
        for (k in 0 until seg) tri(cv, first + k, first + k + 1)
    }

    fun build(name: String) = b.build(name)

    companion object {
        /** A superellipse arch at [z]: from the floor on one side, over the top, to the floor on the other ([e] = squareness). */
        fun arch(cx: Float, halfW: Float, yBottom: Float, yTop: Float, z: Float, e: Float = 3f, n: Int = 14): List<Vec3> =
            (0..n).map { i ->
                val a = PI * i / n
                val c = cos(a)
                val s = sin(a)
                val x = cx + halfW * (if (c >= 0) 1f else -1f) * abs(c).pow(2.0 / e).toFloat()
                val y = yBottom + (yTop - yBottom) * abs(s).pow(2.0 / e).toFloat()
                Vec3(x, y, z)
            }

        /** Smoothstep through a (z, value) table. */
        fun interp(table: Array<FloatArray>, z: Float): Float {
            for (i in 0 until table.size - 1) {
                val a = table[i]
                val c = table[i + 1]
                if (z <= c[0]) {
                    val t = ((z - a[0]) / max(1e-5f, c[0] - a[0])).coerceIn(0f, 1f)
                    val s = t * t * (3 - 2 * t)
                    return a[1] + (c[1] - a[1]) * s
                }
            }
            return table.last()[1]
        }
    }
}

/**
 * The round parts of the car you sit in and the coach cars in the yard, all smooth (B12, user 2026-11-05). Sizes from
 * [io.embodit.yarddrive.domain.CarModel]; nothing here is a box.
 */
internal object RoundParts {
    /** Wheel rim: 72 steps around (5° each), 16 around the tube. */
    const val RIM_SEG = 72
    const val TUBE_SEG = 16
    const val TUBE_R = 0.016f

    /** The 12 o'clock mark: three of the rim's steps (15°), the same torus in yellow, flush with the rim. */
    const val MARK_STEPS = 3

    /** Hub radius and depth (local Z = the column axis towards you; the rim plane is z = 0). */
    const val HUB_R = 0.062f

    /**
     * Light on the wheel, symmetric about its axis: from your side and a little outwards. The wheel turns up to ±540°;
     * a light fixed to the wheel would swing round with it, this one looks the same at any angle.
     */
    fun wheelLight(p: Vec3, n: Vec3): Float {
        val flat = Vec3(p.x, p.y, 0f)
        val radial = if (flat.length > 1e-4f) flat.normalized() else Vec3(0f, 1f, 0f)
        val l = (radial * 0.45f + Vec3(0f, 0f, 0.9f)).normalized()
        return Ramp.shade(n, l)
    }

    /**
     * The steering wheel in its own frame: rim (torus), three spokes (elliptic tubes), hub (rounded disc) and the 12
     * o'clock mark (a yellow arc of the rim). [part] picks one part for the D20 ghost meshes (null = all, solid).
     */
    fun wheel(s: Smooth, rimR: Float, part: WheelPart? = null) {
        val ghost = part != null
        val rimC: Int? = if (ghost) null else Palette.WHEEL_RIM
        val ring = s.torusProfile(rimR, TUBE_R, TUBE_SEG)
        val half = Math.toRadians(MARK_STEPS * 360.0 / RIM_SEG / 2)
        val top = PI / 2
        if (part == null || part == WheelPart.RIM) {
            // the rest of the rim, from just past the mark all the way round to just before it
            s.lathe(rimC, ring, top + half, top - half + 2 * PI, RIM_SEG - MARK_STEPS)
            // spokes at 6, 2 and 10 o'clock, from inside the hub to inside the rim, dished towards you
            for (deg in doubleArrayOf(270.0, 30.0, 150.0)) {
                val a = Math.toRadians(deg)
                val dir = Vec3(cos(a).toFloat(), sin(a).toFloat(), 0f)
                val tangent = Vec3(-dir.y, dir.x, 0f)
                s.tube(rimC, dir * 0.045f + Vec3(0f, 0f, -0.018f), dir * (rimR - 0.008f) + Vec3(0f, 0f, -0.002f), tangent, 0.013f, 0.0065f, 12)
            }
            hub(s, rimC, if (ghost) null else Palette.WHEEL_HI)
        }
        if (part == null || part == WheelPart.MARK) s.lathe(if (ghost) null else Palette.YELLOW, ring, top - half, top + half, MARK_STEPS)
    }

    enum class WheelPart { RIM, MARK }

    /** The hub: a disc with a rounded front edge, and a lighter centre pad 1 mm proud of it. */
    private fun hub(s: Smooth, color: Int?, pad: Int?) {
        val front = -0.004f
        val back = -0.036f
        val bev = 0.012f
        val p = ArrayList<Smooth.Prof>()
        p += Smooth.Prof(0f, front, 0f, 1f)
        val inner = HUB_R - bev
        for (k in 0..6) {
            val a = PI / 2 * (1 - k / 6.0)
            p += Smooth.Prof(inner + bev * cos(a).toFloat(), front - bev + bev * sin(a).toFloat(), cos(a).toFloat(), sin(a).toFloat())
        }
        p += Smooth.Prof(HUB_R, back, 1f, 0f)
        p += Smooth.Prof(HUB_R, back, 0f, -1f)
        p += Smooth.Prof(0f, back, 0f, -1f)
        s.lathe(color, p, 0.0, 2 * PI, 40)
        if (pad != null) {
            val q = listOf(Smooth.Prof(0f, front + 0.001f, 0f, 1f), Smooth.Prof(0.024f, front + 0.001f, 0f, 1f), Smooth.Prof(0.028f, front - 0.001f, 0.7f, 0.7f))
            s.lathe(pad, q, 0.0, 2 * PI, 32)
        }
    }

    /** The gear knob, about local Y from its root: a shaft and a ball (the light is the cabin's, from the front left). */
    fun gearKnob(s: Smooth) {
        // lathe about Z, turned so that Z goes up
        s.at(Quat4.axisAngle(Vec3(1f, 0f, 0f), (-PI / 2).toFloat()), Vec3.ZERO) {
            val shaft = listOf(Smooth.Prof(0.010f, -0.10f, 1f, 0f), Smooth.Prof(0.010f, 0.0f, 1f, 0f))
            lathe(Palette.TRIM, shaft, 0.0, 2 * PI, 16)
            val ball = (0..12).map { k ->
                val f = -PI / 2 + PI * k / 12
                Smooth.Prof(0.022f * cos(f).toFloat(), 0.018f + 0.022f * sin(f).toFloat(), cos(f).toFloat(), sin(f).toFloat())
            }
            lathe(Palette.KNOB, ball, 0.0, 2 * PI, 24)
        }
    }

    /**
     * A parked exam car, 4.5 × 1.7 × 1.45 m, nose towards local -Z, origin on the road under its centre: rounded body,
     * dark glasshouse with a roof over it, four tyres with hub caps, the yellow roof lamp, tail lights, a dark patch under
     * it (the road is Unlit, it gets no shadow). Same idea as the traffic-signals cars, written for this scene's ramp.
     */
    fun coachCar(s: Smooth) {
        val body = Palette.COACH
        val zs = floatArrayOf(-2.25f, -2.22f, -2.15f, -2.0f, -1.75f, -1.35f, -0.85f, -0.2f, 0.6f, 1.25f, 1.75f, 2.05f, 2.19f, 2.25f)
        val top = arrayOf(floatArrayOf(-2.25f, 0.50f), floatArrayOf(-2.15f, 0.66f), floatArrayOf(-1.85f, 0.78f), floatArrayOf(-0.95f, 0.88f), floatArrayOf(0f, 0.9f), floatArrayOf(1.3f, 0.92f), floatArrayOf(2.05f, 0.88f), floatArrayOf(2.25f, 0.62f))
        val halfW = arrayOf(floatArrayOf(-2.25f, 0.60f), floatArrayOf(-2.05f, 0.78f), floatArrayOf(-1.5f, 0.85f), floatArrayOf(1.6f, 0.85f), floatArrayOf(2.1f, 0.78f), floatArrayOf(2.25f, 0.62f))
        s.loft(body, zs.map { z -> Smooth.arch(0f, Smooth.interp(halfW, z), 0.24f, Smooth.interp(top, z), z, e = 3.2f) })
        val cabinZ = floatArrayOf(-1.1f, -0.8f, -0.4f, 0f, 0.6f, 1.0f, 1.3f, 1.65f)
        val cabinTop = arrayOf(floatArrayOf(-1.1f, 0.87f), floatArrayOf(-0.3f, 1.38f), floatArrayOf(0.4f, 1.44f), floatArrayOf(1.0f, 1.38f), floatArrayOf(1.65f, 0.92f))
        s.loft(Palette.GLASS, cabinZ.map { z -> Smooth.arch(0f, 0.72f - 0.05f * Smooth.interp(cabinTop, z) / 1.44f, 0.84f, Smooth.interp(cabinTop, z), z, e = 3.6f, n = 12) })
        val roofZ = floatArrayOf(-0.38f, -0.26f, 0.1f, 0.6f, 0.92f, 1.04f)
        s.loft(body, roofZ.map { z ->
            val t = Smooth.interp(cabinTop, z) + 0.012f
            Smooth.arch(0f, 0.6f, t - 0.05f, t, z, e = 3.6f, n = 10)
        })
        // the roof lamp of an exam car: a small rounded yellow block on the roof
        val lampZ = floatArrayOf(-0.02f, 0.0f, 0.06f, 0.20f, 0.26f, 0.28f)
        s.loft(Palette.YELLOW, lampZ.map { z ->
            val k = 1f - 0.5f * (((z - 0.13f) / 0.15f).let { it * it }).coerceAtMost(1f)
            Smooth.arch(0f, 0.32f * (0.8f + 0.2f * k), 1.44f, 1.44f + 0.15f * k, z, e = 4f, n = 10)
        })
        for (side in floatArrayOf(-1f, 1f)) for (z in floatArrayOf(-1.38f, 1.38f)) {
            // the arch: a dark disc on the flank, the tyre stands in it
            s.disc(Palette.TYRE, Vec3(side * 0.853f, 0.31f, z), Vec3(side, 0f, 0f), Vec3(0f, 0f, 1f), 0.37f)
            tyre(s, side * 0.76f, z, 0.31f, 0.22f, side)
        }
        for (side in floatArrayOf(-1f, 1f)) {
            s.disc(Palette.LIGHT_GREY, Vec3(side * 0.46f, 0.54f, -2.252f), Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f), 0.13f, ry = 0.055f, seg = 16)
            s.disc(Palette.TAIL_RED, Vec3(side * 0.5f, 0.68f, 2.252f), Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), 0.14f, ry = 0.05f, seg = 16)
        }
        // contact patch
        val n = Vec3.UP
        s.quad(s.vertex(Palette.TYRE, Vec3(-0.92f, 0.012f, 2.2f), n), s.vertex(Palette.TYRE, Vec3(0.92f, 0.012f, 2.2f), n), s.vertex(Palette.TYRE, Vec3(0.92f, 0.012f, -2.2f), n), s.vertex(Palette.TYRE, Vec3(-0.92f, 0.012f, -2.2f), n))
    }

    /** A tyre, axis along X, round shoulders, and a hub cap on the outer face ([side] -1 left, 1 right). */
    fun tyre(s: Smooth, x: Float, z: Float, r: Float, w: Float, side: Float, y0: Float = 0f) {
        // lathe about Z turned onto X: Z -> X
        s.at(Quat4.axisAngle(Vec3(0f, 1f, 0f), (PI / 2).toFloat()), Vec3(x, y0 + r, z)) {
            val p = ArrayList<Smooth.Prof>()
            val sh = 0.035f
            p += Smooth.Prof(r * 0.55f, -w / 2, 0f, -1f)
            for (k in 0..4) {
                val a = PI / 2 * k / 4
                p += Smooth.Prof(r - sh + sh * sin(a).toFloat(), -w / 2 + sh - sh * cos(a).toFloat(), sin(a).toFloat(), -cos(a).toFloat())
            }
            for (k in 0..4) {
                val a = PI / 2 * k / 4
                p += Smooth.Prof(r - sh + sh * cos(a).toFloat(), w / 2 - sh + sh * sin(a).toFloat(), cos(a).toFloat(), sin(a).toFloat())
            }
            p += Smooth.Prof(r * 0.55f, w / 2, 0f, 1f)
            lathe(Palette.TYRE, p, 0.0, 2 * PI, 28)
            val face = w / 2 * side + 0.002f * side
            val cap = listOf(Smooth.Prof(0f, face, 0f, side), Smooth.Prof(r * 0.5f, face, 0f, side), Smooth.Prof(r * 0.56f, face - 0.008f * side, 0.6f, 0.8f * side))
            lathe(Palette.LIGHT_GREY, cap, 0.0, 2 * PI, 24)
        }
    }
}
