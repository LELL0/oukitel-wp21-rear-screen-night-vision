package com.lello.wp21rear.pages

import android.graphics.Canvas
import android.text.format.DateFormat
import com.lello.wp21rear.Rear
import com.lello.wp21rear.data.Battery
import com.lello.wp21rear.data.Chrono
import com.lello.wp21rear.data.NotifRepo
import com.lello.wp21rear.data.Weather
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Icons
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A watch face. [color] is the main colour, [accent] the highlight; [aod] = dim always-on variant. */
abstract class Face(val name: String, val defColor: Int, val defAccent: Int) {
    /** True if the face needs continuous frames (smooth seconds). */
    open val smooth = false
    open val showsSeconds = true
    abstract fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean)

    protected fun hh(cal: Calendar): String {
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return if (Rear.prefs.use24h) "%02d".format(h) else "${if (h % 12 == 0) 12 else h % 12}"
    }

    protected fun mm(cal: Calendar) = "%02d".format(cal.get(Calendar.MINUTE))
    protected fun date(cal: Calendar, pattern: String = "EEE d MMM"): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), pattern), cal).toString()

    companion object {
        val ALL: List<Face> by lazy {
            listOf(
                DigitalFace(), AnalogFace(), MinimalFace(), BatteryRingFace(), WorldClockFace(), ComplicationsFace(),
                BoldFace(), RingsFace(), SegmentFace(), WordClockFace(), BinaryFace(), NeonFace(), RadarFace(),
                DualFace(), DayArcFace(), DotMatrixFace(),
            )
        }

        fun dimmed(color: Int) = Draw.lerpColor(color, 0xFF000000.toInt(), 0.45f)

        /** Renders face [i] (current colours, no photo) into a round 360x360 bitmap for the app. */
        fun preview(i: Int, aod: Boolean = false): android.graphics.Bitmap {
            val b = android.graphics.Bitmap.createBitmap(360, 360, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val clip = android.graphics.Path().apply { addCircle(Draw.C, Draw.C, Draw.C, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            c.drawColor(0xFF000000.toInt())
            val f = ALL[i]
            val cal = Calendar.getInstance()
            f.draw(c, cal, System.currentTimeMillis(), Rear.prefs.faceColor(i, f.defColor), Rear.prefs.faceAccent(i, f.defAccent), aod)
            return b
        }
    }
}

class DigitalFace : Face("Digital", Draw.WHITE, Draw.ACCENT) {
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val col = if (aod) Face.dimmed(color) else color
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, 170f, 96f, col, Draw.LIGHT)
        Draw.textC(c, date(cal).uppercase(), Draw.C, 98f, 22f, if (aod) Face.dimmed(accent) else accent, Draw.MED)
        if (!aod) {
            Draw.textC(c, "%02d".format(cal.get(Calendar.SECOND)), Draw.C, 238f, 30f, Draw.DIM, Draw.LIGHT)
            val b = Battery.percent
            Icons.draw(c, if (Battery.charging) "bolt" else "battery", Draw.C - 28f, 284f, 22f, if (Battery.charging) Draw.GREEN else Draw.DIM)
            Draw.textL(c, "$b%", Draw.C - 14f, 284f, 20f, Draw.DIM)
        }
    }
}

class AnalogFace : Face("Analog", Draw.WHITE, 0xFFFF7043.toInt()) {
    override val smooth = true
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val col = if (aod) Face.dimmed(color) else color
        val cx = Draw.C
        for (i in 0 until 60) {
            val a = i * 6f
            val major = i % 5 == 0
            if (aod && !major) continue
            val r0 = if (major) 150f else 162f
            Draw.line(c, Draw.px(cx, r0, a), Draw.py(cx, r0, a), Draw.px(cx, 172f, a), Draw.py(cx, 172f, a),
                if (major) 5f else 2f, if (major) col else Draw.FAINT)
        }
        if (!aod) Draw.textC(c, date(cal, "EEE d").uppercase(), cx, 240f, 18f, Draw.DIM, Draw.MED)
        val sec = cal.get(Calendar.SECOND) + (ms % 1000) / 1000f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hour = (cal.get(Calendar.HOUR) + min / 60f)
        hand(c, hour * 30f, 90f, 9f, col)
        hand(c, min * 6f, 135f, 6f, col)
        if (!aod) {
            val sa = sec * 6f
            Draw.line(c, Draw.px(cx, -24f, sa), Draw.py(cx, -24f, sa), Draw.px(cx, 160f, sa), Draw.py(cx, 160f, sa), 2.5f, accent)
            Draw.circle(c, cx, cx, 7f, accent)
        } else {
            Draw.circle(c, cx, cx, 6f, col)
        }
    }

    private fun hand(c: Canvas, deg: Float, len: Float, w: Float, color: Int) {
        Draw.line(c, Draw.C, Draw.C, Draw.px(Draw.C, len, deg), Draw.py(Draw.C, len, deg), w, color)
    }
}

class MinimalFace : Face("Minimal", Draw.WHITE, Draw.ACCENT) {
    override val showsSeconds = false
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val col = if (aod) Face.dimmed(color) else color
        Draw.textC(c, hh(cal), Draw.C, 128f, 110f, col, Draw.THIN)
        Draw.textC(c, mm(cal), Draw.C, 240f, 110f, if (aod) Face.dimmed(accent) else accent, Draw.THIN)
    }
}

class BatteryRingFace : Face("Battery ring", Draw.WHITE, Draw.GREEN) {
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val col = if (aod) Face.dimmed(color) else color
        val p = Battery.percent
        val ringColor = when {
            aod -> Face.dimmed(accent)
            p <= 15 -> Draw.RED
            p <= 30 -> Draw.AMBER
            else -> accent
        }
        Draw.ring(c, Draw.C, Draw.C, 160f, 14f, Draw.FAINT)
        Draw.arc(c, Draw.C, Draw.C, 160f, 0f, 360f * p / 100f, 14f, ringColor)
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, 165f, 76f, col, Draw.LIGHT)
        Draw.textC(c, "$p%", Draw.C, 236f, 30f, ringColor, Draw.MED)
        if (Battery.charging) Icons.draw(c, "bolt", Draw.C, 98f, 30f, ringColor)
        else Draw.textC(c, date(cal).uppercase(), Draw.C, 100f, 18f, Draw.DIM, Draw.MED)
    }
}

class WorldClockFace : Face("World clock", Draw.WHITE, Draw.AMBER) {
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val col = if (aod) Face.dimmed(color) else color
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, 110f, 70f, col, Draw.LIGHT)
        Draw.textC(c, date(cal).uppercase(), Draw.C, 62f, 16f, Draw.DIM, Draw.MED)
        val zones = Rear.prefs.worldClockZones.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        var y = 178f
        for (z in zones) {
            val tz = TimeZone.getTimeZone(z)
            val zc = Calendar.getInstance(tz).apply { timeInMillis = cal.timeInMillis }
            val city = z.substringAfterLast('/').replace('_', ' ')
            val diffH = (tz.getOffset(cal.timeInMillis) - cal.timeZone.getOffset(cal.timeInMillis)) / 3_600_000f
            val diff = (if (diffH >= 0) "+" else "") + (if (diffH % 1f == 0f) diffH.toInt().toString() else "%.1f".format(diffH))
            Draw.textL(c, city, 70f, y, 20f, if (aod) Face.dimmed(accent) else accent, Draw.MED, 130f)
            Draw.textL(c, "${hh(zc)}:${mm(zc)}", 200f, y, 22f, col, Draw.REG)
            Draw.textL(c, diff, 262f, y, 14f, Draw.DIM, Draw.REG)
            y += 44f
        }
    }
}

class ComplicationsFace : Face("Complications", Draw.WHITE, Draw.ACCENT) {
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val col = if (aod) Face.dimmed(color) else color
        val acc = if (aod) Face.dimmed(accent) else accent
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, 150f, 78f, col, Draw.LIGHT)
        Draw.textC(c, date(cal, "EEEE d MMMM"), Draw.C, 92f, 18f, acc, Draw.MED, 220f)
        if (aod) return
        // weather (left), steps (centre), battery (right)
        val wy = 238f
        Draw.textC(c, Weather.temp(Rear.prefs), 96f, wy - 8f, 24f, Draw.WHITE, Draw.REG)
        Draw.textC(c, Weather.describe(Weather.code).ifEmpty { "weather" }, 96f, wy + 18f, 12f, Draw.DIM, Draw.REG, 80f)
        val st = if (Rear.running) Rear.steps.today(Rear.sensors.steps) else -1
        val goal = Rear.steps.goal
        Draw.ring(c, Draw.C, wy, 30f, 5f, Draw.FAINT)
        if (st > 0) Draw.arc(c, Draw.C, wy, 30f, 0f, 360f * (st.toFloat() / goal).coerceAtMost(1f), 5f, acc)
        Draw.textC(c, if (st < 0) "--" else if (st >= 10000) "${st / 1000}k" else "$st", Draw.C, wy - 3f, 16f, Draw.WHITE, Draw.MED)
        Icons.draw(c, "steps", Draw.C, wy + 15f, 12f, Draw.DIM)
        Draw.textC(c, "${Battery.percent}%", 264f, wy - 8f, 24f, if (Battery.charging) Draw.GREEN else Draw.WHITE, Draw.REG)
        Draw.textC(c, if (Battery.charging) "charging" else "battery", 264f, wy + 18f, 12f, Draw.DIM, Draw.REG)
        // next alarm and unread count
        val alarm = Chrono.nextAlarm(Rear.app)
        val n = NotifRepo.count()
        val parts = ArrayList<String>()
        if (alarm != null && alarm - System.currentTimeMillis() < 24 * 3_600_000L) {
            parts.add("⏰ " + DateFormat.getTimeFormat(Rear.app).format(java.util.Date(alarm)))
        }
        if (n > 0) parts.add("$n notif.")
        if (parts.isNotEmpty()) Draw.textC(c, parts.joinToString("   "), Draw.C, 300f, 15f, Draw.DIM, Draw.REG, 200f)
    }
}
