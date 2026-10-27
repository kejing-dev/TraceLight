package io.embodit.tracelight

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.tracelight.ui.theme.TraceLightTheme
import io.embodit.tracelight.ui.trace.TraceScreen

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0): Full
 * Space with passthrough holds the whole product (design `container`: plane detection and world
 * anchors are Full Space only). There is no WindowContainer.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            TraceLightTheme {
                TraceScreen()
            }
        }
    }
