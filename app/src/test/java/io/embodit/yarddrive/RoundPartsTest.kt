package io.embodit.yarddrive

import io.embodit.yarddrive.domain.CarModel
import io.embodit.yarddrive.domain.Vec3
import io.embodit.yarddrive.ui.scene.RoundParts
import io.embodit.yarddrive.ui.scene.Smooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** User 2026-11-05 / B12: the wheel is a real ring with smooth normals, not boxes round a circle. */
class RoundPartsTest {
    private val rimR = CarModel.WHEEL_R.toFloat()

    private fun rim(): Smooth = Smooth(6000, RoundParts::wheelLight).also { RoundParts.wheel(it, rimR, RoundParts.WheelPart.RIM) }

    /** Every vertex on the rim's torus has the torus normal: from the tube's centre circle out through the vertex. */
    @Test
    fun rimIsATorusWithSmoothNormals() {
        val s = rim()
        var onTorus = 0
        for (i in 0 until s.b.vertexCount) {
            val p = s.b.positions[i].let { Vec3(it.x, it.y, it.z) }
            val n = s.b.normals[i].let { Vec3(it.x, it.y, it.z) }
            assertEquals(1f, n.length, 1e-3f)
            val r = hypot(p.x, p.y)
            if (r < 1e-3f) continue
            // the torus' own vertices: on the tube around the circle of radius rimR in z = 0
            val c = Vec3(p.x / r * rimR, p.y / r * rimR, 0f)
            val d = p - c
            if (abs(d.length - RoundParts.TUBE_R) > 1e-4f) continue
            onTorus++
            assertTrue("normal at $p", d.normalized().dot(n) > 0.9999f)
        }
        // 72 - 3 steps of the rim (+1 column) × 17 profile points
        assertEquals((RoundParts.RIM_SEG - RoundParts.MARK_STEPS + 1) * (RoundParts.TUBE_SEG + 1), onTorus)
        assertTrue(RoundParts.RIM_SEG >= 48 && RoundParts.TUBE_SEG >= 12)
    }

    /** The yellow 12 o'clock mark is an arc of the same torus (flush), centred on +Y, 15° long; the rest closes the circle. */
    @Test
    fun markIsAnArcOfTheSameRing() {
        val m = Smooth(200, RoundParts::wheelLight).also { RoundParts.wheel(it, rimR, RoundParts.WheelPart.MARK) }
        var lo = 999.0
        var hi = -999.0
        for (i in 0 until m.b.vertexCount) {
            val p = m.b.positions[i]
            val r = hypot(p.x, p.y)
            assertEquals(RoundParts.TUBE_R.toDouble(), hypot(r - rimR.toDouble(), p.z.toDouble()), 1e-4)
            val a = Math.toDegrees(atan2(p.y.toDouble(), p.x.toDouble()))
            lo = minOf(lo, a)
            hi = maxOf(hi, a)
        }
        assertEquals(82.5, lo, 0.01)
        assertEquals(97.5, hi, 0.01)
        // nothing on the rim or spokes stands out towards you further than the tube
        val s = rim()
        for (i in 0 until s.b.vertexCount) assertTrue(s.b.positions[i].z <= RoundParts.TUBE_R + 1e-4f)
    }

    /** The light is the same at any steering angle: turning the wheel does not swing a highlight round (it turns ±540°). */
    @Test
    fun wheelLightIsSymmetricAboutTheAxis() {
        val p = Vec3(rimR, 0.005f, 0.01f)
        val n = Vec3(0.6f, 0.1f, 0.79f).normalized()
        val base = RoundParts.wheelLight(p, n)
        for (deg in intArrayOf(30, 90, 145, 200, 300)) {
            val a = Math.toRadians(deg.toDouble())
            val c = kotlin.math.cos(a).toFloat()
            val sn = kotlin.math.sin(a).toFloat()
            fun rot(v: Vec3) = Vec3(v.x * c - v.y * sn, v.x * sn + v.y * c, v.z)
            assertEquals(base, RoundParts.wheelLight(rot(p), rot(n)), 1e-4f)
        }
    }

    /** Quads face their normals (the D20 ghost is drawn with back faces culled: it must show the outside). */
    @Test
    fun facesPointOutwards() {
        val s = rim()
        val idx = s.b.indices
        var bad = 0
        var i = 0
        while (i < idx.size) {
            val a = s.b.positions[idx[i]]
            val b = s.b.positions[idx[i + 1]]
            val c = s.b.positions[idx[i + 2]]
            val f = Vec3(b.x - a.x, b.y - a.y, b.z - a.z).cross(Vec3(c.x - a.x, c.y - a.y, c.z - a.z))
            val n = s.b.normals[idx[i]].let { Vec3(it.x, it.y, it.z) } + s.b.normals[idx[i + 1]].let { Vec3(it.x, it.y, it.z) } + s.b.normals[idx[i + 2]].let { Vec3(it.x, it.y, it.z) }
            if (f.length > 1e-10f && f.dot(n) < 0f) bad++
            i += 3
        }
        assertEquals(0, bad)
    }
}
