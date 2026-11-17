package io.embodit.tracelight.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * `com.picovr.permission.SPATIAL_DATA` (runtime, dangerous): without it the headset never reports planes or the
 * spatial mesh (the emulator does, which is why QA never saw this; Vibe Lab review 2026-11: "scanning forever").
 * Asked once, on the first activity that comes up; the answer is polled (the Spatial launch activity is not ours to
 * override). Trackers register [whenGranted] and restart their provider when the grant arrives.
 */
object SpatialDataPermission {
    const val NAME = "com.picovr.permission.SPATIAL_DATA"
    private const val TAG = "SpatialData"
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var asked = false
    private var notified = false
    private val waiting = ArrayList<() -> Unit>()

    fun granted(): Boolean = app?.checkSelfPermission(NAME) == PackageManager.PERMISSION_GRANTED

    fun install(application: Application) {
        app = application.applicationContext
        notified = granted()
        Log.i(TAG, "granted at start=$notified")
        if (notified) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (granted()) return fire()
                    if (asked) return
                    main.postDelayed({ ask(activity) }, 600L)
                }
                override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityPaused(a: Activity) = Unit
                override fun onActivityStopped(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
                override fun onActivityDestroyed(a: Activity) = Unit
            },
        )
    }

    /** Runs [block] now if granted, otherwise once the user grants it (main thread). */
    fun whenGranted(block: () -> Unit) {
        if (granted()) return
        waiting += block
    }

    private fun ask(activity: Activity) {
        if (asked || granted() || activity.isFinishing || activity.isDestroyed) return
        asked = true
        Log.i(TAG, "request from ${activity.javaClass.simpleName}")
        runCatching { activity.requestPermissions(arrayOf(NAME), 4712) }.onFailure {
            Log.w(TAG, "request failed: $it")
            asked = false
            return
        }
        val t0 = System.currentTimeMillis()
        main.postDelayed(
            object : Runnable {
                override fun run() {
                    if (granted()) return fire()
                    if (System.currentTimeMillis() - t0 < 120_000L) main.postDelayed(this, 400L)
                    else Log.i(TAG, "not granted after 120 s")
                }
            },
            400L,
        )
    }

    private fun fire() {
        if (notified) return
        notified = true
        Log.i(TAG, "granted: restarting ${waiting.size} tracker(s)")
        val list = ArrayList(waiting)
        waiting.clear()
        list.forEach { runCatching(it).onFailure { e -> Log.w(TAG, "restart failed: $e") } }
    }
}
