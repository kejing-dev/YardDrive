package io.embodit.yarddrive.platform

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.yarddrive.domain.HeadPose
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Vec3
import java.util.concurrent.atomic.AtomicReference

/**
 * Controllers, hands and eyes (design container.mrCapabilities), all Full-Space-only providers. Listeners only store
 * values; the frame system reads them (no tracking data goes through Compose state, design ecs.rule). Debug commands can
 * override every value (QA harness: the emulator has no grips or pinches).
 */
class Inputs {
    data class Buttons(
        val a: Boolean = false,
        val b: Boolean = false,
        val x: Boolean = false,
        val y: Boolean = false,
        val gripL: Float = 0f,
        val gripR: Float = 0f,
        val stickLX: Float = 0f,
    )

    private var controller: ControllerTrackingProvider? = null
    private var hands: HandTrackingProvider? = null
    private var eyes: EyeTrackingProvider? = null
    private val buttons = AtomicReference(Buttons())
    private var eyeSince = 0L

    @Volatile var debugButtons: Buttons? = null
    @Volatile var debugGaze: HeadPose? = null
    @Volatile var eyeOff = false

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { d: ControllerActionData ->
            runCatching {
                buttons.set(
                    Buttons(
                        a = d.right.aButtonPressed,
                        b = d.right.bButtonPressed,
                        x = d.left.xButtonPressed,
                        y = d.left.yButtonPressed,
                        gripL = d.left.gripValue,
                        gripR = d.right.gripValue,
                        stickLX = d.left.thumbstickValue.x,
                    )
                )
            }
        }

    fun start() {
        if (controller == null) {
            val p = ControllerTrackingProvider()
            runCatching { p.addControllerActionListener(listener) }
            val r = runCatching { p.start() }
            YdLog.i("TRACK controller start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
            controller = p
        }
        if (hands == null) {
            val p = HandTrackingProvider()
            val r = runCatching { p.start() }
            YdLog.i("TRACK hand start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
            hands = p
        }
        startEyes()
    }

    /** Eye tracking needs the runtime permission; started again once it is granted. */
    fun startEyes() {
        if (eyes != null) return
        val p = EyeTrackingProvider()
        val r = runCatching { p.start() }
        YdLog.i("TRACK eye start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        eyes = p
        eyeSince = SystemClock.elapsedRealtime()
    }

    fun restartEyes() {
        runCatching { eyes?.stop() }
        eyes = null
        startEyes()
    }

    fun stop() {
        controller?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        controller = null
        runCatching { hands?.stop() }
        hands = null
        runCatching { eyes?.stop() }
        eyes = null
        buttons.set(Buttons())
    }

    val now: Buttons get() = debugButtons ?: buttons.get()

    /** Controller positions (Stage), or null. */
    fun controllerPose(right: Boolean): Vec3? {
        val p = controller ?: return null
        val d = runCatching { p.latestData }.getOrNull() ?: return null
        val pose = runCatching { if (right) d.right else d.left }.getOrNull() ?: return null
        return Vec3(pose.position.x, pose.position.y, pose.position.z).takeIf { it.isFinite() && it.length > 1e-4f }
    }

    val controllerSupported: Boolean
        get() = runCatching { controller?.supportState == DataProvider.SupportState.SUPPORTED }.getOrDefault(false)

    val handsSupported: Boolean
        get() = runCatching { hands?.supportState == DataProvider.SupportState.SUPPORTED }.getOrDefault(false)

    /** Pinch point (Stage) and value 0..1 of a hand, or null when not ready. */
    fun pinch(right: Boolean): Pair<Vec3, Float>? {
        val p = hands ?: return null
        val d = runCatching { p.latestData }.getOrNull() ?: return null
        val h = runCatching { if (right) d.rightPinch else d.leftPinch }.getOrNull() ?: return null
        if (!h.isReady) return null
        val v = h.pinchPose.position
        return Vec3(v.x, v.y, v.z) to h.pinchValue
    }

    /** Gaze (origin + rotation; the gaze runs along local -Z), or null: not granted, not supported, or no samples. */
    fun gaze(): HeadPose? {
        debugGaze?.let { return it }
        if (eyeOff) return null
        val p = eyes ?: return null
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val d = runCatching { p.latestData }.getOrNull() ?: return null
        val e = d.eyePose
        val pos = Vec3(e.position.x, e.position.y, e.position.z)
        if (!pos.isFinite() || (pos.length < 1e-4f && kotlin.math.abs(e.rotation.w) > 0.99999f)) return null
        return HeadPose(pos, Quat4(e.rotation.x, e.rotation.y, e.rotation.z, e.rotation.w))
    }

    fun eyeSupport(): String = runCatching { eyes?.supportState?.name }.getOrNull() ?: "null"
}
