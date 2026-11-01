package io.embodit.yarddrive.ui.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import io.embodit.yarddrive.domain.Mirrors
import io.embodit.yarddrive.platform.YdLog
import io.embodit.yarddrive.ui.YdEvent
import io.embodit.yarddrive.ui.YdUiState
import io.embodit.yarddrive.ui.debug.LocalProbePanel
import io.embodit.yarddrive.ui.debug.Probe
import io.embodit.yarddrive.ui.scene.PanelIds
import io.embodit.yarddrive.ui.scene.YardScene
import io.embodit.yarddrive.ui.theme.YdColors

/**
 * The Stage content (window model stage_with_attachment_panels): one SpatialView holding the cabin and yard and the pooled
 * attachment panels P1 / P2 / HUB / MRD-L / MRD-R / MIR-L / MIR-R / MIR-I. [YardScene] parents and places them.
 */
@Composable
internal fun YdStage(state: YdUiState, scene: YardScene, onEvent: (YdEvent) -> Unit) {
    val bindTick = scene.bindTick.intValue
    // the live theme colours, one `PALETTE role=#AARRGGBB` line: tools/contrast.py reads them for the D13 table
    val cs = PicoTheme.colorScheme
    LaunchedEffect(cs) {
        fun hex(c: Color) = String.format(java.util.Locale.US, "#%08X", c.toArgb())
        val roles = listOf(
            "labelPrimary" to cs.labelPrimary, "labelSecondary" to cs.labelSecondary, "fillPrimary" to cs.fillPrimary, "fillSecondary" to cs.fillSecondary,
            "fillTertiary" to cs.fillTertiary, "error" to cs.error, "ink" to YdColors.Ink, "interactionFill" to YdColors.InteractionFill,
        )
        YdLog.i("PALETTE " + roles.joinToString(" ") { (n, c) -> "$n=${hex(c)}" })
    }
    val pics = scene.mirrorPics.value
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        attachments = {
            AttachmentPanel(PanelIds.P1) { PoolPanel(PanelIds.P1, scene) { v -> P1Panel(if (v) state else null, onEvent) } }
            AttachmentPanel(PanelIds.P2) { PoolPanel(PanelIds.P2, scene) { v -> P2Panel(if (v) state else null, onEvent) } }
            AttachmentPanel(PanelIds.HUB) { PoolPanel(PanelIds.HUB, scene) { v -> HubPanel(if (v) state else null) } }
            AttachmentPanel(PanelIds.MRD_L) { PoolPanel(PanelIds.MRD_L, scene) { v -> MrdPanel(if (v) state else null, left = true) } }
            AttachmentPanel(PanelIds.MRD_R) { PoolPanel(PanelIds.MRD_R, scene) { v -> MrdPanel(if (v) state else null, left = false) } }
            AttachmentPanel(PanelIds.MIR_L) { PoolPanel(PanelIds.MIR_L, scene) { v -> MirrorPanel(if (v) pics[Mirrors.Id.L] else null, wide = false) } }
            AttachmentPanel(PanelIds.MIR_R) { PoolPanel(PanelIds.MIR_R, scene) { v -> MirrorPanel(if (v) pics[Mirrors.Id.R] else null, wide = false) } }
            AttachmentPanel(PanelIds.MIR_I) { PoolPanel(PanelIds.MIR_I, scene) { v -> MirrorPanel(if (v) pics[Mirrors.Id.I] else null, wide = true) } }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/** Root of a pooled panel: [content] gets `inView` (a pooled panel draws only once it is in view, Spatial Ruler finding 3). */
@Composable
private fun PoolPanel(id: String, scene: YardScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val watcher =
        Modifier.pointerInput(id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move) continue
                    if (Probe.enabled) {
                        val p = event.changes.firstOrNull()?.position
                        YdLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
