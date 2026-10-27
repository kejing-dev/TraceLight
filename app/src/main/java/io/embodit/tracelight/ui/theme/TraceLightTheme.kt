package io.embodit.tracelight.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * The app theme: SpatialUI Vibrant with the four semantic roles of the design and the Noto Sans type
 * family. The twelve adaptive grey roles are forwarded from the system scheme on purpose, each one
 * named so that the contract is visible (design-doc §3: `inherit SpatialUI Vibrant ...`).
 */
@Composable
fun TraceLightTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors =
        remember(system) {
            system.copy(
                fillPrimary = system.fillPrimary,
                fillSecondary = system.fillSecondary,
                fillTertiary = system.fillTertiary,
                fillLight = system.fillLight,
                labelPrimaryLight = system.labelPrimaryLight,
                labelPrimary = system.labelPrimary,
                labelSecondary = system.labelSecondary,
                labelTertiary = system.labelTertiary,
                labelQuaternary = system.labelQuaternary,
                lightenHover = system.lightenHover,
                lightenPressed = system.lightenPressed,
                error = TraceColors.Error,
                alert = TraceColors.Alert,
                passable = TraceColors.Passable,
                interaction = TraceColors.Interaction,
                dividerLine = system.dividerLine,
            )
        }
    val family = rememberTraceFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
