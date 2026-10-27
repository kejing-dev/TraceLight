package io.embodit.tracelight.ui.trace.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.tracelight.platform.TraceLog
import io.embodit.tracelight.ui.trace.TraceEvent
import io.embodit.tracelight.ui.trace.TraceUiState
import io.embodit.tracelight.ui.trace.debug.LocalProbePanel
import io.embodit.tracelight.ui.trace.debug.Probe
import io.embodit.tracelight.ui.trace.scene.TraceScene
import io.embodit.tracelight.ui.trace.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView with the sheet, the desk's tap targets and the panel
 * pool. Every attachment panel is declared once with a fixed size, shown or not ([TraceScene] parents
 * and places them). Tap and drag recognizers live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model: one panel at a time beside the sheet: the setup panel while aligning, the drawing
 * panel (library, tracing, free drawing) after.
 */
@Composable
internal fun TraceStage(state: TraceUiState, scene: TraceScene, onEvent: (TraceEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.LESSON) {
                PoolPanel(PanelIds.LESSON, scene) { inView -> LessonPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.DRAW) {
                PoolPanel(PanelIds.DRAW, scene) { inView -> DrawPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel
 * draws only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: TraceScene, pressed: (() -> Unit)? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            TraceLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
