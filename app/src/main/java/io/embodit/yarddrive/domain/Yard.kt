package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A line segment on the yard, centre line (design: lines are judged by their centre, touching it is not "out"). */
data class Seg2(val a: P2, val b: P2)

/** The lines a body may not cross (design yard.judgeLines). */
enum class JudgeLine(val zh: String, val en: String) {
    BAY_LEFT("左库边线", "left bay line"),
    BAY_RIGHT("右库边线", "right bay line"),
    BAY_BACK("库底线", "back line"),
    EXT_LEFT("库前线延长线", "bay front line"),
    EXT_RIGHT("库前线延长线", "bay front line"),
    ROAD("道路边线", "road edge"),
    LANE_LEFT("通道左线", "left lane line"),
    LANE_RIGHT("通道右线", "right lane line"),
}

/** Paint on the yard for the scene (design yard.lines). */
data class Paint(val seg: Seg2, val yellow: Boolean, val dashed: Boolean = false, val width: Double = Yard.LINE_W, val aux: Boolean = false)

/**
 * GA 1026-2017 bay-park yard for this one car (design model.yard): bay 5.20 x 2.30, lane 6.75, control lines 6.75 from the
 * bay lines. All numbers are the design's, not recomputed.
 */
object Yard {
    const val BAY_HALF = 1.15
    const val BAY_DEPTH = 5.20
    const val ROAD_Z = -6.75
    const val CONTROL_X = 7.90
    const val PAINT_END = 12.9
    const val LINE_W = 0.15
    const val CTRL_EXT_TO = -9.75

    /** Lessons 3-5 (and Easy in free practice) judge the bay 0.2 m wider; the paint does not move (gate B). */
    const val LENIENT_EXTRA = 0.20

    val GAPS = doubleArrayOf(1.5, 1.8, 2.0)
    const val GAP_DEFAULT = 1.8

    val poles = listOf(P2(-BAY_HALF, 0.0), P2(BAY_HALF, 0.0), P2(-BAY_HALF, BAY_DEPTH), P2(BAY_HALF, BAY_DEPTH))

    val paint: List<Paint> =
        listOf(
            Paint(Seg2(P2(-BAY_HALF, 0.0), P2(-BAY_HALF, BAY_DEPTH)), true),
            Paint(Seg2(P2(BAY_HALF, 0.0), P2(BAY_HALF, BAY_DEPTH)), true),
            Paint(Seg2(P2(-BAY_HALF, BAY_DEPTH), P2(BAY_HALF, BAY_DEPTH)), true),
            Paint(Seg2(P2(-BAY_HALF, 0.0), P2(BAY_HALF, 0.0)), false, dashed = true),
            Paint(Seg2(P2(-PAINT_END, 0.0), P2(-BAY_HALF, 0.0)), false),
            Paint(Seg2(P2(BAY_HALF, 0.0), P2(PAINT_END, 0.0)), false),
            Paint(Seg2(P2(-PAINT_END, ROAD_Z), P2(PAINT_END, ROAD_Z)), false),
            Paint(Seg2(P2(-CONTROL_X, 0.0), P2(-CONTROL_X, ROAD_Z)), false),
            Paint(Seg2(P2(CONTROL_X, 0.0), P2(CONTROL_X, ROAD_Z)), false),
            Paint(Seg2(P2(-CONTROL_X, ROAD_Z), P2(-CONTROL_X, CTRL_EXT_TO)), false, dashed = true, width = 0.08, aux = true),
            Paint(Seg2(P2(CONTROL_X, ROAD_Z), P2(CONTROL_X, CTRL_EXT_TO)), false, dashed = true, width = 0.08, aux = true),
        )

    /** Judge lines, widened by [extra] on each side of the bay (0 = exam). */
    fun judgeLines(extra: Double = 0.0): Map<JudgeLine, Seg2> {
        val h = BAY_HALF + extra / 2
        return linkedMapOf(
            JudgeLine.BAY_LEFT to Seg2(P2(-h, 0.0), P2(-h, BAY_DEPTH)),
            JudgeLine.BAY_RIGHT to Seg2(P2(h, 0.0), P2(h, BAY_DEPTH)),
            JudgeLine.BAY_BACK to Seg2(P2(-h, BAY_DEPTH), P2(h, BAY_DEPTH)),
            JudgeLine.EXT_LEFT to Seg2(P2(-PAINT_END, 0.0), P2(-h, 0.0)),
            JudgeLine.EXT_RIGHT to Seg2(P2(h, 0.0), P2(PAINT_END, 0.0)),
            JudgeLine.ROAD to Seg2(P2(-PAINT_END, ROAD_Z), P2(PAINT_END, ROAD_Z)),
        )
    }

    // ---- lesson 2 straight lane (design entities3d.laneLesson2): 2.3 m wide along the right start, about 10 m long
    const val LANE_X0 = -2.0
    const val LANE_X1 = 9.2

    fun laneZ(gap: Double) = -(gap + Car.HALF_W)

    fun laneLines(gap: Double): Map<JudgeLine, Seg2> {
        val zc = laneZ(gap)
        return linkedMapOf(
            JudgeLine.LANE_LEFT to Seg2(P2(LANE_X0, zc - BAY_HALF), P2(LANE_X1, zc - BAY_HALF)),
            JudgeLine.LANE_RIGHT to Seg2(P2(LANE_X0, zc + BAY_HALF), P2(LANE_X1, zc + BAY_HALF)),
        )
    }

    /** Lane cross lines: start (rear bumper at the start) and the 6 m mark. */
    fun laneMarks(gap: Double): List<Seg2> {
        val zc = laneZ(gap)
        val start = RIGHT_START_X - Car.REAR
        return listOf(start, start - 6.0).map { x -> Seg2(P2(x, zc - BAY_HALF), P2(x, zc + BAY_HALF)) }
    }

    const val RIGHT_START_X = 5.45

    fun rightStart(gap: Double = GAP_DEFAULT) = Pose(RIGHT_START_X, -(gap + Car.HALF_W), 0.0)

    fun leftStart(gap: Double = GAP_DEFAULT) = Pose(-RIGHT_START_X, -(gap + Car.HALF_W), Math.PI)

    /** In the bay, stopped (start of II / IV). */
    val inBay = Pose(0.04, 3.80, Math.toRadians(90.0))
}

/** Geometry helpers on the yard plane. */
object Geo {
    private fun orient(a: P2, b: P2, c: P2): Double = (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)

    /** Proper crossing of two segments (touching an end point or lying on the line is not a crossing). */
    fun crosses(p1: P2, p2: P2, p3: P2, p4: P2): Boolean {
        val d1 = orient(p3, p4, p1)
        val d2 = orient(p3, p4, p2)
        val d3 = orient(p1, p2, p3)
        val d4 = orient(p1, p2, p4)
        return d1 * d2 < -1e-12 && d3 * d4 < -1e-12
    }

    fun pointSeg(p: P2, a: P2, b: P2): Double {
        val d = b - a
        val l2 = d.dot(d)
        val t = if (l2 < 1e-12) 0.0 else ((p - a).dot(d) / l2).coerceIn(0.0, 1.0)
        return (a + d * t - p).length
    }

    /** Nearest point of segment ab to p. */
    fun nearestOnSeg(p: P2, a: P2, b: P2): P2 {
        val d = b - a
        val l2 = d.dot(d)
        val t = if (l2 < 1e-12) 0.0 else ((p - a).dot(d) / l2).coerceIn(0.0, 1.0)
        return a + d * t
    }

    fun inside(p: P2, poly: List<P2>): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val o = orient(poly[i], poly[(i + 1) % poly.size], p)
            val s = if (o > 0) 1 else if (o < 0) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Distance from a convex polygon to a segment; 0 when they cross or the segment end is inside. */
    fun polySeg(poly: List<P2>, a: P2, b: P2): Double {
        val n = poly.size
        for (i in 0 until n) if (crosses(poly[i], poly[(i + 1) % n], a, b)) return 0.0
        if (inside(a, poly) && !onBoundary(a, poly) || inside(b, poly) && !onBoundary(b, poly)) return 0.0
        var d = Double.MAX_VALUE
        for (p in poly) d = min(d, pointSeg(p, a, b))
        for (i in 0 until n) {
            d = min(d, pointSeg(a, poly[i], poly[(i + 1) % n]))
            d = min(d, pointSeg(b, poly[i], poly[(i + 1) % n]))
        }
        return d
    }

    /** The body is over the line: an edge properly crosses it or an end of it is strictly inside (touching is not). */
    fun over(poly: List<P2>, a: P2, b: P2): Boolean {
        val n = poly.size
        for (i in 0 until n) if (crosses(poly[i], poly[(i + 1) % n], a, b)) return true
        return strictlyInside(a, poly) || strictlyInside(b, poly)
    }

    private fun strictlyInside(p: P2, poly: List<P2>): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val o = orient(poly[i], poly[(i + 1) % poly.size], p)
            if (kotlin.math.abs(o) < 1e-9) return false
            val s = if (o > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun onBoundary(p: P2, poly: List<P2>): Boolean = (poly.indices).any { pointSeg(p, poly[it], poly[(it + 1) % poly.size]) < 1e-9 }

    fun pointPoly(p: P2, poly: List<P2>): Double {
        if (inside(p, poly)) return 0.0
        var d = Double.MAX_VALUE
        for (i in poly.indices) d = min(d, pointSeg(p, poly[i], poly[(i + 1) % poly.size]))
        return d
    }

    /** Where (roughly) a body crosses a line: the crossing point of the first crossing edge, else the nearest corner. */
    fun touchPoint(poly: List<P2>, a: P2, b: P2): P2 {
        val n = poly.size
        for (i in 0 until n) {
            val p = poly[i]
            val q = poly[(i + 1) % n]
            if (crosses(p, q, a, b)) {
                val d1 = orient(a, b, p)
                val d2 = orient(a, b, q)
                val t = d1 / (d1 - d2)
                return p + (q - p) * t
            }
        }
        return poly.minByOrNull { pointSeg(it, a, b) } ?: poly[0]
    }

    fun clampAbs(v: Double, m: Double) = max(-m, min(m, v))

    fun absMin(a: Double, b: Double) = if (abs(a) < abs(b)) a else b
}
