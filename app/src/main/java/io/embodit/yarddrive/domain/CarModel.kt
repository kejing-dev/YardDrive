package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The procedural car (design entities3d.cabin, ported box for box from apps/yard-drive/carmodel/build_yard.py): cabin
 * frame, origin = eye, -Z = front, default eye height 1.20 (the road at y = -1.20). Zero external models, so the D25
 * calibration is a test of these numbers against design model.car (bounding box, front direction, size).
 */
object CarModel {
    enum class Shade { DASH, TRIM, SEAT, HEADLINER, BODY, BUMPER, TIRE, FRAME }

    data class Box(val name: String, val a: V3, val b: V3, val shade: Shade) {
        val min: V3 get() = V3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z))
        val max: V3 get() = V3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z))
    }

    const val XL = -0.48
    const val XR = 1.22
    const val ZF = -2.00
    const val ZR = 2.50
    const val ROOF_Y = 0.22
    const val SILL_Y = -0.22
    const val G = -1.20

    /**
     * Steering wheel centre, rim radius, and the axis towards you (plane tilted back 25°).
     * B1: the design's rim top "-29.8°" came from shoulder-check (rim top at z -0.344, the tilt added towards you); the
     * mesh tilts the top away from you, as a real column does (rim top z -0.50), so at centre y -0.354 the silhouette
     * was really -19.2°, touching P1 / P2. The centre is lowered so the real silhouette is the design's -29.8°.
     */
    val WHEEL_CENTRE = V3(0.0, -0.465, -0.42)
    const val WHEEL_R = 0.18
    val WHEEL_AXIS = V3(0.0, kotlin.math.sin(Math.toRadians(25.0)), kotlin.math.cos(Math.toRadians(25.0)))
    val GEAR_KNOB = V3(0.37, -0.33, -0.30)

    /** Exterior shell + interior. Ordered so the scene can build them in one mesh per shade. */
    val boxes: List<Box> by lazy {
        val l = ArrayList<Box>()
        fun b(n: String, a: V3, c: V3, s: Shade) {
            l += Box(n, a, c, s)
        }
        b("hood", V3(XL, -0.55, -1.05), V3(XR, -0.45, ZF), Shade.BODY)
        b("frontFace", V3(XL, -1.0, ZF + 0.05), V3(XR, -0.45, ZF), Shade.BODY)
        b("dashTop", V3(XL + 0.03, -0.47, -0.60), V3(XR - 0.03, -0.38, -1.02), Shade.DASH)
        b("dashFace", V3(XL + 0.03, -0.80, -0.60), V3(XR - 0.03, -0.47, -0.70), Shade.DASH)
        b("console", V3(0.20, -0.75, -0.65), V3(0.54, -0.48, 0.30), Shade.TRIM)
        b("floor", V3(XL, G + 0.20, -1.0), V3(XR, G + 0.24, 2.0), Shade.TRIM)
        for (sx in doubleArrayOf(0.0, 0.74)) {
            b("seatBase$sx", V3(sx - 0.26, G + 0.45, 0.0), V3(sx + 0.26, G + 0.60, 0.55), Shade.SEAT)
            b("seatBack$sx", V3(sx - 0.26, G + 0.60, 0.25), V3(sx + 0.26, -0.18, 0.40), Shade.SEAT)
            b("headrest$sx", V3(sx - 0.10, -0.22, 0.30), V3(sx + 0.10, -0.08, 0.40), Shade.SEAT)
        }
        b("rearBench", V3(XL + 0.06, G + 0.45, 1.05), V3(XR - 0.06, G + 0.60, 1.55), Shade.SEAT)
        b("rearBack", V3(XL + 0.06, G + 0.60, 1.45), V3(XR - 0.06, -0.30, 1.62), Shade.SEAT)
        for ((sx, n) in listOf(XL to "L", XR to "R")) {
            val inner = sx + if (n == "L") 0.04 else -0.04
            b("door$n", V3(sx, G + 0.20, -1.05), V3(inner, SILL_Y, 0.35), Shade.DASH)
            b("doorR$n", V3(sx, G + 0.20, 0.40), V3(inner, SILL_Y, 1.90), Shade.DASH)
            // the body side: fender up to the bonnet in front of the windscreen, up to the sill line behind it
            b("fender$n", V3(sx, G + 0.20, ZF), V3(sx + if (n == "L") -0.01 else 0.01, -0.45, -1.02), Shade.BODY)
            b("side$n", V3(sx, G + 0.20, -1.02), V3(sx + if (n == "L") -0.01 else 0.01, SILL_Y, ZR), Shade.BODY)
            b("pillarB$n", V3(sx, SILL_Y, 0.33), V3(inner, ROOF_Y, 0.42), Shade.TRIM)
            b("pillarC$n", V3(sx, -0.30, 1.88), V3(inner, ROOF_Y, 2.10), Shade.TRIM)
            val mx = if (n == "L") -0.70 else 1.44
            b("mirHouse$n", V3(mx - 0.10, -0.255, -0.83), V3(mx + 0.10, -0.13, -0.69), Shade.FRAME) // lower edge is the chant datum (Car.MIRROR_EDGE_*), kept
            b("mirArm$n", V3(sx, -0.22, -0.76), V3(mx, -0.18, -0.70), Shade.FRAME)
        }
        b("roof", V3(XL, ROOF_Y, -0.52), V3(XR, ROOF_Y + 0.03, 1.92), Shade.BODY)
        b("headliner", V3(XL + 0.04, ROOF_Y - 0.01, -0.50), V3(XR - 0.04, ROOF_Y, 1.90), Shade.HEADLINER)
        b("trunk", V3(XL, -0.42, 2.28), V3(XR, -0.30, ZR), Shade.BODY)
        b("rearFace", V3(XL, G + 0.25, ZR - 0.05), V3(XR, -0.42, ZR), Shade.BODY)
        b("bumperR", V3(XL - 0.02, G + 0.30, ZR - 0.08), V3(XR + 0.02, G + 0.50, ZR + 0.02), Shade.BUMPER)
        b("bumperF", V3(XL - 0.02, G + 0.30, ZF - 0.02), V3(XR + 0.02, G + 0.50, ZF + 0.08), Shade.BUMPER)
        for (sx in doubleArrayOf(XL + 0.09, XR - 0.09)) for (zz in doubleArrayOf(-1.15, 1.45)) {
            b("tire${sx}_$zz", V3(sx - 0.095, G, zz - 0.31), V3(sx + 0.095, G + 0.62, zz + 0.31), Shade.TIRE)
        }
        l
    }

    /** The A pillars (rods from the dash corner to the roof front edge), cabin. */
    val pillarsA: List<Pair<V3, V3>> = listOf(V3(XL + 0.03, -0.38, -1.02) to V3(XL + 0.03, ROOF_Y, -0.52), V3(XR - 0.03, -0.38, -1.02) to V3(XR - 0.03, ROOF_Y, -0.52))

    /** The steering column: from behind the hub down along the wheel's axis to the dash. */
    val column: Pair<V3, V3> = (WHEEL_CENTRE - WHEEL_AXIS * 0.03) to (WHEEL_CENTRE - WHEEL_AXIS * 0.33)
    const val ROD_R = 0.035

    /** A part of the mesh that is not axis-aligned: centre, axes, half sizes. The scene builds from the same numbers. */
    data class Solid(val name: String, val c: V3, val x: V3, val y: V3, val z: V3, val hx: Double, val hy: Double, val hz: Double) {
        private fun local(p: V3) = (p - c).let { d -> V3(d.dot(x), d.dot(y), d.dot(z)) }

        fun contains(p: V3): Boolean = local(p).let { abs(it.x) < hx && abs(it.y) < hy && abs(it.z) < hz }

        /** Does the segment eye (origin) -> [p] go through the inside? */
        fun crossedBy(p: V3): Boolean {
            val a = local(V3(0.0, 0.0, 0.0))
            val b = local(p)
            var t0 = 0.0
            var t1 = 1.0
            for ((o, e, h) in listOf(Triple(a.x, b.x, hx), Triple(a.y, b.y, hy), Triple(a.z, b.z, hz))) {
                val d = e - o
                if (abs(d) < 1e-12) {
                    if (abs(o) >= h) return false
                    continue
                }
                val u = (-h - o) / d
                val v = (h - o) / d
                t0 = max(t0, min(u, v))
                t1 = min(t1, max(u, v))
                if (t0 >= t1) return false
            }
            return true
        }
    }

    /** The scene's rod: a square bar along a -> b, turned as Quat4.fromTo turns it. */
    fun rodSolid(name: String, a: V3, b: V3, r: Double = ROD_R): Solid {
        val d = b - a
        val q = Quat4.fromTo(Vec3(0f, 0f, 1f), d.normalized().f())
        fun axis(v: Vec3) = q.rotate(v).let { V3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
        return Solid(name, (a + b) * 0.5, axis(Vec3(1f, 0f, 0f)), axis(Vec3(0f, 1f, 0f)), axis(Vec3(0f, 0f, 1f)), r, r, d.length / 2)
    }

    private fun basis(name: String, c: V3, n: V3, hx: Double, hy: Double, hz: Double): Solid {
        val u = V3(0.0, 1.0, 0.0).cross(n).normalized()
        return Solid(name, c, u, n.cross(u), n, hx, hy, hz)
    }

    /** The frame plate behind a mirror face: 1.2 cm wider each way, 6...18 mm behind it (design says 4 mm back, deviation). */
    fun framePlate(spec: Mirrors.Spec, n: V3): Solid =
        basis("frame${spec.id}", spec.pos - n * 0.012, n, spec.wM / 2 + 0.012, spec.hM / 2 + 0.012, 0.006)

    /**
     * The narrow bar from the frame plate to the shell, so the side mirror does not float 5 cm in front of it. It runs
     * straight back along the mirror's normal, which keeps it behind the face from the eye (test strutsHideBehindTheMirror).
     */
    fun mirrorStrut(spec: Mirrors.Spec, n: V3): Solid {
        val from = 0.012
        val to = (spec.pos.z + 0.70) / n.z // 1 cm into the shell, whose rear is at z -0.69
        return basis("strut${spec.id}", spec.pos - n * ((from + to) / 2), n, 0.015, 0.010, (to - from) / 2)
    }

    /** Mesh that is not in [boxes], at this mirror aim (the settings' sliders). The wheel itself is PanelCone's business. */
    fun extrasAt(outDeg: Double, pitchDeg: Double): List<Solid> {
        val l = ArrayList<Solid>()
        pillarsA.forEachIndexed { i, (a, b) -> l += rodSolid("pillarA${if (i == 0) "L" else "R"}", a, b) }
        l += rodSolid("column", column.first, column.second)
        // the gear knob: rod and head, both gear positions
        l += Solid("gearKnob", GEAR_KNOB + V3(0.0, -0.03, 0.0), V3(1.0, 0.0, 0.0), V3(0.0, 1.0, 0.0), V3(0.0, 0.0, 1.0), 0.025, 0.07, 0.05)
        for (spec in Mirrors.all) {
            val n = Mirrors.normal(spec, Mirrors.aim(spec, outDeg, pitchDeg))
            l += framePlate(spec, n)
            if (spec.id != Mirrors.Id.I) l += mirrorStrut(spec, n)
        }
        return l
    }

    /** At the default aim. */
    val extras: List<Solid> by lazy { extrasAt(5.0, -16.0) }

    /** Outer body boxes (the shell that defines the car's size). */
    val shellNames = setOf("hood", "frontFace", "sideL", "sideR", "fenderL", "fenderR", "roof", "trunk", "rearFace", "bumperR", "bumperF")

    fun bounds(names: Set<String>? = null): Pair<V3, V3> {
        var lo = V3(1e9, 1e9, 1e9)
        var hi = V3(-1e9, -1e9, -1e9)
        for (bx in boxes) {
            if (names != null && bx.name !in names) continue
            val a = bx.min
            val c = bx.max
            lo = V3(min(lo.x, a.x), min(lo.y, a.y), min(lo.z, a.z))
            hi = V3(max(hi.x, c.x), max(hi.y, c.y), max(hi.z, c.z))
        }
        return lo to hi
    }
}
