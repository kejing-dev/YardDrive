package io.embodit.yarddrive.platform

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import io.embodit.yarddrive.domain.HeadPose
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Vec3
import java.util.concurrent.atomic.AtomicReference

/**
 * Controllers and eyes (design container.mrCapabilities), all Full-Space-only providers. Listeners only store
 * values; the frame system reads them (no tracking data goes through Compose state, design ecs.rule). Debug commands can
 * override every value (QA harness: the emulator has no controllers).
 */
class Inputs {
    data class Buttons(
        val a: Boolean = false,
        val x: Boolean = false,
        val y: Boolean = false,
        val stickLX: Float = 0f,
    )

    private var controller: ControllerTrackingProvider? = null
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
                        x = d.left.xButtonPressed,
                        y = d.left.yButtonPressed,
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
        runCatching { eyes?.stop() }
        eyes = null
        buttons.set(Buttons())
    }

    val now: Buttons get() = debugButtons ?: buttons.get()

    val controllerSupported: Boolean
        get() = runCatching { controller?.supportState == DataProvider.SupportState.SUPPORTED }.getOrDefault(false)

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
