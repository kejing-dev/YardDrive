package io.embodit.yarddrive.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * The one placement function (profile D9 hard gate, design container.placement): "I'm seated" and "Re-seat" put the
 * cabin at the current head position with the head's yaw only; pitch and roll never reach the car or any panel on it.
 * The cabin frame = the design's cabin coordinates (origin = eye, -Z = car front, +X right, +Y up).
 */
data class Seat(val eye: Vec3, val yaw: Float) {
    private val rot: Quat4 get() = Quat4.axisAngle(Vec3.UP, yaw)

    /** Rotation of the cabin frame in the Stage. */
    val rotation: Quat4 get() = rot

    fun toStage(local: Vec3): Vec3 = eye + rot.rotate(local)

    fun toStageDir(local: Vec3): Vec3 = rot.rotate(local)

    fun toCabin(stage: Vec3): Vec3 = rot.conjugate().rotate(stage - eye)

    fun toCabinDir(stage: Vec3): Vec3 = rot.conjugate().rotate(stage)

    /** Normal of a panel placed at [yawDeg] / [pitchDeg] from the eye, turned back to face the eye. */
    fun panelNormalStage(yawDeg: Double, pitchDeg: Double): Vec3 = toStageDir(-dir(yawDeg, pitchDeg))

    companion object {
        /** All-zero position or the identity rotation at the origin = tracking not ready yet (profile D1). */
        fun valid(p: HeadPose?): Boolean = Upright.valid(p)

        fun place(head: HeadPose): Seat = Seat(head.position, head.yaw(0f))

        // panel spots in the cabin (design panels[].placement)
        const val P1_DIST = 1.05
        const val P1_YAW_DEG = 6.0
        const val P1_PITCH_DEG = -8.5
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
    }
}
