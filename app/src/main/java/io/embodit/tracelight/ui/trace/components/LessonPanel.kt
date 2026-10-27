package io.embodit.tracelight.ui.trace.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.tracelight.R
import io.embodit.tracelight.ui.trace.TraceEvent
import io.embodit.tracelight.ui.trace.TraceUiState
import io.embodit.tracelight.ui.trace.debug.probe
import io.embodit.tracelight.ui.theme.TraceColors
import io.embodit.tracelight.ui.theme.TraceType
import kotlin.math.roundToInt

private val KeyH = 48.dp
private val PrimaryW = 168.dp
private val SecondaryW = 120.dp

/**
 * The lesson panel during the alignment flow (design `panels.lesson.content.setup`: S00 - S06, S16,
 * S18): one big instruction, a small figure (the four corners, the current one lit), one primary
 * button and, where there is an old sheet, a secondary one. Density cap: <= 1 primary + 1 secondary.
 */
@Composable
internal fun LessonPanel(state: TraceUiState?, onEvent: (TraceEvent) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            Modifier
                .size(PanelSizes.LESSON_W.dp, PanelSizes.LESSON_H.dp)
                .clip(shape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Column
        when (state.designState) {
            "S00" -> Loading()
            "S01" -> Welcome(onEvent)
            "S02" -> Instruction(icon = R.drawable.ic_look_down, text = rs(R.string.s02_look_down), hint = rs(R.string.s02_hint)) {
                Secondary(state, onEvent)
            }
            "S03" ->
                when {
                    // automatic alignment (design change 2026-10-23): the camera found the sheet
                    state.found != null && state.foundStable ->
                        Instruction(icon = R.drawable.ic_check, text = rs(R.string.s03_found), hint = rs(R.string.s03_found_hint)) {
                            SecondaryButton(rs(R.string.s03_adjust), "autoAdjust", width = 132.dp) { onEvent(TraceEvent.AutoAdjustClicked) }
                            Primary(rs(R.string.s04_aligned), "autoAccept") { onEvent(TraceEvent.AutoAcceptClicked) }
                        }
                    state.found != null ->
                        Instruction(icon = R.drawable.ic_paper, text = rs(R.string.s03_finding), hint = rs(R.string.s03_hold_still)) {
                            Secondary(state, onEvent)
                        }
                    else ->
                        Instruction(icon = R.drawable.ic_paper, text = rs(R.string.s03_look), hint = if (state.searchSlow) rs(R.string.s03_slow) else null) {
                            Secondary(state, onEvent)
                        }
                }
            "S04" -> AlignCorners(state, onEvent)
            "S05" -> CornerOff(state, onEvent)
            "S06" -> Done()
            "S16" ->
                Instruction(icon = R.drawable.ic_look_down, text = rs(R.string.s16_no_desk), hint = rs(R.string.s16_hint)) {
                    Primary(rs(R.string.s16_retry), "retryDesk") { onEvent(TraceEvent.RetryDeskClicked) }
                }
            "S18" ->
                Instruction(icon = R.drawable.ic_warning, text = rs(R.string.s18_permission), hint = null) {
                    Primary(rs(R.string.s18_grant), "grant") { onEvent(TraceEvent.RetryDeskClicked) }
                }
            else -> Unit
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val text = rs(R.string.s00_loading)
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp).semantics { contentDescription = text },
                color = PicoTheme.colorScheme.labelPrimary,
                progressSize = CircularProgressDefaults.circleProgressSize(40.dp),
            )
            Text(text = text, color = PicoTheme.colorScheme.labelSecondary, style = TraceType.body)
        }
    }
}

/** S01: a sheet of A4, a pencil, a bright desk; tape two corners down. */
@Composable
private fun ColumnScope.Welcome(onEvent: (TraceEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Need(R.drawable.ic_paper, rs(R.string.s01_paper))
        Need(R.drawable.ic_pencil, rs(R.string.s01_pencil))
        Need(R.drawable.ic_lamp, rs(R.string.s01_desk))
    }
    Text(text = rs(R.string.s01_line), color = ink, style = TraceType.body, maxLines = 2)
    Text(text = rs(R.string.s01_ruler), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 1)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Primary(rs(R.string.s01_ready), "ready") { onEvent(TraceEvent.ReadyClicked) }
    }
}

@Composable
private fun Need(icon: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(128.dp)) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(40.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Text(text = label, color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** An icon, the one instruction, an optional hint line, the buttons at the bottom right. */
@Composable
private fun ColumnScope.Instruction(icon: Int?, text: String, hint: String?, hintColor: Color? = null, buttons: @Composable () -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    if (icon != null) Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(44.dp), tint = ink)
    Text(text = text, color = ink, style = TraceType.instruction, maxLines = 2)
    if (hint != null) Text(text = hint, color = hintColor ?: PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 2)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { buttons() }
}

/** 取消 while re-aligning an old sheet (design T15). */
@Composable
private fun Secondary(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    if (state.previous != null) SecondaryButton(rs(R.string.action_cancel), "cancel") { onEvent(TraceEvent.CancelClicked) }
}

private fun cornerNumber(i: Int): String = "①②③④"[i].toString()

/** S04: drag ② to the lower right corner (first time) / ① is it on? (re-check). */
@Composable
private fun ColumnScope.AlignCorners(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val i = state.activeCorner
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CornerFigure(state, size = 76.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val line =
                if (state.recheck) rs(R.string.s04_recheck, cornerNumber(i))
                else if (state.allCornersSet) rs(R.string.s04_all_set)
                else rs(R.string.s04_drag, cornerNumber(i), rs(cornerName(i)))
            Text(text = line, color = ink, style = TraceType.instruction, maxLines = 3)
        }
    }
    if (state.controller) {
        Text(text = rs(R.string.hint_stick), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 1)
        Text(text = rs(R.string.s04_tip), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 1)
    }
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (state.recheck) {
            if (state.previous != null) SecondaryButton(rs(R.string.action_cancel), "cancel") { onEvent(TraceEvent.CancelClicked) }
            if (state.confirmed >= 4) Primary(rs(R.string.s04_aligned), "confirm") { onEvent(TraceEvent.ConfirmClicked) }
            else Primary(rs(R.string.s04_corner_ok), "cornerOk") { onEvent(TraceEvent.CornerOkClicked) }
        } else {
            SecondaryButton(rs(R.string.action_restart), "restart") { onEvent(TraceEvent.RestartClicked) }
            Primary(rs(R.string.s04_aligned), "confirm", enabled = state.allCornersSet) { onEvent(TraceEvent.ConfirmClicked) }
        }
    }
}

private fun cornerName(i: Int): Int =
    when (i) {
        0 -> R.string.corner_ll
        1 -> R.string.corner_lr
        2 -> R.string.corner_ur
        else -> R.string.corner_ul
    }

/** S05: ③ is 7 mm off, drag it again (or: this is not A4). */
@Composable
private fun ColumnScope.CornerOff(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val v = state.verdict
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CornerFigure(state, size = 76.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (v != null && v.looksLetter) {
                Text(text = rs(R.string.s05_letter), color = ink, style = TraceType.instruction, maxLines = 3)
            } else if (v != null) {
                Text(text = rs(R.string.s05_off, cornerNumber(v.a4.worst), v.a4.maxMm.roundToInt()), color = ink, style = TraceType.instruction, maxLines = 2)
                Text(text = rs(R.string.s05_moved), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 1)
            }
        }
    }
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(rs(R.string.action_restart), "restart") { onEvent(TraceEvent.RestartClicked) }
        Primary(rs(R.string.s04_aligned), "confirm") { onEvent(TraceEvent.ConfirmClicked) }
    }
}

@Composable
private fun Done() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(56.dp), tint = PicoTheme.colorScheme.passable)
            Text(text = rs(R.string.s06_done), color = PicoTheme.colorScheme.labelPrimary, style = TraceType.instruction)
        }
    }
}

/**
 * The four-corner figure (domain graphic, design `spatialUiMap.cornerDiagram`): a portrait sheet, the
 * corner to drag amber, placed ones jade, the one that is off coral; ①② at the near edge.
 */
@Composable
internal fun CornerFigure(state: TraceUiState, size: Dp) {
    val line = PicoTheme.colorScheme.labelSecondary
    val worst = if (state.designState == "S05") state.verdict?.a4?.worst else null
    val placed = state.draft?.moved ?: List(4) { false }
    Canvas(modifier = Modifier.size(size * 0.72f, size)) {
        val w = this.size.width
        val h = this.size.height
        val inset = 8.dp.toPx()
        drawRect(color = line, topLeft = Offset(inset, inset), size = Size(w - 2 * inset, h - 2 * inset), style = Stroke(width = 1.5.dp.toPx()))
        // ① lower left, ② lower right, ③ upper right, ④ upper left
        val pts = listOf(Offset(inset, h - inset), Offset(w - inset, h - inset), Offset(w - inset, inset), Offset(inset, inset))
        for (k in 0 until 4) {
            val c =
                when {
                    worst == k -> TraceColors.Coral
                    k == state.activeCorner -> TraceColors.PenAmber
                    state.recheck && k < state.confirmed -> TraceColors.Jade
                    !state.recheck && placed[k] -> TraceColors.Jade
                    else -> line
                }
            drawCircle(color = c, radius = if (k == state.activeCorner) 6.dp.toPx() else 4.5.dp.toPx(), center = pts[k])
        }
    }
}

@Composable
internal fun Primary(text: String, probeName: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(KeyH).width(PrimaryW).probe(probeName),
        size = ButtonDefaults.buttonSize(width = PrimaryW, height = KeyH),
        contentPadding = PaddingValues(horizontal = 12.dp),
        enabled = enabled,
    ) {
        Text(text = text, style = TraceType.title, maxLines = 1)
    }
}

@Composable
internal fun SecondaryButton(text: String, probeName: String, width: Dp = SecondaryW, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(KeyH).width(width).probe(probeName),
        size = ButtonDefaults.buttonSize(width = width, height = KeyH),
        contentPadding = PaddingValues(horizontal = 10.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Text(text = text, style = TraceType.title, maxLines = 1)
    }
}
