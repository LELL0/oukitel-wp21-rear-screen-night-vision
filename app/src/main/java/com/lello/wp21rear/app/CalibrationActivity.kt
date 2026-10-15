package com.lello.wp21rear.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.daemon.Proto
import com.lello.wp21rear.pages.CalibrationOverlay
import kotlin.math.hypot

/**
 * Display rotation/mirror and colour byte order are chosen by eye; touch orientation is measured
 * by tapping four targets and picking the rotation/mirror that fits best.
 */
class CalibrationActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var result: TextView
    private var overlay: CalibrationOverlay? = null
    private val h = Handler(Looper.getMainLooper())

    private val targets = listOf(90f to 90f, 270f to 90f, 270f to 270f, 90f to 270f)
    private val raw = ArrayList<Pair<Float, Float>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Calibration"
        prefs = Prefs(this)
        val root = vbox()
        root.add(MirrorView(this))

        root.add(header("1. Display orientation"))
        root.add(label("Look at the rear screen. Change these until the arrow points up and TOP / LEFT / RIGHT read normally.", 13f))
        root.add(button("Show the arrow") { show("orient", "") })
        val rot = Spinner(this)
        val rots = listOf("0", "90", "180", "270")
        rot.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, rots.map { "Rotate $it°" })
        rot.setSelection(rots.indexOf(prefs.rotation.toString()).coerceAtLeast(0), false)
        rot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.sp.edit().putString(Prefs.K_ROTATION, rots[pos]).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.add(rot)
        root.add(check("Mirror image", Prefs.K_MIRROR))

        root.add(header("2. Colours"))
        root.add(label("The bands must be red, green and blue as labelled. If the colours are wrong, toggle the byte order.", 13f))
        root.add(button("Show colour bands") { show("colors", "") })
        root.add(check("Swap bytes (little-endian RGB565)", Prefs.K_SWAP_BYTES))

        root.add(header("3. Touch"))
        root.add(label("Tap the red dot on the rear screen each time it moves (4 taps).", 13f))
        root.add(button("Start touch calibration") { startTouch() })
        result = label("Current: rotate ${prefs.touchRotation}°, mirror ${prefs.touchMirror}", 13f)
        root.add(result)
        root.add(button("Done") { finish() })
        setContentView(scroll(root))
    }

    private fun check(text: String, key: String) = CheckBox(this).apply {
        this.text = text
        isChecked = prefs.sp.getBoolean(key, false)
        setOnCheckedChangeListener { _, c -> prefs.sp.edit().putBoolean(key, c).apply() }
    }

    private fun show(mode: String, msg: String) {
        if (!Rear.running) {
            result.text = "Service is not running"
            return
        }
        Rear.service?.wakePanel()
        val ui = Rear.ui
        ui.post {
            val o = overlay ?: CalibrationOverlay(ui).also { overlay = it }
            o.mode = mode
            o.message = msg
            ui.showOverlay(o, false)
            ui.invalidate()
        }
    }

    private fun startTouch() {
        raw.clear()
        show("touch", "tap the dot")
        nextTarget()
        var downX = 0f
        var downY = 0f
        Rear.touchCapture = { action, x, y ->
            if (action == Proto.TOUCH_DOWN) { downX = x.toFloat(); downY = y.toFloat() }
            if (action == Proto.TOUCH_UP) h.post {
                raw.add(downX to downY)
                if (raw.size >= targets.size) finishTouch() else nextTarget()
            }
        }
    }

    private fun nextTarget() {
        val o = overlay ?: return
        o.target = targets[raw.size]
        o.message = "${raw.size + 1} / ${targets.size}"
        Rear.ui.invalidate()
    }

    private fun finishTouch() {
        Rear.touchCapture = null
        var best = Triple(0, false, Float.MAX_VALUE)
        for (mirror in listOf(false, true)) for (rot in listOf(0, 90, 180, 270)) {
            var err = 0f
            for (i in targets.indices) {
                val (mx, my) = map(raw[i].first, raw[i].second, rot, mirror)
                err += hypot(mx - targets[i].first, my - targets[i].second)
            }
            if (err < best.third) best = Triple(rot, mirror, err)
        }
        val avg = best.third / targets.size
        prefs.sp.edit().putString(Prefs.K_TOUCH_ROTATION, best.first.toString()).putBoolean(Prefs.K_TOUCH_MIRROR, best.second).apply()
        result.text = "Saved: rotate ${best.first}°, mirror ${best.second} (average error %.0f px%s)".format(
            avg, if (avg > 60) " — high, try again" else "")
        overlay?.let { it.target = null; it.message = "touch calibrated"; Rear.ui.invalidate() }
    }

    /** Same transform as RearUi.mapTouch, for a candidate rotation/mirror. */
    private fun map(rx: Float, ry: Float, rot: Int, mirror: Boolean): Pair<Float, Float> {
        var x = rx
        var y = ry
        if (mirror) x = 360f - x
        when (rot) {
            90 -> { val t = x; x = 360f - y; y = t }
            180 -> { x = 360f - x; y = 360f - y }
            270 -> { val t = x; x = y; y = 360f - t }
        }
        return x to y
    }

    override fun onPause() {
        super.onPause()
        Rear.touchCapture = null
        if (Rear.running) {
            val o = overlay
            Rear.ui.post { if (Rear.ui.overlay === o) Rear.ui.closeOverlay(false) }
        }
        overlay = null
    }
}
