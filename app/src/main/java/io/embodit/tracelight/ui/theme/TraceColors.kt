package io.embodit.tracelight.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme

/**
 * The design's exact colours (design-package `palette`): trace blue lines on white paper, amber for
 * what moves, jade for what is in place, coral for what is off. This is the only file with colour
 * literals; the four semantic roles are injected into `PicoTheme` by [TraceLightTheme], the brand
 * tokens are used directly by the scene.
 */
object TraceColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Interaction = Color(0xFF4D8DFF) // design-style: fixed-figma-color interaction
    val Passable = Color(0xFF5FD3A4) // design-style: fixed-figma-color passable: aligned
    val Alert = Color(0xFFFFB648) // design-style: fixed-figma-color alert: the corner to drag
    val Error = Color(0xFFFF6B5E) // design-style: fixed-figma-color error: off / cannot drop

    // ---- brand tokens outside ColorScheme
    val TraceBlue = Color(0xFF2F7BFF) // design-style: fixed-figma-color traceBlue: guide lines
    val Cinnabar = Color(0xFFFF4A3D) // design-style: fixed-figma-color cinnabar: guide lines on blue paper
    val PenAmber = Color(0xFFFFB648) // design-style: fixed-figma-color penAmber: pen tip / active corner
    val LightboxWarm = Color(0xFFFFF4DC) // design-style: fixed-figma-color lightboxWarm: aligned flash
    val Graphite = Color(0xFF3A3F47) // design-style: fixed-figma-color graphite
    val Plaster = Color(0xFFEDEBE6) // design-style: fixed-figma-color plaster: still-life
    val Jade = Color(0xFF5FD3A4) // design-style: fixed-figma-color jade: placed corner
    val Coral = Color(0xFFFF6B5E) // design-style: fixed-figma-color coral: off
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color white: frame, ghost
    val Debug = Color(0xFF00E5FF) // design-style: fixed-figma-color debug (debug command only)
}

/** Type roles of the design (`typography.roles`). */
object TraceType {
    /** 20/26 SemiBold: the one big instruction of the alignment flow. */
    val instruction: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    /** 16/20 SemiBold: lesson names, panel titles. */
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

    /** 16/22 Medium: step text. */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)

    /** 14/18 Medium: small print. */
    val hint: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    /** 12/16 SemiBold: step counts, segments. */
    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** The calibration timer. */
    val timer: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")
}
