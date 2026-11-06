package io.embodit.yarddrive.platform

import android.content.pm.PackageManager
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.yarddrive.ui.YdController

class LaunchActivity : SpatialLaunchActivity() {
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // an empty result = the dialog went away unanswered, that is not a "no"
        if (requestCode == YdController.EYE_REQUEST && grantResults.isNotEmpty()) YdController.onEyeAnswered(grantResults[0] == PackageManager.PERMISSION_GRANTED)
        YdLog.i("EYE result code=$requestCode results=${grantResults.toList()}")
    }
}
