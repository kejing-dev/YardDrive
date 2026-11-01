package io.embodit.yarddrive.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.embodit.yarddrive.platform.YdLog

/**
 * Debug builds only (action `io.embodit.yarddrive.DEBUG`), see [DebugCommands]:
 *
 *   adb -s emulator-5556 shell "am broadcast -a io.embodit.yarddrive.DEBUG -p io.embodit.yarddrive --es cmd 'goto S07 seg=1 pose=2.0,-0.5,45 steer=540'"
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        YdLog.i("CMD recv '$raw'")
        Handler(Looper.getMainLooper()).post { DebugCommands.run(raw) }
    }
}
