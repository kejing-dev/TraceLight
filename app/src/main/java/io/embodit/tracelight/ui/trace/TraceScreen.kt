package io.embodit.tracelight.ui.trace

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import io.embodit.tracelight.platform.TraceLog
import io.embodit.tracelight.ui.trace.components.TraceStage
import io.embodit.tracelight.ui.trace.components.LocalTraceResources
import io.embodit.tracelight.ui.trace.components.localizedResources
import io.embodit.tracelight.ui.trace.debug.Probe
import io.embodit.tracelight.ui.trace.scene.TraceFrameSystem
import io.embodit.tracelight.ui.trace.scene.TraceScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [TraceScene] (entities, per-frame work, taps and drags) and [TraceStage] (the panels), and owns nothing.
 */
@Composable
fun TraceScreen() {
    val context = LocalContext.current
    val viewModel: TraceViewModel = viewModel(factory = TraceViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { TraceScene(viewModel::onEvent).also { it.appContext = context.applicationContext } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<TraceFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): TraceViewModel = viewModel

                override fun scene(): TraceScene = scene
            }
        // in the background every tracker stops (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        TraceLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<TraceFrameSystem>()
            scene.detach()
            TraceLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    // real hands and the pencil over the virtual lines (design `container.upperLimbRenderMode`; device effect is a spike question)
    val limbs = LocalStageUpperLimbRenderModelManager.current
    LaunchedEffect(limbs) {
        val r = runCatching { limbs.setUpperLimbRenderMode(UpperLimbRenderMode.Visible) }
        TraceLog.i("LIMBS visible result=${r.getOrNull() ?: r.exceptionOrNull()}")
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalTraceResources provides resources) {
        TraceStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}

