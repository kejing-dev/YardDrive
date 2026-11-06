package io.embodit.yarddrive.domain

import java.util.Random
import kotlin.math.abs

/**
 * Free practice (design content.freeMode): seed + level 1-3 start offsets; x only from -0.10 so the front axle always
 * stays past the control line. Level 3 also hints at a different seat ("the coach raised the seat this time").
 */
object Free {
    data class Start(val seed: Int, val difficulty: Int, val pose: Pose, val dx: Double, val dz: Double, val dPsiDeg: Double, val seat: Int?)

    fun start(seed: Int, difficulty: Int, gap: Double = Yard.GAP_DEFAULT): Start {
        val r = Random(seed * 31L + difficulty)
        val (x0, x1, zz, pp) =
            when (difficulty.coerceIn(1, 3)) {
                1 -> listOf(-0.05, 0.10, 0.10, 1.0)
                2 -> listOf(-0.10, 0.20, 0.20, 2.5)
                else -> listOf(-0.10, 0.30, 0.25, 4.0)
            }
        val dx = x0 + r.nextDouble() * (x1 - x0)
        val dz = (r.nextDouble() * 2 - 1) * zz
        val dp = (r.nextDouble() * 2 - 1) * pp
        val seat = if (difficulty >= 3) r.nextInt(3) else null
        val base = Yard.rightStart(gap)
        return Start(seed, difficulty, Pose(base.x + dx, base.z + dz, Math.toRadians(dp)), dx, dz, dp, seat)
    }

    /**
     * The run of one free-practice start (profile D30): a random seat goes into this run's config only, the settings are
     * never written. [Start.seat] equal to your own seat means nothing changes this time.
     */
    fun config(s: Settings, st: Start, easy: Boolean, lockX: Double): RunConfig =
        RunConfig(
            mode = Mode.FREE, rules = if (easy) Rules.LESSON else Rules.EXAM, lenient = easy, strict = s.strict, timed = !easy, gap = s.gap, start = st.pose, speed = s.speed, lockX = lockX,
            seed = st.seed, difficulty = st.difficulty, seat = st.seat?.takeIf { it != s.seat },
        )

    /** True when the rounded offsets are within the design ranges (U11 sanity). */
    fun inRange(s: Start): Boolean = s.dx >= -0.1001 && s.dx <= 0.3001 && abs(s.dz) <= 0.2501 && abs(s.dPsiDeg) <= 4.0001

    const val GROUP = 5
}
