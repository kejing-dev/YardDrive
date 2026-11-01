package io.embodit.yarddrive.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Head pose in Stage space. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    /** Yaw of the view direction (see [Yaw]); [fallback] when looking straight up or down. */
    fun yaw(fallback: Float = 0f): Float {
        val f = forward
        return if (hypot(f.x, f.z) < 1e-3f) fallback else Yaw.of(f)
    }

    /** Pitch of the view direction, radians (+ up). */
    val pitch: Float
        get() {
            val f = forward
            return atan2(f.y, hypot(f.x, f.z))
        }
}

/**
 * Yaw angles about the vertical, radians: 0 is Stage -Z (ahead at Stage start), positive turns left
 * (counter-clockwise seen from above, the right-hand rule about +Y).
 */
object Yaw {
    fun dir(yaw: Float): Vec3 = Vec3(-sin(yaw), 0f, -cos(yaw))

    fun of(v: Vec3): Float = atan2(-v.x, -v.z)

    /** Wraps to -pi..pi. */
    fun wrap(a: Float): Float {
        var d = a
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }
}

/** Turn of a panel towards the viewer: yaw about the vertical, then pitch, never roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector out of the panel's face, towards the viewer. */
    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))

    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    val up: Vec3
        get() = normal.cross(right)
}

/**
 * The panel faces the viewer: yaw from the horizontal direction, pitch up to [maxPitch] (labels high in
 * the sky tilt down a long way). Within 10 cm horizontally the last yaw is kept.
 */
object FacePanel {
    private const val KEEP_YAW_WITHIN_M = 0.1f

    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?, maxPitch: Float = 1.0471976f): PanelFacing {
        val to = head - panel
        val flat = hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else atan2(to.x, to.z)
        val pitch = atan2(to.y, flat).coerceIn(-maxPitch, maxPitch)
        return PanelFacing(yaw, pitch)
    }
}

/**
 * The one placement function (profile D9 hard gate): the driver frame is built from the head position and its yaw only;
 * pitch and roll of the head never reach any panel or the scene. Every place / re-centre / lane switch goes through here.
 */
object Upright {
    /** A head pose that is all zeros or the identity rotation at the origin is "tracking not ready" (profile D1). */
    fun valid(p: HeadPose?): Boolean {
        if (p == null || !p.position.isFinite()) return false
        val zeroPos = p.position.length < 1e-4f
        val identity = abs(p.rotation.w) > 0.99999f
        return !(zeroPos && identity) && !zeroPos
    }

    /** Driver frame: origin on the road under the eye, yaw of the head; returns (road origin, yaw, eye height). */
    fun frame(head: HeadPose, eyeHeight: Float): Triple<Vec3, Float, Float> {
        val yaw = head.yaw(0f)
        val eye = head.position
        return Triple(Vec3(eye.x, eye.y - eyeHeight, eye.z), yaw, eyeHeight)
    }

    /** A point given in the driver frame (x right, y up, -z ahead) to Stage space. */
    fun toStage(origin: Vec3, yaw: Float, local: Vec3): Vec3 = origin + Quat4.axisAngle(Vec3.UP, yaw).rotate(local)

    /** Rotation of an upright panel at [panel] turned (yaw only) to look at [eye]: pitch = roll = 0. */
    fun panelRotation(panel: Vec3, eye: Vec3): Quat4 {
        val to = eye - panel
        val yaw = if (hypot(to.x, to.z) < 1e-4f) 0f else atan2(to.x, to.z)
        return Quat4.axisAngle(Vec3.UP, yaw)
    }
}
