package com.lello.wp21rear.pages

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import com.lello.wp21rear.data.Battery
import com.lello.wp21rear.ui.Draw
import java.util.Calendar

/** Huge stacked hours over minutes. */
class BoldFace : Face("Bold", Draw.WHITE, 0xFFFF7043.toInt()) {
    override val showsSeconds = false
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        Draw.textC(c, hh(cal), Draw.C + 4f, 122f, 128f, if (aod) Face.dimmed(color) else color, Draw.BOLD)
        Draw.textC(c, mm(cal), Draw.C + 4f, 240f, 128f, if (aod) Face.dimmed(accent) else accent, Draw.BOLD)
    }
}

/** Concentric progress rings for hours, minutes and seconds. */
class RingsFace : Face("Rings", Draw.WHITE, Draw.ACCENT) {
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
        val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val s = cal.get(Calendar.SECOND).toFloat()
        val dim = { col: Int -> if (aod) Face.dimmed(col) else col }
        Draw.ring(c, Draw.C, Draw.C, 160f, 14f, Draw.FAINT)
        Draw.arc(c, Draw.C, Draw.C, 160f, 0f, h / 12f * 360f, 14f, dim(accent))
        Draw.ring(c, Draw.C, Draw.C, 136f, 14f, Draw.FAINT)
        Draw.arc(c, Draw.C, Draw.C, 136f, 0f, m / 60f * 360f, 14f, dim(Draw.GREEN))
        if (!aod) {
            Draw.ring(c, Draw.C, Draw.C, 112f, 14f, Draw.FAINT)
            Draw.arc(c, Draw.C, Draw.C, 112f, 0f, (s + 1) / 60f * 360f, 14f, Draw.AMBER)
        }
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, Draw.C - 6f, 46f, dim(color), Draw.LIGHT)
        Draw.textC(c, date(cal, "EEE d").uppercase(), Draw.C, Draw.C + 32f, 15f, Draw.DIM, Draw.MED)
    }
}

/** Retro seven-segment LCD with faint unlit segments. */
class SegmentFace : Face("Segment LCD", 0xFFFFB300.toInt(), 0xFFFFB300.toInt()) {
    // segments a b c d e f g as (x0,y0,x1,y1) in a 1x2 digit box
    private val segs = arrayOf(
        floatArrayOf(0.1f, 0f, 0.9f, 0f), floatArrayOf(1f, 0.1f, 1f, 0.9f), floatArrayOf(1f, 1.1f, 1f, 1.9f),
        floatArrayOf(0.1f, 2f, 0.9f, 2f), floatArrayOf(0f, 1.1f, 0f, 1.9f), floatArrayOf(0f, 0.1f, 0f, 0.9f),
        floatArrayOf(0.1f, 1f, 0.9f, 1f),
    )
    private val digits = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)

    private fun digit(c: Canvas, d: Int, x: Float, y: Float, w: Float, on: Int, off: Int) {
        for (i in 0 until 7) {
            val lit = digits[d] shr i and 1 == 1
            val s = segs[i]
            Draw.line(c, x + s[0] * w, y + s[1] * w, x + s[2] * w, y + s[3] * w, w * 0.16f, if (lit) on else off)
        }
    }

    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val on = if (aod) Face.dimmed(color) else color
        val off = Draw.withAlpha(color, 28)
        val h = cal.get(Calendar.HOUR_OF_DAY).let { if (com.lello.wp21rear.Rear.prefs.use24h) it else (it % 12).let { v -> if (v == 0) 12 else v } }
        val m = cal.get(Calendar.MINUTE)
        val w = 34f
        val y = 132f
        val xs = floatArrayOf(52f, 106f, 202f, 256f)
        val ds = intArrayOf(h / 10, h % 10, m / 10, m % 10)
        for (i in 0..3) digit(c, ds[i], xs[i], y, w, on, off)
        if (aod || cal.get(Calendar.SECOND) % 2 == 0) {
            Draw.circle(c, Draw.C, y + w * 0.6f, 4.5f, on)
            Draw.circle(c, Draw.C, y + w * 1.4f, 4.5f, on)
        }
        if (!aod) {
            Draw.textC(c, date(cal, "EEE d MMM").uppercase(), Draw.C, 98f, 16f, Draw.withAlpha(color, 170), Draw.MONO)
            Draw.textC(c, "%02d".format(cal.get(Calendar.SECOND)), Draw.C, 238f, 22f, Draw.withAlpha(color, 170), Draw.MONO)
            Draw.textC(c, "BAT ${Battery.percent}%", Draw.C, 270f, 14f, Draw.withAlpha(color, 140), Draw.MONO)
        }
    }
}

/** The time in words, to the nearest five minutes. */
class WordClockFace : Face("Words", Draw.WHITE, Draw.ACCENT) {
    override val showsSeconds = false
    private fun mins(m: Int) = when (m) {
        5 -> "five"
        10 -> "ten"
        20 -> "twenty"
        25 -> "twenty-five"
        else -> "$m"
    }

    private val nums = arrayOf("twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val m = cal.get(Calendar.MINUTE)
        val r = ((m + 2) / 5) * 5
        var h = cal.get(Calendar.HOUR)
        val lines = ArrayList<String>()
        lines.add("it is")
        when {
            r == 0 || r == 60 -> { if (r == 60) h++; lines.add(nums[h % 12]); lines.add("o'clock") }
            r == 15 -> { lines.add("quarter past"); lines.add(nums[h % 12]) }
            r == 30 -> { lines.add("half past"); lines.add(nums[h % 12]) }
            r == 45 -> { lines.add("quarter to"); lines.add(nums[(h + 1) % 12]) }
            r < 30 -> { lines.add(mins(r) + " past"); lines.add(nums[h % 12]) }
            else -> { lines.add(mins(60 - r) + " to"); lines.add(nums[(h + 1) % 12]) }
        }
        val top = Draw.C - (lines.size - 1) * 26f
        for ((i, l) in lines.withIndex()) {
            val hour = i == lines.size - 1 || l == "o'clock"
            val col = if (hour) accent else color
            Draw.textC(c, l.uppercase(), Draw.C, top + i * 52f, if (hour) 42f else 30f,
                if (aod) Face.dimmed(col) else col, if (hour) Draw.BOLD else Draw.LIGHT, 300f)
        }
    }
}

/** Binary-coded decimal dots: one column per digit of HH MM SS. */
class BinaryFace : Face("Binary", Draw.WHITE, Draw.GREEN) {
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val v = intArrayOf(
            cal.get(Calendar.HOUR_OF_DAY) / 10, cal.get(Calendar.HOUR_OF_DAY) % 10,
            cal.get(Calendar.MINUTE) / 10, cal.get(Calendar.MINUTE) % 10,
            cal.get(Calendar.SECOND) / 10, cal.get(Calendar.SECOND) % 10,
        )
        val cols = if (aod) 4 else 6
        val gap = 44f
        val x0 = Draw.C - (cols - 1) * gap / 2 - (if (cols == 6) 8f else 4f)
        for (col in 0 until cols) {
            val x = x0 + col * gap + (col / 2) * 8f
            for (bit in 0..3) {
                val y = 230f - bit * 40f
                val lit = (v[col] shr bit) and 1 == 1
                Draw.circle(c, x, y, 14f, if (lit) (if (aod) Face.dimmed(accent) else accent) else Draw.FAINT)
            }
        }
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, 282f, 22f, if (aod) Face.dimmed(color) else color, Draw.MONO)
    }
}

/** Glowing outlined digits. */
class NeonFace : Face("Neon", 0xFFEC407A.toInt(), 0xFF26C6DA.toInt()) {
    override val showsSeconds = false
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Draw.BOLD
    }

    private fun neon(c: Canvas, s: String, y: Float, size: Float, col: Int, aod: Boolean) {
        glow.textSize = size
        if (!aod) {
            glow.color = col
            glow.strokeWidth = 8f
            glow.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            c.drawText(s, Draw.C, y, glow)
        }
        glow.maskFilter = null
        glow.strokeWidth = 3f
        glow.color = if (aod) Face.dimmed(col) else Draw.lerpColor(col, Draw.WHITE, 0.5f)
        c.drawText(s, Draw.C, y, glow)
    }

    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        neon(c, hh(cal), 160f, 110f, color, aod)
        neon(c, mm(cal), 280f, 110f, accent, aod)
    }
}

/** Rotating radar sweep marking the seconds. */
class RadarFace : Face("Radar", Draw.GREEN, Draw.GREEN) {
    override val smooth = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()

    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val grid = Draw.withAlpha(accent, 70)
        for (r in listOf(60f, 110f, 160f)) Draw.ring(c, Draw.C, Draw.C, r, 1.5f, grid)
        Draw.line(c, 20f, Draw.C, 340f, Draw.C, 1.5f, grid)
        Draw.line(c, Draw.C, 20f, Draw.C, 340f, 1.5f, grid)
        if (!aod) {
            val sec = cal.get(Calendar.SECOND) + (ms % 1000) / 1000f
            val angle = sec * 6f
            val sg = SweepGradient(Draw.C, Draw.C, intArrayOf(0, 0, Draw.withAlpha(accent, 150)), floatArrayOf(0f, 0.75f, 1f))
            m.setRotate(angle - 90f, Draw.C, Draw.C)
            sg.setLocalMatrix(m)
            p.shader = sg
            c.drawCircle(Draw.C, Draw.C, 170f, p)
            p.shader = null
        }
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, Draw.C, 64f, if (aod) Face.dimmed(color) else color, Draw.MONO)
        if (!aod) Draw.textC(c, date(cal, "EEE d").uppercase(), Draw.C, Draw.C + 48f, 16f, Draw.withAlpha(color, 180), Draw.MONO)
    }
}

/** Small analog dial on top, digital time below. */
class DualFace : Face("Dual", Draw.WHITE, 0xFFFF7043.toInt()) {
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val col = if (aod) Face.dimmed(color) else color
        val cx = Draw.C
        val cy = 122f
        val r = 78f
        Draw.ring(c, cx, cy, r, 3f, Draw.FAINT)
        for (i in 0 until 12) {
            val a = i * 30f
            Draw.line(c, Draw.px(cx, r - 10f, a), Draw.py(cy, r - 10f, a), Draw.px(cx, r - 2f, a), Draw.py(cy, r - 2f, a), 3f, col)
        }
        val min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val hour = cal.get(Calendar.HOUR) + min / 60f
        Draw.line(c, cx, cy, Draw.px(cx, 44f, hour * 30f), Draw.py(cy, 44f, hour * 30f), 6f, col)
        Draw.line(c, cx, cy, Draw.px(cx, 64f, min * 6f), Draw.py(cy, 64f, min * 6f), 4f, col)
        if (!aod) {
            val s = cal.get(Calendar.SECOND) * 6f
            Draw.line(c, cx, cy, Draw.px(cx, 68f, s), Draw.py(cy, 68f, s), 2f, accent)
        }
        Draw.circle(c, cx, cy, 5f, if (aod) col else accent)
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, 252f, 54f, col, Draw.LIGHT)
        if (!aod) Draw.textC(c, date(cal).uppercase(), Draw.C, 296f, 15f, Draw.DIM, Draw.MED)
    }
}

/** The day as an arc from 06:00 to 18:00 with the sun on it (moon at night). */
class DayArcFace : Face("Day arc", Draw.WHITE, Draw.AMBER) {
    override val showsSeconds = false
    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val t = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        val day = t in 6f..18f
        val frac = if (day) (t - 6f) / 12f else ((t + 6f) % 24f) / 12f
        val col = if (aod) Face.dimmed(color) else color
        val sunCol = if (aod) Face.dimmed(accent) else if (day) accent else 0xFF90CAF9.toInt()
        // arc across the upper half, -90..+90 degrees
        Draw.arc(c, Draw.C, 210f, 140f, -90f, 180f, 3f, Draw.FAINT)
        Draw.arc(c, Draw.C, 210f, 140f, -90f, 180f * frac, 3f, sunCol)
        val a = -90f + 180f * frac
        Draw.circle(c, Draw.px(Draw.C, 140f, a), Draw.py(210f, 140f, a), 14f, sunCol)
        Draw.line(c, 30f, 210f, 330f, 210f, 1.5f, Draw.FAINT)
        Draw.textC(c, "${hh(cal)}:${mm(cal)}", Draw.C, 170f, 58f, col, Draw.LIGHT)
        if (!aod) {
            Draw.textC(c, date(cal).uppercase(), Draw.C, 246f, 16f, Draw.DIM, Draw.MED)
            Draw.textC(c, if (day) "day" else "night", Draw.C, 274f, 13f, sunCol, Draw.MED)
        }
    }
}

/** Digits drawn as a 5x7 dot matrix. */
class DotMatrixFace : Face("Dot matrix", 0xFFEF5350.toInt(), 0xFFEF5350.toInt()) {
    override val showsSeconds = false
    private val font = arrayOf(
        intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E), intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
        intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F), intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
        intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02), intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
        intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E), intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
        intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E), intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
    )

    private fun glyph(c: Canvas, d: Int, x: Float, y: Float, s: Float, on: Int, off: Int) {
        for (row in 0 until 7) for (col in 0 until 5) {
            val lit = font[d][row] shr (4 - col) and 1 == 1
            Draw.circle(c, x + col * s, y + row * s, s * 0.38f, if (lit) on else off)
        }
    }

    override fun draw(c: Canvas, cal: Calendar, ms: Long, color: Int, accent: Int, aod: Boolean) {
        val on = if (aod) Face.dimmed(color) else color
        val off = Draw.withAlpha(color, 22)
        val h = hh(cal).padStart(2, '0')
        val m = mm(cal)
        val s = 9f
        val w = 5 * s + 8f
        val y = 136f
        val total = 4 * w + 18f
        var x = Draw.C - total / 2 + s / 2
        for ((i, ch) in (h + m).withIndex()) {
            glyph(c, ch - '0', x, y, s, on, off)
            x += w
            if (i == 1) {
                Draw.circle(c, x - 2f, y + 2 * s, 3.5f, on)
                Draw.circle(c, x - 2f, y + 4 * s, 3.5f, on)
                x += 18f
            }
        }
        if (!aod) Draw.textC(c, date(cal).uppercase(), Draw.C, 234f, 16f, Draw.withAlpha(color, 170), Draw.MONO)
    }
}
