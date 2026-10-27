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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleButton
import com.pico.spatial.ui.design.ToggleButtonDefaults
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.tracelight.R
import io.embodit.tracelight.domain.Library
import io.embodit.tracelight.domain.Piece
import io.embodit.tracelight.ui.trace.TraceEvent
import io.embodit.tracelight.ui.trace.TraceUiState
import io.embodit.tracelight.ui.trace.debug.probe
import io.embodit.tracelight.ui.theme.TraceColors
import io.embodit.tracelight.ui.theme.TraceType

/**
 * The drawing panel, standing beside the sheet (S07 library · S09 tracing · S12 done · S14 free drawing).
 * Gaze + pinch or the controller ray press every key; nothing needs the user to walk up to it.
 */
@Composable
internal fun DrawPanel(state: TraceUiState?, onEvent: (TraceEvent) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            Modifier
                .size(PanelSizes.DRAW_W.dp, PanelSizes.DRAW_H.dp)
                .clip(shape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Column
        if (state.menuOpen) {
            Menu(state, onEvent)
            return@Column
        }
        when (state.designState) {
            "S07" -> LibraryView(state, onEvent)
            "S09" -> TraceView(state, onEvent)
            "S12" -> DoneView(state, onEvent)
            "S14" -> FreeView(state, onEvent)
            else -> Unit
        }
    }
}

private fun isZh(lang: String) = lang == "zh"

@Composable
private fun lang(): String = rs(R.string.lang_code)

/** Title row: an optional ← (back to the library), the title, ⋯ (re-align, free drawing, library). */
@Composable
private fun Header(title: String, back: Boolean, onEvent: (TraceEvent) -> Unit, trailing: @Composable () -> Unit = {}) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (back) {
            IconButton(
                onClick = { onEvent(TraceEvent.LibraryClicked) },
                modifier = Modifier.probe("back"),
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = 44.dp, height = 44.dp),
            ) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = rs(R.string.action_library), modifier = Modifier.size(22.dp), tint = ink)
            }
        }
        Text(text = title, color = ink, style = TraceType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
        IconButton(
            onClick = { onEvent(TraceEvent.MenuClicked) },
            modifier = Modifier.probe("more"),
            colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
            size = IconButtonDefaults.iconButtonSize(width = 44.dp, height = 44.dp),
        ) {
            Icon(painter = painterResource(R.drawable.ic_more), contentDescription = rs(R.string.action_more), modifier = Modifier.size(22.dp), tint = ink)
        }
    }
}

/** S07: six sets as toggles, then the five drawings of the chosen set, easy to hard; 自由画 at the top. */
@Composable
private fun ColumnScope.LibraryView(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    val zh = isZh(lang())
    Header(rs(R.string.library_title), back = false, onEvent = onEvent) {
        SecondaryButton(rs(R.string.free_draw), "freeDraw", width = 112.dp) { onEvent(TraceEvent.FreeDrawClicked) }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in Library.sets) {
            val on = s.id == state.setId
            ToggleButton(
                checked = on,
                onCheckedChange = { onEvent(TraceEvent.SetPicked(s.id)) },
                modifier = Modifier.weight(1f).height(40.dp).probe("set_${s.id}"),
                colors =
                    ToggleButtonDefaults.toggleButtonColors(
                        checkedContainerColor = PicoTheme.colorScheme.fillSecondary,
                        checkedContentColor = PicoTheme.colorScheme.labelPrimary,
                        uncheckedContainerColor = PicoTheme.colorScheme.fillTertiary,
                        uncheckedContentColor = PicoTheme.colorScheme.labelSecondary,
                    ),
            ) {
                Text(text = if (zh) s.zh.take(4) else s.en.substringBefore(' '), style = TraceType.label, maxLines = 1)
            }
        }
    }
    val set = Library.set(state.setId) ?: Library.sets.first()
    val rows = set.pieces.chunked(3)
    for (row in rows) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (p in row) PieceTile(p, done = p.id in state.done, zh = zh, onEvent = onEvent)
        }
    }
    Spacer(modifier = Modifier.weight(1f))
    Text(text = rs(R.string.library_hint), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 1)
}

@Composable
private fun PieceTile(p: Piece, done: Boolean, zh: Boolean, onEvent: (TraceEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val name = if (zh) p.zh else p.en
    Button(
        onClick = { onEvent(TraceEvent.PiecePicked(p.id)) },
        modifier = Modifier.width(TileW).height(TileH).semantics { contentDescription = name }.probe("piece_${p.id}"),
        size = ButtonDefaults.buttonSize(width = TileW, height = TileH),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = name, style = TraceType.body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (done) Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.passable)
            }
            Level(p.level)
        }
    }
}

/** Difficulty as five small dots (domain graphic). */
@Composable
private fun Level(level: Int) {
    val off = PicoTheme.colorScheme.labelQuaternary
    Canvas(modifier = Modifier.size(64.dp, 8.dp)) {
        val r = 3.dp.toPx()
        for (k in 0 until 5) drawCircle(color = if (k < level) TraceColors.PenAmber else off, radius = r, center = Offset(r + k * 4f * r, size.height / 2f))
    }
}

/** S09: the step's words, where it is in the drawing, previous / next. */
@Composable
private fun ColumnScope.TraceView(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    val p = state.piece ?: return
    val zh = isZh(lang())
    val ink = PicoTheme.colorScheme.labelPrimary
    val n = p.steps.size
    val step = p.steps[state.step.coerceIn(0, n - 1)]
    Header(if (zh) p.zh else p.en, back = true, onEvent = onEvent)
    StepDots(state.step, n)
    Text(text = rs(R.string.trace_step, state.step + 1, n), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.label)
    Text(text = if (zh) step.zh else step.en, color = ink, style = TraceType.instruction, maxLines = 3)
    Text(text = rs(R.string.trace_hint), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 2)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(rs(R.string.trace_prev), "prevStep", width = 132.dp, enabled = state.step > 0) { onEvent(TraceEvent.PrevStepClicked) }
        Primary(if (state.step >= n - 1) rs(R.string.trace_finish) else rs(R.string.trace_next), "nextStep") { onEvent(TraceEvent.NextStepClicked) }
    }
}

@Composable
private fun StepDots(step: Int, n: Int) {
    val off = PicoTheme.colorScheme.fillSecondary
    Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (k in 0 until n) {
            val c = if (k < step) TraceColors.Jade else if (k == step) TraceColors.PenAmber else off
            drawRoundRect(color = c, topLeft = Offset(k * (w + gap), 0f), size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
        }
    }
}

/** S12: done; next drawing on a fresh sheet, or back to the library. */
@Composable
private fun ColumnScope.DoneView(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    val p = state.piece
    val zh = isZh(lang())
    val next = p?.let { Library.next(it.id) }
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(56.dp), tint = PicoTheme.colorScheme.passable)
            Text(text = rs(R.string.done_title, if (p == null) "" else if (zh) p.zh else p.en), color = PicoTheme.colorScheme.labelPrimary, style = TraceType.instruction, maxLines = 2)
            Text(text = if (next != null) rs(R.string.done_next_hint, if (zh) next.zh else next.en) else rs(R.string.done_set_hint), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 2)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(rs(R.string.action_library), "library", width = 132.dp) { onEvent(TraceEvent.LibraryClicked) }
        if (next != null) Primary(rs(R.string.done_next), "nextPiece") { onEvent(TraceEvent.NextPieceClicked) }
    }
}

/** S14: the whole sheet is yours; an optional grid. */
@Composable
private fun ColumnScope.FreeView(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    Header(rs(R.string.free_draw), back = true, onEvent = onEvent)
    Text(text = rs(R.string.free_line), color = PicoTheme.colorScheme.labelPrimary, style = TraceType.instruction, maxLines = 2)
    Text(text = rs(R.string.free_hint), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 2)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(if (state.grid) rs(R.string.free_grid_off) else rs(R.string.free_grid_on), "grid", width = 132.dp) { onEvent(TraceEvent.GridClicked) }
        Primary(rs(R.string.action_library), "library") { onEvent(TraceEvent.LibraryClicked) }
    }
}

/** ⋯: re-align the sheet, free drawing, back to the library; tapping ⋯ again closes it. */
@Composable
private fun ColumnScope.Menu(state: TraceUiState, onEvent: (TraceEvent) -> Unit) {
    Header(rs(R.string.menu_title), back = false, onEvent = onEvent)
    MenuRow(rs(R.string.menu_realign), "realign") { onEvent(TraceEvent.RealignClicked) }
    if (state.designState != "S14") MenuRow(rs(R.string.free_draw), "menuFree") { onEvent(TraceEvent.FreeDrawClicked) }
    if (state.designState != "S07") MenuRow(rs(R.string.action_library), "menuLibrary") { onEvent(TraceEvent.LibraryClicked) }
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SecondaryButton(rs(R.string.action_close), "menuClose", width = 120.dp) { onEvent(TraceEvent.MenuClicked) }
    }
}

@Composable
private fun MenuRow(text: String, probeName: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp).probe(probeName),
        size = ButtonDefaults.buttonSize(width = (PanelSizes.DRAW_W - 48f).dp, height = 52.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Text(text = text, style = TraceType.body, maxLines = 1, modifier = Modifier.fillMaxWidth())
    }
}

private val TileW = 160.dp
private val TileH = 72.dp
