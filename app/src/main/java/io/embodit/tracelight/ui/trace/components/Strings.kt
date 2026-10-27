package io.embodit.tracelight.ui.trace.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.tracelight.domain.LanguagePref
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / EN), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads
 * its strings from it (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalTraceResources = staticCompositionLocalOf<Resources> { error("LocalTraceResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalTraceResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalTraceResources.current.getString(id, *args)

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val LESSON_W = 480f
    const val LESSON_H = 320f

    /** The drawing panel (S07 library · S09 tracing · S12 done · S14 free drawing). */
    const val DRAW_W = 560f
    const val DRAW_H = 400f

    const val DISABLED_ALPHA = 0.38f
}
