package io.embodit.yarddrive.domain

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

    /** Steering wheel centre, rim radius, and the axis towards you (plane tilted back 25°). */
    val WHEEL_CENTRE = V3(0.0, -0.354, -0.42)
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
            b("mirHouse$n", V3(mx - 0.10, -0.255, -0.80), V3(mx + 0.10, -0.145, -0.66), Shade.FRAME)
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
