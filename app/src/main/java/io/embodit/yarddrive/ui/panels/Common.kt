package io.embodit.yarddrive.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.yarddrive.domain.Tr
import io.embodit.yarddrive.platform.YdLog
import io.embodit.yarddrive.ui.YdUiState
import io.embodit.yarddrive.ui.debug.LocalProbePanel
import io.embodit.yarddrive.ui.debug.probe
import io.embodit.yarddrive.ui.theme.YdColors
import io.embodit.yarddrive.ui.theme.YdType

internal val GlassShape = RoundedCornerShape(28.dp)

internal fun YdUiState.t(tr: Tr): String = tr.of(zh)

internal fun YdUiState.f(tr: Tr, vararg args: Any): String = String.format(java.util.Locale.US, tr.of(zh), *args)

/**
 * Logs `OVERFLOW <panel>/<name> text=…` when a fixed-line text does not fit (profile D13 gate: every fixed-line text hangs
 * one of these; tools/fit_sweep.sh runs every state in the longest language with the widest values and expects none).
 */
@Composable
internal fun fits(name: String, text: String): (TextLayoutResult) -> Unit {
    val panel = LocalProbePanel.current
    return { r -> if (r.hasVisualOverflow) YdLog.w("OVERFLOW $panel/$name text=\"$text\" lines=${r.lineCount} w=${r.size.width}") }
}

/** One fixed-line text with the overflow log (never an ellipsis: clipped text is a bug, D13). */
@Composable
internal fun Line(text: String, name: String, style: TextStyle, color: Color = PicoTheme.colorScheme.labelPrimary, modifier: Modifier = Modifier, lines: Int = 1, align: TextAlign? = null) {
    Text(text = text, style = style, color = color, maxLines = lines, overflow = TextOverflow.Clip, softWrap = lines > 1, textAlign = align ?: TextAlign.Unspecified, modifier = modifier, onTextLayout = fits(name, text))
}

/** Glass root of P1 / P2 / HUB / MRD (SpatialUI system glass, design surface). */
@Composable
internal fun Glass(w: Float, h: Float, shape: Shape = GlassShape, padding: Dp = 24.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.size(w.dp, h.dp).clip(shape).backgroundMaterial(enable = true, style = Material.Regular).then(glassRefresh())) {
        Column(modifier = Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

enum class KeyKind { NORMAL, PRIMARY, DANGER, LEFT, RIGHT, ON }

/** A text button (optionally with an icon); every key carries words (B19), height >= 48 dp. */
@Composable
internal fun Key(
    text: String,
    name: String,
    icon: Int? = null,
    kind: KeyKind = KeyKind.NORMAL,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    onClick: () -> Unit,
) {
    // D13: coloured keys are the colour as the FILL with dark ink on it, never coloured text on the grey fill
    val container = when (kind) {
        KeyKind.PRIMARY, KeyKind.ON -> YdColors.InteractionFill
        KeyKind.DANGER -> YdColors.Danger
        KeyKind.LEFT -> YdColors.Left
        KeyKind.RIGHT -> YdColors.Right
        KeyKind.NORMAL -> PicoTheme.colorScheme.fillTertiary
    }
    val ink = if (kind == KeyKind.NORMAL) PicoTheme.colorScheme.labelPrimary else YdColors.Ink
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.probe(name).height(height),
        size = ButtonDefaults.Regular.copy(height = height),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = ink),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
        }
        Line(text, name, style ?: YdType.label, color = ink)
    }
}

@Composable
internal fun Chip(text: String, name: String, fill: Color = PicoTheme.colorScheme.fillSecondary, ink: Color = PicoTheme.colorScheme.labelPrimary) {
    Box(modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(fill).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Line(text, name, YdType.small, color = ink)
    }
}

@Composable
internal fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = YdColors.InteractionFill,
        selectedItemContentColor = YdColors.Ink,
    )

/** sui-segment-control with fixed item width; each label has the overflow log. */
@Composable
internal fun Seg(items: List<Pair<String, Boolean>>, name: String, itemW: Dp, enabled: Boolean = true, onPick: (Int) -> Unit) {
    SegmentControl(modifier = Modifier.width(itemW * items.size + 8.dp).height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (label, selected) ->
            SegmentItem(
                selected = selected,
                onClick = { if (enabled) onPick(i) },
                modifier = Modifier.width(itemW).fillMaxHeight().probe("${name}_$i"),
                textStyle = YdType.label,
                colors = segColors(),
                title = { Line(label, "${name}_$i", YdType.label, color = if (selected) YdColors.Ink else PicoTheme.colorScheme.labelPrimary) },
            )
        }
    }
}

@Composable
internal fun Toggle(on: Boolean, name: String, onChange: (Boolean) -> Unit) {
    Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.probe(name))
}

/** Panel title row (title + optional sub line, trailing keys). */
@Composable
internal fun TopBar(title: String, sub: String?, subLines: Int = 1, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(if (subLines > 1) 82.dp else 64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Line(title, "title", YdType.headline)
            if (sub != null) Line(sub, "titleSub", YdType.small, color = PicoTheme.colorScheme.labelPrimary, lines = subLines)
        }
        trailing()
    }
}

/** A labelled settings / form row, >= 56 dp high. */
@Composable
internal fun FormRow(label: String, sub: String?, name: String, modifier: Modifier = Modifier, control: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Line(label, name, YdType.label)
            if (sub != null) Line(sub, "${name}_sub", YdType.small.copy(fontSize = YdType.small.fontSize * 0.9f), color = PicoTheme.colorScheme.labelPrimary)
        }
        control()
    }
}

/** A filled block with dark ink (status chips that must read on any glass, D13). */
@Composable
internal fun Badge(text: String, name: String, fill: Color) = Chip(text, name, fill = fill, ink = YdColors.Ink)

/**
 * A state-coloured line: the state colour is the FILL, the text is dark ink on it (D13: the glass can be light or dark,
 * a coloured stroke on it is not safe; ink on these fills is >= 4.5:1, tools/contrast.py). [fill] null = plain label.
 */
@Composable
internal fun Tag(text: String, name: String, style: TextStyle, fill: Color?, lines: Int = 1, modifier: Modifier = Modifier) {
    if (fill == null) {
        Line(text, name, style, lines = lines, modifier = modifier)
        return
    }
    Box(modifier = modifier.clip(RoundedCornerShape(12.dp)).background(fill).padding(horizontal = 10.dp, vertical = 1.dp)) {
        Line(text, name, style, color = YdColors.Ink, lines = lines)
    }
}
