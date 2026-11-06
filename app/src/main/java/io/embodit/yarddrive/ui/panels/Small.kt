package io.embodit.yarddrive.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.Copy
import io.embodit.yarddrive.domain.LookPoints
import io.embodit.yarddrive.domain.Mirrors
import io.embodit.yarddrive.domain.MrdReadout
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdUiState
import io.embodit.yarddrive.ui.scene.PanelSizes
import io.embodit.yarddrive.ui.theme.YdColors
import io.embodit.yarddrive.ui.theme.YdType
import java.util.Locale
import kotlin.math.abs

/** HUB: the wheel's turn count on the hub (design HUB, Ø160 dp at scale 0.6). Upright whatever the wheel does. */
@Composable
internal fun HubPanel(state: YdUiState?) {
    Glass(PanelSizes.HUB_D, PanelSizes.HUB_D, shape = CircleShape, padding = 10.dp) {
        if (state == null) return@Glass
        val w = state.run.wheel
        val lock = abs(w) >= Car.WHEEL_MAX - 1
        val txt = MrdReadout.turnsText(w, state.zh)
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Tag(txt, "hubTurns", YdType.hub.copy(fontSize = if (state.zh) YdType.hub.fontSize else YdType.hub.fontSize * 0.72f), if (abs(w) < MrdReadout.CENTRED_DEG) null else if (w < 0) YdColors.Left else YdColors.Right)
            Line(state.t(if (lock) Copy.hubLock else Copy.hubWheel), "hubSub", YdType.small)
        }
    }
}

/**
 * One line where you are looking: "look straight ahead" with its countdown while the car is being placed (profile D29), or,
 * on S01, which way the panel went when it is out of view (R5, D1).
 */
@Composable
internal fun HintPanel(state: YdUiState?) {
    Glass(PanelSizes.HINT_W, PanelSizes.HINT_H, shape = RoundedCornerShape(20.dp), padding = 12.dp) {
        if (state == null) return@Glass
        val text = when {
            state.reseating -> state.f(Copy.lookAhead, state.lookLeftS)
            state.panelAway < 0 -> state.t(Copy.panelLeft)
            state.panelAway > 0 -> state.t(Copy.panelRight)
            else -> return@Glass
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Line(text, "hint", YdType.headline) }
    }
}

/**
 * MRD-L / MRD-R: by each side mirror, the corner gap (look-ahead, its side's segment) and the turns, one per line, centred;
 * in the library the chant offset, or at a point with no chant the same two as a run at that truth pose (profile D38).
 */
@Composable
internal fun MrdPanel(state: YdUiState?, left: Boolean) {
    val value = if (left) YdType.mrd else YdType.mrdR
    val chant = if (left) YdType.mrd.copy(fontSize = YdType.mrd.fontSize * 0.8f) else YdType.mrdR
    Glass(if (left) PanelSizes.MRD_W else PanelSizes.MRD_R_W, if (left) PanelSizes.MRD_H else PanelSizes.MRD_R_H, shape = RoundedCornerShape(16.dp), padding = 6.dp, paddingV = if (left) 2.dp else 4.dp) {
        if (state == null) return@Glass
        val r = state.run
        val c =
            if (state.screen == Screen.POINT) MrdReadout.library(LookPoints.byN(state.lp), left, state.settings.eyeH, r.wheel)
            else MrdReadout.driving(left, r.segs.getOrNull(r.segIndex), r.mode, r.gapL, r.gapR, r.wheel)
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val cm = c.chantCm
            if (cm != null) {
                val t = if (state.zh) String.format(Copy.mrdChant.zh, if (cm < 0) "早" else "晚", abs(cm)) else String.format(Locale.US, Copy.mrdChant.en, abs(cm), if (cm < 0) "early" else "late")
                Line(t, "mrdChant", chant)
                return@Column
            }
            c.gap?.let { g ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Line(state.t(Copy.corner), "mrdCornerLabel", value)
                    Tag(String.format(Locale.US, "%.2f m", g), "mrdCorner", value, if (g < 0.10) YdColors.Warn else null, padV = 0.dp)
                }
            }
            val w = c.wheel
            Tag(MrdReadout.turnsText(w, state.zh), "mrdTurns", value, if (abs(w) < MrdReadout.CENTRED_DEG) null else if (w < 0) YdColors.Left else YdColors.Right, padV = 0.dp)
        }
    }
}

/** One of the three mirrors (design model.mirrors): an opaque canvas of the projected geometry (20 Hz). */
@Composable
internal fun MirrorPanel(pic: Mirrors.Picture?, wide: Boolean) {
    val w = if (wide) PanelSizes.MIRI_W else PanelSizes.MIR_W
    val h = if (wide) PanelSizes.MIRI_H else PanelSizes.MIR_H
    // clipped: when the mirror is tipped up the sky reaches its top corners, and they have to stay round as well
    Box(modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(if (wide) 10.dp else 18.dp)).background(YdColors.Asphalt)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sw = size.width
            val sh = size.height
            val hz = ((pic?.horizon ?: 0.3f) * sh).coerceIn(-sh, 2 * sh)
            if (hz > 0) drawRect(Brush.verticalGradient(listOf(YdColors.SkyTop, YdColors.SkyLow), 0f, hz.coerceAtLeast(1f)), size = androidx.compose.ui.geometry.Size(sw, hz.coerceAtMost(sh)))
            if (pic == null) return@Canvas
            for (p in pic.polys) {
                val pts = p.pts
                if (pts.size < 6) continue
                val path = Path()
                path.moveTo(pts[0] * sw, pts[1] * sh)
                var i = 2
                while (i < pts.size) {
                    path.lineTo(pts[i] * sw, pts[i + 1] * sh)
                    i += 2
                }
                path.close()
                drawPath(path, Color(p.color))
            }
        }
    }
}
