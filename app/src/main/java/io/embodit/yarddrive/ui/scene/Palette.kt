package io.embodit.yarddrive.ui.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.pico.spatial.core.math.Vector2
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Flat colours of the procedural car and yard (design palette.cabin / palette.yard). Every colour is a block of three
 * shades in one small atlas (top / sunny side / shady side), so everything is drawn with one opaque Unlit material and a
 * sun from the front left (design: 40° from the car's front left) without any light computation.
 */
internal object Palette {
    const val DASH = 0
    const val TRIM = 1
    const val SEAT = 2
    const val HEADLINER = 3
    const val BODY = 4
    const val BUMPER = 5
    const val TYRE = 6
    const val FRAME = 7
    const val WHEEL_RIM = 8
    const val WHEEL_HI = 9
    const val YELLOW = 10
    const val WHITE = 11
    const val POLE_DARK = 12
    const val FENCE = 13
    const val COACH = 14
    const val LEAF = 15
    const val TRUNK = 16
    const val BUILDING = 17
    const val SIGN = 18
    const val GRASS = 19
    const val CYAN = 20
    const val RED = 21
    const val KNOB = 22
    const val SKIN = 23

    private val COLORS =
        intArrayOf(
            0xFF2B2E33.toInt(), // dash (design palette.cabin.dash)
            0xFF3A3E44.toInt(), // trim
            0xFF4A4F57.toInt(), // seat
            0xFFC9C6BF.toInt(), // headliner
            0xFFD8DBDE.toInt(), // body (exam car, light silver)
            0xFF2B2E33.toInt(), // bumper
            0xFF15171A.toInt(), // tyre
            0xFF1E2125.toInt(), // mirror frame
            0xFF1A1C1F.toInt(), // wheel rim
            0xFF3D4248.toInt(), // grip ring highlight
            0xFFF2C94C.toInt(), // bay line / pole yellow
            0xFFF2F2EE.toInt(), // white lines
            0xFF1E2125.toInt(), // pole dark band
            0xFF7A8089.toInt(), // fence
            0xFFF4F5F2.toInt(), // coach car
            0xFF4C7A3C.toInt(), // tree crown
            0xFF6B4E32.toInt(), // trunk
            0xFF9AA3AD.toInt(), // building
            0xFF2B5FB3.toInt(), // sign blue
            0xFF6E7F5A.toInt(), // verge
            0xFF5BD6C8.toInt(), // ideal path cyan
            0xFFFF3B30.toInt(), // line flash red
            0xFF1A1C1F.toInt(), // gear knob
            0xFFE0B49A.toInt(), // coach hand skin
        )

    const val COLS = 8
    private const val BLOCK = 16

    val rows: Int
        get() = (COLORS.size + COLS - 1) / COLS

    private val SHADES = floatArrayOf(1.0f, 0.86f, 0.68f)

    fun argb(color: Int): Int = COLORS[color]

    fun atlas(): Bitmap {
        val w = COLS * 3 * BLOCK
        val h = rows * BLOCK
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (i in COLORS.indices) {
            val col = i % COLS
            val row = i / COLS
            for (s in 0 until 3) {
                val k = SHADES[s]
                val argb = COLORS[i]
                val r = (((argb shr 16) and 255) * k).toInt().coerceIn(0, 255)
                val g = (((argb shr 8) and 255) * k).toInt().coerceIn(0, 255)
                val b = ((argb and 255) * k).toInt().coerceIn(0, 255)
                p.color = android.graphics.Color.argb(255, r, g, b)
                val x0 = (col * 3 + s) * BLOCK
                val y0 = row * BLOCK
                c.drawRect(x0.toFloat(), y0.toFloat(), (x0 + BLOCK).toFloat(), (y0 + BLOCK).toFloat(), p)
            }
        }
        return bmp
    }

    /** UV of a colour block: v = 0 is the top row as drawn (the bitmap is flipped once on upload, see flipV). */
    fun uv(color: Int, shade: Int): Vector2 {
        val col = color % COLS
        val row = color / COLS
        val u = ((col * 3 + shade) + 0.5f) / (COLS * 3)
        val v = (row + 0.5f) / rows
        return Vector2(u, v)
    }

    /** Toward the sun in the frame being built (yard: from the south-west-ish, high). */
    var SUN = Vec3(-0.5f, 0.7071f, 0.5f).normalized()

    fun shadeOf(n: Vec3): Int {
        if (n.y > 0.6f) return 0
        val flat = Vec3(n.x, 0f, n.z)
        return if (flat.dot(Vec3(SUN.x, 0f, SUN.z)) > -0.05f) 1 else 2
    }
}

internal class Toon(capacity: Int = 1024) {
    val b = MeshBuilder(capacity)
    private var rot = Quat4.IDENTITY
    private var off = Vec3.ZERO
    private var k = Vec3(1f, 1f, 1f)

    fun at(rotation: Quat4, offset: Vec3, scale: Vec3 = Vec3(1f, 1f, 1f), block: Toon.() -> Unit) {
        val r0 = rot
        val o0 = off
        val k0 = k
        rot = rotation
        off = offset
        k = scale
        block()
        rot = r0
        off = o0
        k = k0
    }

    private fun p(v: Vec3): Vec3 = off + rot.rotate(Vec3(v.x * k.x, v.y * k.y, v.z * k.z))

    private fun n(v: Vec3): Vec3 = rot.rotate(v).normalized()

    /** One flat quad a, b, c, d (counter-clockwise seen from the front). */
    fun quad(color: Int, a: Vec3, bb: Vec3, c: Vec3, d: Vec3, shade: Int? = null) {
        val pa = p(a)
        val pb = p(bb)
        val pc = p(c)
        val pd = p(d)
        val nrm = (pb - pa).cross(pc - pa).normalized()
        val uv = Palette.uv(color, shade ?: Palette.shadeOf(nrm))
        b.quad(b.vertex(pa, nrm, uv), b.vertex(pb, nrm, uv), b.vertex(pc, nrm, uv), b.vertex(pd, nrm, uv))
    }

    fun tri(color: Int, a: Vec3, bb: Vec3, c: Vec3) {
        val pa = p(a)
        val pb = p(bb)
        val pc = p(c)
        val nrm = (pb - pa).cross(pc - pa).normalized()
        val uv = Palette.uv(color, Palette.shadeOf(nrm))
        b.tri(b.vertex(pa, nrm, uv), b.vertex(pb, nrm, uv), b.vertex(pc, nrm, uv))
    }

    /** Axis-aligned box from [min] to [max] (local), all six faces. */
    fun box(color: Int, min: Vec3, max: Vec3, top: Int = color, bottom: Boolean = false) {
        val (x0, y0, z0) = Triple(min.x, min.y, min.z)
        val (x1, y1, z1) = Triple(max.x, max.y, max.z)
        quad(top, Vec3(x0, y1, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0), Vec3(x0, y1, z0)) // top
        if (bottom) quad(color, Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y0, z1), Vec3(x0, y0, z1))
        quad(color, Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1)) // +z
        quad(color, Vec3(x1, y0, z0), Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x1, y1, z0)) // -z
        quad(color, Vec3(x1, y0, z1), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1)) // +x
        quad(color, Vec3(x0, y0, z0), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x0, y1, z0)) // -x
    }

    /** Vertical cylinder centred on (cx, cz) from y0 to y1. */
    fun cylinder(color: Int, cx: Float, cz: Float, r: Float, y0: Float, y1: Float, seg: Int = 16, top: Int = color) {
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val p0 = Vec3(cx + (r * cos(a0)).toFloat(), 0f, cz + (r * sin(a0)).toFloat())
            val p1 = Vec3(cx + (r * cos(a1)).toFloat(), 0f, cz + (r * sin(a1)).toFloat())
            quad(color, Vec3(p1.x, y0, p1.z), Vec3(p0.x, y0, p0.z), Vec3(p0.x, y1, p0.z), Vec3(p1.x, y1, p1.z))
            tri(top, Vec3(cx, y1, cz), Vec3(p1.x, y1, p1.z), Vec3(p0.x, y1, p0.z))
        }
    }

    /** Vertical cone (tree crown). */
    fun cone(color: Int, cx: Float, cz: Float, r: Float, y0: Float, y1: Float, seg: Int = 10) {
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val p0 = Vec3(cx + (r * cos(a0)).toFloat(), y0, cz + (r * sin(a0)).toFloat())
            val p1 = Vec3(cx + (r * cos(a1)).toFloat(), y0, cz + (r * sin(a1)).toFloat())
            tri(color, p1, p0, Vec3(cx, y1, cz))
        }
    }

    fun build(name: String) = b.build(name)

    @Suppress("unused")
    private fun unusedMax(a: Float, c: Float) = max(a, c)
}
