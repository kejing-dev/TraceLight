package io.embodit.tracelight.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.tracelight.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
