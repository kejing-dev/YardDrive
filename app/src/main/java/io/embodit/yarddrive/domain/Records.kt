package io.embodit.yarddrive.domain

import kotlin.math.abs

/** One finished run as kept on the device (design persistence: up to 300). */
data class RunRecord(
    val at: Long,
    val mode: Mode,
    val lesson: Int,
    val seed: Int,
    val difficulty: Int,
    val outcome: Outcome,
    val passed: Boolean,
    val score: Int,
    val stops: Int,
    val timeS: Double,
    val hits: List<Pair<String, Int>>,
    val gapR: Double?,
    val gapL: Double?,
    val nose: Double?,
    val looks: IntArray,
)

/** The scorecard (design content.scorecard, S16). */
object Stats {
    data class ModeRow(val mode: Mode, val runs: Int, val passRate10: Int?, val avgTimeS: Double?, val avgStops: Double?)

    fun rows(records: List<RunRecord>): List<ModeRow> =
        Mode.entries.map { m ->
            val rs = records.filter { it.mode == m }
            val last = rs.takeLast(10)
            ModeRow(
                m,
                rs.size,
                if (last.isEmpty()) null else last.count { it.passed } * 100 / last.size,
                rs.map { it.timeS }.takeIf { it.isNotEmpty() }?.average(),
                rs.map { it.stops.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
            )
        }

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** The look-point with the largest median |early / late| (signed median returned). */
    fun mostCommon(records: List<RunRecord>): Pair<String, Int>? {
        val by = HashMap<String, MutableList<Double>>()
        for (r in records) for ((lp, cm) in r.hits) by.getOrPut(lp) { ArrayList() } += cm.toDouble()
        val best = by.maxByOrNull { (_, v) -> abs(median(v) ?: 0.0) } ?: return null
        val m = median(best.value) ?: return null
        if (abs(m) < 1) return null
        return best.key to m.toInt()
    }

    fun medianGap(records: List<RunRecord>, right: Boolean): Double? = median(records.mapNotNull { if (right) it.gapR else it.gapL })
}
