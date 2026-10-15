package com.lello.wp21rear.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.cos
import kotlin.math.sin

/** Shared paints, colours and drawing helpers for the 360x360 round panel. */
object Draw {
    const val S = 360f
    const val C = 180f

    val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val REG: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val COND: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val MONO: Typeface = Typeface.create("monospace", Typeface.NORMAL)

    const val WHITE = Color.WHITE
    const val DIM = 0xFF9AA0A6.toInt()
    const val FAINT = 0xFF3C4043.toInt()
    const val ACCENT = 0xFF4FC3F7.toInt()
    const val GREEN = 0xFF66BB6A.toInt()
    const val RED = 0xFFEF5350.toInt()
    const val AMBER = 0xFFFFB300.toInt()
    const val CARD = 0xFF1E1F22.toInt()

    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textAlign = Paint.Align.CENTER }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    /** Draws text centred horizontally at x with its vertical centre at cy. */
    fun textC(c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int = WHITE, tf: Typeface = REG, maxW: Float = 0f) {
        text.textSize = size
        text.color = color
        text.typeface = tf
        text.textAlign = Paint.Align.CENTER
        val str = if (maxW > 0) TextUtils.ellipsize(s, text, maxW, TextUtils.TruncateAt.END).toString() else s
        val fm = text.fontMetrics
        c.drawText(str, x, cy - (fm.ascent + fm.descent) / 2, text)
    }

    fun textL(c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int = WHITE, tf: Typeface = REG, maxW: Float = 0f) {
        text.textSize = size
        text.color = color
        text.typeface = tf
        text.textAlign = Paint.Align.LEFT
        val str = if (maxW > 0) TextUtils.ellipsize(s, text, maxW, TextUtils.TruncateAt.END).toString() else s
        val fm = text.fontMetrics
        c.drawText(str, x, cy - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.CENTER
    }

    fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.color = color
        c.drawCircle(x, y, r, fill)
    }

    fun ring(c: Canvas, x: Float, y: Float, r: Float, w: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = w
        c.drawCircle(x, y, r, stroke)
    }

    /** Arc on a circle centred at (cx, cy); angles in degrees, 0 = 12 o'clock, clockwise. */
    fun arc(c: Canvas, cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, w: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = w
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, startDeg - 90f, sweepDeg, false, stroke)
    }

    fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill.color = color
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, fill)
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = w
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    /** Point on the circle, 0 deg = 12 o'clock, clockwise. */
    fun px(cx: Float, r: Float, deg: Float) = cx + r * sin(Math.toRadians(deg.toDouble())).toFloat()
    fun py(cy: Float, r: Float, deg: Float) = cy - r * cos(Math.toRadians(deg.toDouble())).toFloat()

    /** Centre-crops [b] into the circle of radius r centred at (cx, cy). */
    fun bitmapCircle(c: Canvas, b: Bitmap, cx: Float, cy: Float, r: Float, alpha: Int = 255) {
        val save = c.save()
        val p = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        c.clipPath(p)
        bitmapCover(c, b, cx - r, cy - r, cx + r, cy + r, alpha)
        c.restoreToCount(save)
    }

    /** Draws [b] scaled to cover the rectangle (centre crop). */
    fun bitmapCover(c: Canvas, b: Bitmap, l: Float, t: Float, r: Float, btm: Float, alpha: Int = 255) {
        val w = r - l
        val h = btm - t
        val scale = maxOf(w / b.width, h / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        rect.set(l + (w - dw) / 2, t + (h - dh) / 2, l + (w + dw) / 2, t + (h + dh) / 2)
        bmpPaint.alpha = alpha
        c.drawBitmap(b, null, rect, bmpPaint)
        bmpPaint.alpha = 255
    }

    fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xff) + (((b shr s) and 0xff) - ((a shr s) and 0xff)) * tt).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** Page indicator dots along the bottom edge. */
    fun dots(c: Canvas, count: Int, current: Float) {
        if (count <= 1) return
        val gap = 11f
        val x0 = C - (count - 1) * gap / 2
        for (i in 0 until count) {
            val d = kotlin.math.abs(current - i).coerceAtMost(1f)
            circle(c, x0 + i * gap, 340f, 2.6f + (1 - d) * 1.4f, lerpColor(WHITE, FAINT, d))
        }
    }

    fun easeOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1 - (1 - x) * (1 - x) * (1 - x)
    }
}
