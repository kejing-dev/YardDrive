package io.embodit.yarddrive

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.yarddrive.ui.YdScreen
import io.embodit.yarddrive.ui.theme.YdTheme

/**
 * App entry (design container STAGE_FULL, skeleton C). The default Stage is Full (manifest `pico.spatial.stage.style=3`):
 * opening the app puts you in the exam car's driver seat at the bay-park yard; there is no Shared Space window.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { YdTheme { YdScreen() } }
    }
