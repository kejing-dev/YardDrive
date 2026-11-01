package io.embodit.yarddrive.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import io.embodit.yarddrive.platform.YdLog
import io.embodit.yarddrive.ui.debug.Probe
import io.embodit.yarddrive.ui.panels.YdStage
import io.embodit.yarddrive.ui.scene.YardFrameSystem
import io.embodit.yarddrive.ui.scene.YardScene

/** The one screen: wires [YdController] (state), [YardScene] (entities, per-frame work) and [YdStage] (panels). */
@Composable
fun YdScreen() {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        YdController.start(context)
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        YdController.activity = c as? Activity
        true
    }
    val state by YdController.state.collectAsStateWithLifecycle()
    val scene = remember { YardScene(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<YardFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): YardScene = scene }
        YdController.onQuit = {
            // D26: release tracking, audio and the scene before the process goes
            scene.detach()
        }
        val observer =
            LifecycleEventObserver { _, event ->
                YdLog.i("LIFECYCLE event=$event")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        YdController.onEvent(YdEvent.Background(true))
                        scene.pause()
                    }
                    Lifecycle.Event.ON_START -> scene.resume()
                    Lifecycle.Event.ON_RESUME -> if (YdController.eyeGranted()) scene.inputs.restartEyes()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        YdLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<YardFrameSystem>()
            scene.detach()
            YdController.sound.setLoops(false, false)
            YdLog.i("STAGE disposed")
        }
    }

    YdStage(state = state, scene = scene, onEvent = YdController::onEvent)
}
