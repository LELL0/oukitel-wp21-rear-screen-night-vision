package com.lello.wp21rear.pages

import android.graphics.Canvas
import android.text.format.DateFormat
import com.lello.wp21rear.Rear
import com.lello.wp21rear.data.Battery
import com.lello.wp21rear.data.Chrono
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Icons
import com.lello.wp21rear.ui.Page
import com.lello.wp21rear.ui.RearUi
import java.util.Date
import kotlin.math.abs
import kotlin.math.hypot

/** Launcher grid for the tools, plus the torch toggle in the middle. */
class ToolsPage(ui: RearUi) : Page(ui) {
    override val id = "tools"

    private data class Item(val page: String, val icon: String, val label: String)

    private val ring = listOf(
        Item("compass", "compass", "Compass"),
        Item("level", "level", "Level"),
        Item("stopwatch", "stopwatch", "Stopwatch"),
        Item("timer", "timer", "Timer"),
        Item("battery", "battery", "Battery"),
        Item("steps", "steps", "Steps"),
        Item("camera", "camera", "Camera"),
        Item("photos", "photo", "Photos"),
    )

    private fun pos(i: Int): Pair<Float, Float> {
        val a = i * 360f / ring.size
        return Pair(Draw.px(Draw.C, 118f, a), Draw.py(Draw.C, 118f, a))
    }

    override fun draw(c: Canvas, now: Long) {
        for ((i, it) in ring.withIndex()) {
            val (x, y) = pos(i)
            Draw.circle(c, x, y, 30f, Draw.CARD)
            Icons.draw(c, it.icon, x, y, 30f, Draw.WHITE)
        }
        val on = Rear.toggles.torchOn
        Draw.circle(c, Draw.C, Draw.C, 50f, if (on) Draw.AMBER else Draw.CARD)
        Icons.draw(c, "flashlight", Draw.C, Draw.C - 6f, 40f, if (on) 0xFF000000.toInt() else Draw.WHITE)
        Draw.textC(c, if (on) "ON" else "Torch", Draw.C, Draw.C + 30f, 12f, if (on) 0xFF000000.toInt() else Draw.DIM, Draw.MED)
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (hypot(x - Draw.C, y - Draw.C) < 55f) {
            Rear.toggles.setTorch(!Rear.toggles.torchOn)
            ui.postDelayed(250, Runnable { invalidate() })
            return true
        }
        for ((i, it) in ring.withIndex()) {
            val (px, py) = pos(i)
            if (hypot(x - px, y - py) < 38f) {
                if (!ui.goTo(it.page)) ui.toast("${it.label} page is disabled")
                return true
            }
        }
        return false
    }
}

class CompassPage(ui: RearUi) : Page(ui) {
    override val id = "compass"
    private var shown = 0f
    private val listener: () -> Unit = { invalidate() }

    override fun onShow() {
        Rear.sensors.headingListeners.add(listener)
        Rear.sensors.acquire(Rear.sensors.rotation, id, 66_000)
    }

    override fun onHide() {
        Rear.sensors.headingListeners.remove(listener)
        Rear.sensors.release(Rear.sensors.rotation, id)
    }

    override fun draw(c: Canvas, now: Long) {
        // The rear screen faces away from the main screen; the heading is the phone's top direction.
        val target = Rear.sensors.heading
        var d = target - shown
        if (d > 180) d -= 360f
        if (d < -180) d += 360f
        shown = (shown + d * 0.5f + 360f) % 360f
        val rot = -shown
        for (i in 0 until 72) {
            val a = i * 5f + rot
            val major = i % 6 == 0
            val r0 = if (major) 150f else 160f
            Draw.line(c, Draw.px(Draw.C, r0, a), Draw.py(Draw.C, r0, a), Draw.px(Draw.C, 170f, a), Draw.py(Draw.C, 170f, a),
                if (major) 3f else 1.5f, if (major) Draw.WHITE else Draw.DIM)
        }
        val labels = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        for ((l, a) in labels) {
            Draw.textC(c, l, Draw.px(Draw.C, 128f, a + rot), Draw.py(Draw.C, 128f, a + rot), 24f,
                if (l == "N") Draw.RED else Draw.WHITE, Draw.MED)
        }
        // fixed pointer at the top
        Draw.fill.color = Draw.RED
        val p = android.graphics.Path().apply { moveTo(Draw.C, 6f); lineTo(Draw.C - 10f, 26f); lineTo(Draw.C + 10f, 26f); close() }
        c.drawPath(p, Draw.fill)
        Draw.textC(c, "${target.toInt()}°", Draw.C, 170f, 54f, Draw.WHITE, Draw.LIGHT)
        Draw.textC(c, dir(target), Draw.C, 215f, 22f, Draw.DIM, Draw.MED)
        if (Rear.sensors.headingAccuracy in 0..1) Draw.textC(c, "move phone in a figure 8", Draw.C, 250f, 13f, Draw.AMBER)
    }

    private fun dir(h: Float) = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((h + 22.5f) % 360) / 45).toInt()]

    override fun animating(now: Long) = true
}

class LevelPage(ui: RearUi) : Page(ui) {
    override val id = "level"
    private var bx = 0f
    private var by = 0f

    override fun onShow() = Rear.sensors.acquire(Rear.sensors.accel, id, 66_000)
    override fun onHide() = Rear.sensors.release(Rear.sensors.accel, id)

    override fun draw(c: Canvas, now: Long) {
        val (roll, pitch) = Rear.sensors.tilt()
        // Viewed from the back, x is mirrored.
        val tx = (roll / 45f).coerceIn(-1f, 1f) * 140f
        val ty = (-pitch / 45f).coerceIn(-1f, 1f) * 140f
        bx += (tx - bx) * 0.5f
        by += (ty - by) * 0.5f
        val level = abs(roll) < 1f && abs(pitch) < 1f
        Draw.ring(c, Draw.C, Draw.C, 150f, 2f, Draw.FAINT)
        Draw.ring(c, Draw.C, Draw.C, 75f, 2f, Draw.FAINT)
        Draw.line(c, Draw.C - 160f, Draw.C, Draw.C + 160f, Draw.C, 1.5f, Draw.FAINT)
        Draw.line(c, Draw.C, Draw.C - 160f, Draw.C, Draw.C + 160f, 1.5f, Draw.FAINT)
        Draw.ring(c, Draw.C, Draw.C, 24f, 2f, if (level) Draw.GREEN else Draw.DIM)
        Draw.circle(c, Draw.C + bx, Draw.C + by, 20f, if (level) Draw.GREEN else Draw.ACCENT)
        Draw.textC(c, "%.1f°  %.1f°".format(abs(roll), abs(pitch)), Draw.C, 300f, 20f, if (level) Draw.GREEN else Draw.WHITE, Draw.MONO)
    }

    override fun animating(now: Long) = true
}

class StopwatchPage(ui: RearUi) : Page(ui) {
    override val id = "stopwatch"

    override fun draw(c: Canvas, now: Long) {
        val e = Chrono.swElapsed()
        val sec = (e / 1000) % 60
        Draw.ring(c, Draw.C, Draw.C, 166f, 6f, Draw.FAINT)
        Draw.arc(c, Draw.C, Draw.C, 166f, 0f, 360f * ((e % 60000) / 60000f), 6f, Draw.ACCENT)
        val m = e / 60000
        Draw.textC(c, "%02d:%02d".format(m, sec), Draw.C, 140f, 72f, Draw.WHITE, Draw.LIGHT)
        Draw.textC(c, ".%02d".format((e % 1000) / 10), Draw.C, 192f, 28f, Draw.DIM, Draw.LIGHT)
        val laps = synchronized(Chrono.laps) { Chrono.laps.take(2) }
        laps.forEachIndexed { i, l ->
            Draw.textC(c, "Lap ${synchronized(Chrono.laps) { Chrono.laps.size } - i}  " + fmt(l), Draw.C, 228f + i * 20f, 14f, Draw.DIM, Draw.MONO)
        }
        // buttons: left = lap/reset, right = start/stop
        Draw.circle(c, 125f, 294f, 26f, Draw.CARD)
        Icons.draw(c, if (Chrono.swRunning) "flag" else "reset", 125f, 294f, 22f, Draw.WHITE)
        Draw.circle(c, 235f, 294f, 26f, if (Chrono.swRunning) Draw.RED else Draw.GREEN)
        Icons.draw(c, if (Chrono.swRunning) "pause" else "play", 235f, 294f, 22f, 0xFF000000.toInt())
    }

    private fun fmt(ms: Long) = "%02d:%02d.%02d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10)

    override fun animating(now: Long) = Chrono.swRunning

    override fun onTap(x: Float, y: Float): Boolean {
        if (y > 250f && x < Draw.C) {
            if (Chrono.swRunning) Chrono.swLap() else Chrono.swReset()
        } else {
            Chrono.swToggle()
        }
        invalidate()
        return true
    }
}

class TimerPage(ui: RearUi) : Page(ui) {
    override val id = "timer"
    private var dragStartSet = 0L

    override val keepAwake get() = Chrono.ringing

    override fun draw(c: Canvas, now: Long) {
        val left = Chrono.tmLeft()
        val total = Chrono.tmSetMs.coerceAtLeast(1)
        Draw.ring(c, Draw.C, Draw.C, 166f, 8f, Draw.FAINT)
        Draw.arc(c, Draw.C, Draw.C, 166f, 0f, 360f * left / total, 8f, if (Chrono.ringing) Draw.RED else Draw.AMBER)
        val s = (left + 999) / 1000
        val txt = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        if (Chrono.ringing) {
            if ((now / 400) % 2 == 0L) Draw.textC(c, "TIME'S UP", Draw.C, 150f, 36f, Draw.RED, Draw.MED)
            Draw.textC(c, "tap to stop", Draw.C, 200f, 18f, Draw.DIM)
            return
        }
        Draw.textC(c, txt, Draw.C, 150f, 66f, Draw.WHITE, Draw.LIGHT)
        if (!Chrono.tmRunning && Chrono.tmPausedLeft < 0) {
            Draw.textC(c, "slide around the rim to set", Draw.C, 196f, 13f, Draw.DIM)
        }
        Chrono.nextAlarm(Rear.app)?.let {
            Draw.textC(c, "Alarm " + DateFormat.getTimeFormat(Rear.app).format(Date(it)), Draw.C, 60f, 15f, Draw.DIM)
        }
        Draw.circle(c, 125f, 270f, 26f, Draw.CARD)
        Icons.draw(c, "reset", 125f, 270f, 22f, Draw.WHITE)
        Draw.circle(c, 235f, 270f, 26f, if (Chrono.tmRunning) Draw.RED else Draw.GREEN)
        Icons.draw(c, if (Chrono.tmRunning) "pause" else "play", 235f, 270f, 22f, 0xFF000000.toInt())
    }

    override fun animating(now: Long) = Chrono.ringing
    override fun nextChangeIn(now: Long) = if (Chrono.tmRunning) 250L else Long.MAX_VALUE

    override fun onTap(x: Float, y: Float): Boolean {
        if (Chrono.ringing) {
            Rear.service?.stopTimerAlarm()
        } else if (y > 235f && x < Draw.C) {
            Chrono.tmReset()
        } else {
            Chrono.tmToggle()
            Rear.service?.scheduleTimer()
        }
        invalidate()
        return true
    }

    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float): Boolean {
        if (Chrono.tmRunning || Chrono.ringing) return false
        if (hypot(x0 - Draw.C, y0 - Draw.C) < 120f) return false
        dragStartSet = Chrono.tmSetMs
        Chrono.tmPausedLeft = -1
        return true
    }

    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {
        // one full turn = 60 minutes, in 30 s steps
        val a0 = angle(x - dx, y - dy)
        var d = angle(x, y) - a0
        if (d > 180) d -= 360f
        if (d < -180) d += 360f
        val ms = dragStartSet + (d / 360f * 3_600_000f).toLong()
        Chrono.tmSetMs = (ms / 30_000 * 30_000).coerceIn(30_000L, 6 * 3_600_000L)
        invalidate()
    }

    private fun angle(x: Float, y: Float) = Math.toDegrees(kotlin.math.atan2((x - Draw.C).toDouble(), -(y - Draw.C).toDouble())).toFloat()
}

class BatteryPage(ui: RearUi) : Page(ui) {
    override val id = "battery"

    override fun onShow() = Battery.update(Rear.app)

    override fun draw(c: Canvas, now: Long) {
        val p = Battery.percent
        val col = when {
            Battery.charging -> Draw.GREEN
            p <= 15 -> Draw.RED
            p <= 30 -> Draw.AMBER
            else -> Draw.ACCENT
        }
        Draw.ring(c, Draw.C, Draw.C, 160f, 12f, Draw.FAINT)
        Draw.arc(c, Draw.C, Draw.C, 160f, 0f, 360f * p / 100f, 12f, col)
        Draw.textC(c, "$p%", Draw.C, 110f, 60f, Draw.WHITE, Draw.LIGHT)
        val lines = ArrayList<Pair<String, String>>()
        if (Battery.charging) {
            lines.add("Charging" to (Battery.plug.ifEmpty { "yes" } + if (Battery.watts() > 0.1f) "  %.1f W".format(Battery.watts()) else ""))
            if (Battery.chargeTimeLeftMs > 0) {
                val m = Battery.chargeTimeLeftMs / 60000
                lines.add("Full in" to if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min")
            }
        } else {
            lines.add("Current" to "${Battery.currentMa} mA")
        }
        lines.add("Temperature" to "%.1f °C".format(Battery.tempC))
        lines.add("Voltage" to "%.2f V".format(Battery.voltage))
        lines.add("Health" to Battery.health)
        var y = 170f
        for ((k, v) in lines) {
            Draw.textL(c, k, 70f, y, 16f, Draw.DIM)
            Draw.textL(c, v, 190f, y, 16f, Draw.WHITE, Draw.MED, 110f)
            y += 30f
        }
    }

    override fun nextChangeIn(now: Long): Long {
        Battery.update(Rear.app)
        return 3000L
    }
}

class StepsPage(ui: RearUi) : Page(ui) {
    override val id = "steps"
    private val listener: () -> Unit = { invalidate() }

    override fun onShow() {
        Rear.sensors.stepListeners.add(listener)
        Rear.sensors.acquire(Rear.sensors.stepCounter, id)
    }

    override fun onHide() {
        Rear.sensors.stepListeners.remove(listener)
        Rear.sensors.release(Rear.sensors.stepCounter, id)
    }

    override fun draw(c: Canvas, now: Long) {
        if (Rear.sensors.stepCounter == null) {
            Draw.textC(c, "No step sensor", Draw.C, Draw.C, 20f, Draw.DIM)
            return
        }
        val st = Rear.steps.today(Rear.sensors.steps)
        val goal = Rear.steps.goal
        Draw.ring(c, Draw.C, Draw.C, 156f, 16f, Draw.FAINT)
        if (st > 0) Draw.arc(c, Draw.C, Draw.C, 156f, 0f, 360f * (st.toFloat() / goal).coerceAtMost(1f), 16f, Draw.GREEN)
        Icons.draw(c, "steps", Draw.C, 100f, 34f, Draw.GREEN)
        Draw.textC(c, if (st < 0) "—" else "%,d".format(st), Draw.C, 175f, 62f, Draw.WHITE, Draw.LIGHT)
        Draw.textC(c, "of %,d steps".format(goal), Draw.C, 222f, 17f, Draw.DIM)
        if (st >= 0) {
            // rough distance: 0.75 m per step
            Draw.textC(c, "%.2f km".format(st * 0.75f / 1000f), Draw.C, 262f, 18f, Draw.DIM, Draw.MED)
        }
        if (st < 0) Draw.textC(c, "waiting for sensor…", Draw.C, 262f, 14f, Draw.FAINT)
    }
}
