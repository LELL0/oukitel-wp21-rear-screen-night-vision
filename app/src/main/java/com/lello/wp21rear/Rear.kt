package com.lello.wp21rear

import android.annotation.SuppressLint
import android.content.Context
import com.lello.wp21rear.core.DaemonClient
import com.lello.wp21rear.core.Panel
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.data.SensorHub
import com.lello.wp21rear.data.Steps
import com.lello.wp21rear.data.Toggles
import com.lello.wp21rear.service.RearService
import com.lello.wp21rear.ui.RearUi

/** Process-wide handles to the running rear-screen components, owned by [RearService]. */
@SuppressLint("StaticFieldLeak") // application context only
object Rear {
    lateinit var app: Context
    lateinit var prefs: Prefs
    lateinit var daemon: DaemonClient
    lateinit var panel: Panel
    lateinit var ui: RearUi
    lateinit var sensors: SensorHub
    lateinit var toggles: Toggles
    lateinit var steps: Steps

    @Volatile var service: RearService? = null

    /** Raw rear-touch observers (diagnostics viewer). Args: action, raw x, raw y. */
    val touchListeners = java.util.concurrent.CopyOnWriteArrayList<(Int, Int, Int) -> Unit>()

    /** When set, raw rear touches go here instead of the UI (touch calibration). */
    @Volatile var touchCapture: ((Int, Int, Int) -> Unit)? = null

    val running: Boolean get() = service != null

    /** Minimal setup for code that runs without the service (face previews in the app). */
    fun initBasics(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        if (!::prefs.isInitialized) prefs = Prefs(app)
    }
}
