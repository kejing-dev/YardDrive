package io.embodit.yarddrive.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's fixed colours (design palette.ui). The 16 ColorScheme roles stay PicoTheme defaults; these are state
 * colours only. Text never sits in a state colour straight on the glass unless it measures >= 4.5:1 there
 * (tools/contrast.py); fills carry dark ink (D13).
 */
object YdColors {
    val Ink = Color(0xFF111418) // design-style: fixed-figma-color ink on light fills
    val InteractionFill = Color(0xFF5A9CFF) // design-style: fixed-figma-color interaction fill (factory, traffic-signals v0.1.1)
    val Left = Color(0xFF7FB2FF) // design-style: fixed-figma-color palette.ui.left
    val Right = Color(0xFFFFA14A) // design-style: fixed-figma-color palette.ui.right
    val Cue = Color(0xFF5BD6C8) // design-style: fixed-figma-color palette.ui.cue
    val Warn = Color(0xFFE0A030) // design-style: fixed-figma-color palette.ui.warn
    val PassFill = Color(0xFF2E8B57) // design-style: fixed-figma-color palette.ui.pass (fill, ink on it)
    val PassText = Color(0xFF7FE0A8) // design-style: fixed-figma-color pass, lifted for text on glass (sample)
    val FailText = Color(0xFFFF8F7A) // design-style: fixed-figma-color fail, lifted for text on glass (sample)
    val Danger = Color(0xFFFF6B5E) // design-style: fixed-figma-color danger fill with ink (factory, 6.7:1)
    val LineRed = Color(0xFFFF3B30) // design-style: fixed-figma-color palette.ui.lineFlash
    val Asphalt = Color(0xFF4A4D52) // design-style: fixed-figma-color palette.yard.asphalt (canvases)
    val BayYellow = Color(0xFFF2C94C) // design-style: fixed-figma-color palette.yard.bayLine
    val LineWhite = Color(0xFFF2F2EE) // design-style: fixed-figma-color palette.yard.whiteLine
    val CarBody = Color(0xFFF4F5F2) // design-style: fixed-figma-color palette.yard.coachCar (top view car)
    val CarEdge = Color(0xFF1E2125) // design-style: fixed-figma-color palette.cabin.mirrorFrame
    val SkyTop = Color(0xFF9CC4E6) // design-style: fixed-figma-color palette.yard.sky
    val SkyLow = Color(0xFFEAF3FA) // design-style: fixed-figma-color palette.yard.sky
}
