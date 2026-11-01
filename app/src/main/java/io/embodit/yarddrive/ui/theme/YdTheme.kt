package io.embodit.yarddrive.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/** The app theme: SpatialUI Vibrant system colours (all 16 roles default, design palette.rule) + the Noto Sans family. */
@Composable
fun YdTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = systemColorScheme(context)
    val family = rememberYdFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
