package io.embodit.yarddrive.domain

import java.util.Locale
import kotlin.math.abs

/**
 * What the wheel readouts say (profile D38): MRD-L, MRD-R and the HUB read the same state and word it the same way. No
 * state word is written in a panel; "centred" only comes out of the wheel angle here.
 */
object MrdReadout {
    const val CENTRED_DEG = 15.0

    /** The two parts of an MRD: the chant offset takes the whole panel; otherwise the corner gap (its side's segment) and the turns. */
    data class Content(val chantCm: Int?, val gap: Double?, val wheel: Double)

    fun turns(wheelDeg: Double): String = String.format(Locale.US, "%.1f", abs(wheelDeg) / 360.0)

    fun turnsText(wheelDeg: Double, zh: Boolean): String {
        val w = if (abs(wheelDeg) < CENTRED_DEG) Copy.hubCentred else if (wheelDeg < 0) Copy.hubLeft else Copy.hubRight
        return String.format(Locale.US, if (zh) w.zh else w.en, turns(wheelDeg))
    }

    /**
     * The corner gap one side's bay-in segment shows (design panels.MRD, profile D38): both sides through here, the segment
     * as the parameter. On its segment: the closer of what you have seen and the look-ahead; afterwards what you saw.
     */
    fun cornerGap(side: SegId, seen: Double?, seg: SegId, predictedSeg: SegId?, hold: Double?): Double? {
        val ahead = hold?.takeIf { seg == side && predictedSeg == seg }
        return when {
            seen == null -> ahead?.takeIf { it < 8 }
            ahead == null -> seen
            else -> kotlin.math.min(seen, ahead)
        }
    }

    /** P2's third column with the top view off: the bay-in you are in or have just come out of, the same on both sides (III and IV read the left). */
    fun topViewOffGap(seg: SegId?, gapL: Double?, gapR: Double?): Double? = if (seg == SegId.III || seg == SegId.IV) gapL else gapR

    private fun mine(left: Boolean, seg: SegId?) = if (left) seg == SegId.III else seg == SegId.I

    /** Driving: the corner gap on its side's bay-in segment (not in the mock exam, design S14). */
    fun driving(left: Boolean, seg: SegId?, mode: Mode, gapL: Double?, gapR: Double?, wheel: Double): Content =
        Content(null, if (mine(left, seg) && mode != Mode.MOCK) (if (left) gapL else gapR) else null, wheel)

    /** The library: the chant offset for this seat; a point with no chant reads like a run standing at that truth pose. */
    fun library(lp: LookPoint, left: Boolean, eyeH: Double, wheel: Double): Content {
        val cm = LookPoints.chantLateCm(lp.id, eyeH)
        return if (cm != null) Content(cm, null, wheel) else Content(null, if (mine(left, lp.seg)) lp.cornerGap else null, wheel)
    }
}
