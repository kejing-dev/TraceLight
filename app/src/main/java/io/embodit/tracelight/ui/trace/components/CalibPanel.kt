package io.embodit.tracelight.ui.trace.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.tracelight.R
import io.embodit.tracelight.ui.trace.TraceEvent
import io.embodit.tracelight.ui.trace.TraceUiState
import io.embodit.tracelight.ui.trace.debug.probe
import io.embodit.tracelight.ui.theme.TraceType
import kotlinx.coroutines.delay

/**
 * The calibration page (design S20; spike / debug build only, so its density is not the product's):
 * round and gain, a running timer, 记一次 (one `SPIKE` log line per reading), the test figure, the
 * pen-grip tap test, the height nudge and 下一轮 (a fresh alignment).
 */
@Composable
internal fun CalibPanel(state: TraceUiState?, onEvent: (TraceEvent) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            Modifier
                .size(PanelSizes.CALIB_W.dp, PanelSizes.CALIB_H.dp)
                .clip(shape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state == null) return@Column
        val c = state.calib
        val ink = PicoTheme.colorScheme.labelPrimary
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.s20_title, c.round), color = ink, style = TraceType.title, modifier = Modifier.weight(1f), maxLines = 1)
            Gain(c.gain, onEvent)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Timer(c.startedAtMs, modifier = Modifier.weight(1f))
            Primary(rs(R.string.s20_record, c.records), "record") { onEvent(TraceEvent.RecordClicked) }
        }
        Text(text = rs(R.string.s20_how), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 2)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(if (c.testFigure) rs(R.string.s20_figure_off) else rs(R.string.s20_figure_on), "figure", width = 136.dp) { onEvent(TraceEvent.TestFigureClicked) }
            Spacer(modifier = Modifier.weight(1f))
            Text(text = rs(R.string.s20_height), color = PicoTheme.colorScheme.labelSecondary, style = TraceType.hint, maxLines = 1)
            SecondaryButton("▼", "heightDown", width = 48.dp) { onEvent(TraceEvent.HeightClicked(-1)) }
            Text(text = "${if (c.heightMm > 0) "+" else ""}${c.heightMm}", color = ink, style = TraceType.title, maxLines = 1)
            SecondaryButton("▲", "heightUp", width = 48.dp) { onEvent(TraceEvent.HeightClicked(1)) }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(rs(R.string.s20_test_tap, c.taps), "testTap", width = 180.dp) { onEvent(TraceEvent.TestTapClicked) }
            Spacer(modifier = Modifier.weight(1f))
            SecondaryButton(rs(R.string.s20_next_round), "nextRound", width = 140.dp) { onEvent(TraceEvent.NextRoundClicked) }
        }
    }
}

@Composable
private fun Timer(startedAtMs: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val s = ((now - startedAtMs).coerceAtLeast(0L) / 1000L).toInt()
    Box(modifier = modifier) { Text(text = String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60), color = PicoTheme.colorScheme.labelPrimary, style = TraceType.timer, maxLines = 1) }
}

@Composable
private fun Gain(gain: Float, onEvent: (TraceEvent) -> Unit) {
    SegmentControl(modifier = Modifier.width(168.dp).height(40.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        for (g in listOf(0.5f, 1.0f)) {
            SegmentItem(
                selected = gain == g,
                onClick = { onEvent(TraceEvent.GainClicked(g)) },
                modifier = Modifier.width(80.dp).fillMaxHeight().probe("gain_$g"),
                textStyle = TraceType.label,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { Text(text = rs(R.string.s20_gain, g.toString()), style = TraceType.label, maxLines = 1) },
            )
        }
    }
}
