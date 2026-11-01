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
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.SegId
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdUiState
import io.embodit.yarddrive.ui.scene.PanelSizes
import io.embodit.yarddrive.ui.theme.YdColors
import io.embodit.yarddrive.ui.theme.YdType
import java.util.Locale
import kotlin.math.abs

private fun turns(w: Double): String = String.format(Locale.US, "%.1f", abs(w) / 360.0)

/** HUB: the wheel's turn count on the hub (design HUB, Ø160 dp at scale 0.6). Upright whatever the wheel does. */
@Composable
internal fun HubPanel(state: YdUiState?) {
    Glass(PanelSizes.HUB_D, PanelSizes.HUB_D, shape = CircleShape, padding = 10.dp) {
        if (state == null) return@Glass
        val w = state.run.wheel
        val lock = abs(w) >= Car.WHEEL_MAX - 1
        val (txt, color) = when {
            abs(w) < 15 -> state.t(Copy.hubCentred) to PicoTheme.colorScheme.labelPrimary
            w < 0 -> state.f(Copy.hubLeft, turns(w)) to YdColors.Left
            else -> state.f(Copy.hubRight, turns(w)) to YdColors.Right
        }
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Tag(txt, "hubTurns", YdType.hub.copy(fontSize = if (state.zh) YdType.hub.fontSize else YdType.hub.fontSize * 0.72f), if (abs(w) < 15) null else color)
            Line(state.t(if (lock) Copy.hubLock else Copy.hubWheel), "hubSub", YdType.small)
        }
    }
}

/** MRD-L / MRD-R: by each side mirror, the corner gap (look-ahead) and the turns; in the library, the chant offset. */
@Composable
internal fun MrdPanel(state: YdUiState?, left: Boolean) {
    Glass(PanelSizes.MRD_W, PanelSizes.MRD_H, shape = RoundedCornerShape(16.dp), padding = 6.dp) {
        if (state == null) return@Glass
        val r = state.run
        if (state.screen == Screen.POINT) {
            // the library: the chant offset for this seat, full width
            val id = "LP${state.lp}"
            val cm = LookPoints.chantLateCm(id, state.settings.eyeH)
            val t = if (cm == null) state.t(Copy.hubWheel) + " · " + state.t(Copy.hubCentred) else if (state.zh) String.format(Copy.mrdChant.zh, if (cm < 0) "早" else "晚", abs(cm)) else String.format(Copy.mrdChant.en, abs(cm), if (cm < 0) "early" else "late")
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Line(t, "mrdChant", YdType.mrd.copy(fontSize = YdType.mrd.fontSize * 0.8f)) }
            return@Glass
        }
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.weight(1.15f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                run {
                    val seg = r.segs.getOrNull(r.segIndex)
                    val mine = if (left) seg == SegId.III else seg == SegId.I
                    val g = if (left) r.gapL else r.gapR
                    if (mine && r.mode != Mode.MOCK && g != null) {
                        Column {
                            Line(state.t(Copy.corner), "mrdCornerLabel", YdType.small.copy(fontSize = YdType.small.fontSize * 0.85f))
                            Tag(String.format(Locale.US, "%.2f m", g), "mrdCorner", YdType.mrd, if (g < 0.10) YdColors.Warn else null)
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Line(state.t(Copy.turns), "mrdTurnsLabel", YdType.small.copy(fontSize = YdType.small.fontSize * 0.85f))
                val w = r.wheel
                Tag(if (abs(w) < 15) state.t(Copy.hubCentred) else (if (w < 0) "L " else "R ") + turns(w), "mrdTurns", YdType.mrd, if (abs(w) < 15) null else if (w < 0) YdColors.Left else YdColors.Right)
            }
        }
    }
}

/** One of the three mirrors (design model.mirrors): an opaque canvas of the projected geometry (20 Hz). */
@Composable
internal fun MirrorPanel(pic: Mirrors.Picture?, wide: Boolean) {
    val w = if (wide) PanelSizes.MIRI_W else PanelSizes.MIR_W
    val h = if (wide) PanelSizes.MIRI_H else PanelSizes.MIR_H
    Box(modifier = Modifier.size(w.dp, h.dp).background(YdColors.Asphalt, RoundedCornerShape(if (wide) 10.dp else 18.dp))) {
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
