package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

/**
 * The one placement function (profile D9 hard gate, design container.placement): "I'm seated" and "Re-seat" put the
 * cabin at the current head position with the head's yaw only; pitch and roll never reach the car or any panel on it.
 * The cabin frame = the design's cabin coordinates (origin = eye, -Z = car front, +X right, +Y up).
 */
data class Seat(val eye: Vec3, val yaw: Float, val debugPitch: Float = 0f) {
    /** [debugPitch] is only the debug `view yaw,pitch` (the emulator camera cannot look down); always 0 otherwise. */
    private val rot: Quat4 get() = if (debugPitch == 0f) Quat4.axisAngle(Vec3.UP, yaw) else Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, debugPitch)

    /** Rotation of the cabin frame in the Stage. */
    val rotation: Quat4 get() = rot

    fun toStage(local: Vec3): Vec3 = eye + rot.rotate(local)

    fun toStageDir(local: Vec3): Vec3 = rot.rotate(local)

    fun toCabin(stage: Vec3): Vec3 = rot.conjugate().rotate(stage - eye)

    fun toCabinDir(stage: Vec3): Vec3 = rot.conjugate().rotate(stage)

    /** Normal of a panel placed at [yawDeg] / [pitchDeg] from the eye, turned back to face the eye. */
    fun panelNormalStage(yawDeg: Double, pitchDeg: Double): Vec3 = toStageDir(-dir(yawDeg, pitchDeg))

    /** Degrees the stage yaw [yaw] looks to the right of this seat's car axis. */
    fun lookRightDeg(yaw: Float): Double {
        val d = toCabinDir(Yaw.dir(yaw))
        return Math.toDegrees(atan2(d.x.toDouble(), -d.z.toDouble()))
    }


    companion object {
        /** All-zero position or the identity rotation at the origin = tracking not ready yet (profile D1). */
        fun valid(p: HeadPose?): Boolean = Upright.valid(p)

        fun place(head: HeadPose): Seat = Seat(head.position, head.yaw(0f))

        /** Median of a short window of head yaws (radians), safe across the +-pi seam. */
        fun medianYaw(list: List<HeadPose>): Float {
            val y0 = list.first().yaw(0f)
            val d = list.map { Yaw.wrap(it.yaw(y0) - y0) }.sorted()
            return Yaw.wrap(y0 + d[d.size / 2])
        }

        fun medianPosition(list: List<HeadPose>): Vec3 {
            val xs = list.map { it.position.x }.sorted()
            val ys = list.map { it.position.y }.sorted()
            val zs = list.map { it.position.z }.sorted()
            return Vec3(xs[xs.size / 2], ys[ys.size / 2], zs[zs.size / 2])
        }

        /** The car placed from a short window of head samples: eye and yaw, nothing else, no dead zone (profile D9, D29). */
        fun settled(samples: List<HeadPose>): Seat = Seat(medianPosition(samples), medianYaw(samples))

        /** The head has turned slower than [STILL_DEG_S] for the last [STILL_MS] ms ([samples]: time ms, pose; oldest first). */
        fun still(samples: List<Pair<Long, HeadPose>>, nowMs: Long): Boolean {
            val from = samples.indexOfLast { nowMs - it.first >= STILL_MS }
            if (from < 0) return false
            for (i in from until samples.size - 1) {
                val (t0, a) = samples[i]
                val (t1, b) = samples[i + 1]
                val dt = (t1 - t0) / 1000.0
                if (dt <= 0) continue
                val d = abs(Math.toDegrees(Yaw.wrap(b.yaw(a.yaw(0f)) - a.yaw(0f)).toDouble()))
                if (d / dt > STILL_DEG_S) return false
            }
            return true
        }

        // panel spots in the cabin (design panels[].placement)
        const val P1_DIST = 1.05
        const val P1_YAW_DEG = 6.0
        const val P1_PITCH_DEG = -10.0

        const val STILL_DEG_S = 5.0
        const val STILL_MS = 500L

        /** HUB: fixed in the car, not on the wheel (design HUB 0.55 m, about -40°; profile D34: not below -40°). */
        const val HUB_DIST = 0.55
        const val HUB_PITCH_DEG = -39.0
        const val P2_DIST = 1.10
        const val P2_PITCH_R = -15.0
        const val P2_PITCH_D = 1.0

        /** Unit direction from the eye: [yawDeg] + to the right, [pitchDeg] + up (cabin). */
        fun dir(yawDeg: Double, pitchDeg: Double): Vec3 {
            val y = Math.toRadians(yawDeg)
            val p = Math.toRadians(pitchDeg)
            return Vec3((sin(y) * cos(p)).toFloat(), sin(p).toFloat(), (-cos(y) * cos(p)).toFloat())
        }

        fun p1Local(): Vec3 = dir(P1_YAW_DEG, P1_PITCH_DEG) * P1_DIST.toFloat()

        fun p2Local(pitchDeg: Double): Vec3 = dir(0.0, pitchDeg) * P2_DIST.toFloat()

        fun hubLocal(): Vec3 = dir(0.0, HUB_PITCH_DEG) * HUB_DIST.toFloat()

        /** Readouts by the side mirrors: inside the door (its inner face is x -0.44 / +1.18), below the sill line (profile D35). */
        val MRD_L = Vec3(-0.38f, -0.26f, -0.60f)
        val MRD_R = Vec3(1.09f, -0.26f, -0.60f)

        /**
         * The one-line hint: 0.6 m out at -10° (nearer than the A pillars and the mirror housings; drawn at scale [HINT_SCALE] so it
         * looks the size it would at 1 m), at most this far round from the car axis (behind that are the B pillars and seats, D35).
         */
        const val HINT_DIST = 0.6
        const val HINT_SCALE = 0.48f
        const val HINT_PITCH_DEG = -10.0
        const val HINT_MAX_DEG = 100.0

        /** Hint spot from the head: [lookDeg] is the head yaw right of the car axis. Cabin direction. */
        fun hintDir(lookDeg: Double): Vec3 = dir(lookDeg.coerceIn(-HINT_MAX_DEG, HINT_MAX_DEG), HINT_PITCH_DEG) * HINT_DIST.toFloat()
    }
}

/**
 * The one path of "I'm seated" and "Re-seat" (profile D29). At the moment of a press nothing can tell whether you are looking
 * at the button or straight ahead, so the button goes away: P1 is hidden, one line says "look straight ahead", and after at
 * least [MIN_MS], with the head slower than [Seat.STILL_DEG_S] for [Seat.STILL_MS] (or after [MAX_MS]), the car is put
 * wherever the head then points. Unconditionally: no dead zone. Leaving the page or starting to drive cancels it.
 */
class SeatFlow {
    sealed class Step {
        data object Idle : Step()

        /** Still waiting; about [leftS] seconds to go if the head stays still. */
        data class Waiting(val leftS: Double) : Step()

        data class Place(val seat: Seat) : Step()

        /** Nothing placed; [lost]: because head tracking stopped (the page says press again). */
        data class Cancelled(val lost: Boolean = false) : Step()
    }

    private var fromMs = 0L
    private var stillFromMs = 0L
    private var key = ""
    var active = false
        private set

    /** A press on any placing button; [pageKey] says which page it was pressed on. A press during a wait changes nothing. */
    fun start(nowMs: Long, pageKey: String) {
        if (active) return
        active = true
        fromMs = nowMs
        stillFromMs = nowMs
        key = pageKey
    }

    fun cancel() {
        active = false
    }

    /** One frame. [samples]: about the last second of the head, oldest first (time ms, pose). */
    fun step(nowMs: Long, samples: List<Pair<Long, HeadPose>>, pageKey: String, driving: Boolean): Step {
        if (!active) return Step.Idle
        if (pageKey != key || driving) {
            active = false
            return Step.Cancelled()
        }
        // tracking stopped: the last sample is where the head was, not where it is (D9)
        if (nowMs - (samples.lastOrNull()?.first ?: fromMs) > STALE_MS) {
            active = false
            return Step.Cancelled(lost = true)
        }
        val waited = nowMs - fromMs
        val still = Seat.still(samples, nowMs)
        if (!still && moved(samples)) stillFromMs = nowMs
        if (waited >= MIN_MS && (still || waited >= MAX_MS)) {
            val last = samples.filter { nowMs - it.first <= Seat.STILL_MS }.ifEmpty { samples.takeLast(1) }.map { it.second }
            active = false
            return if (last.isEmpty()) Step.Cancelled(lost = true) else Step.Place(Seat.settled(last))
        }
        val left = max(fromMs + MIN_MS, stillFromMs + Seat.STILL_MS) - nowMs
        return Step.Waiting(min(left, fromMs + MAX_MS - nowMs).coerceAtLeast(0L) / 1000.0)
    }

    private fun moved(samples: List<Pair<Long, HeadPose>>): Boolean {
        if (samples.size < 2) return false
        val (t0, a) = samples[samples.size - 2]
        val (t1, b) = samples[samples.size - 1]
        val dt = (t1 - t0) / 1000.0
        return dt > 0 && abs(Math.toDegrees(Yaw.wrap(b.yaw(a.yaw(0f)) - a.yaw(0f)).toDouble())) / dt > Seat.STILL_DEG_S
    }

    companion object {
        const val MIN_MS = 1_200L
        const val MAX_MS = 5_000L
        const val STALE_MS = 500L
    }
}

/**
 * The car's first place: valid head poses for a short window at the start, once. Nothing moves it after that but the placing
 * flow: no correction while you look around on S01 (profile D21, D1).
 */
class StartSeat {
    private val samples = ArrayList<HeadPose>()
    private var fromMs = 0L
    var done = false
        private set

    /** One frame's head pose (null: none yet); the seat the one time it is ready, else null. */
    fun offer(nowMs: Long, pose: HeadPose?): Seat? {
        if (done || !Seat.valid(pose)) return null
        if (fromMs == 0L) fromMs = nowMs
        samples += pose!!
        if (nowMs - fromMs < WINDOW_MS || samples.size < 2) return null
        done = true
        return Seat.settled(samples)
    }

    fun reset() {
        done = false
        fromMs = 0L
        samples.clear()
    }

    companion object {
        const val WINDOW_MS = 300L
    }
}
