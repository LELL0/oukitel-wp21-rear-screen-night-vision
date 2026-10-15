package com.lello.wp21rear.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Small vector icons drawn with Canvas primitives, sized by [s] (the icon box edge) and centred
 * at (x, y). No resources, so they scale cleanly on the panel and in the mirror.
 */
object Icons {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()

    private fun fill(color: Int) = p.apply { style = Paint.Style.FILL; this.color = color }
    private fun line(color: Int, w: Float) = p.apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = w }

    fun draw(c: Canvas, name: String, x: Float, y: Float, s: Float, color: Int) {
        val h = s / 2
        val w = s * 0.09f
        path.reset()
        when (name) {
            "play" -> {
                path.moveTo(x - h * 0.55f, y - h * 0.75f); path.lineTo(x + h * 0.8f, y); path.lineTo(x - h * 0.55f, y + h * 0.75f); path.close()
                c.drawPath(path, fill(color))
            }
            "pause" -> {
                r.set(x - h * 0.6f, y - h * 0.7f, x - h * 0.15f, y + h * 0.7f); c.drawRoundRect(r, w, w, fill(color))
                r.set(x + h * 0.15f, y - h * 0.7f, x + h * 0.6f, y + h * 0.7f); c.drawRoundRect(r, w, w, fill(color))
            }
            "next", "prev" -> {
                val d = if (name == "next") 1 else -1
                path.moveTo(x - d * h * 0.7f, y - h * 0.6f); path.lineTo(x + d * h * 0.35f, y); path.lineTo(x - d * h * 0.7f, y + h * 0.6f); path.close()
                c.drawPath(path, fill(color))
                r.set(minOf(x + d * h * 0.4f, x + d * h * 0.62f), y - h * 0.6f, maxOf(x + d * h * 0.4f, x + d * h * 0.62f), y + h * 0.6f)
                c.drawRect(r, fill(color))
            }
            "flashlight" -> {
                path.moveTo(x - h * 0.45f, y - h * 0.8f); path.lineTo(x + h * 0.45f, y - h * 0.8f); path.lineTo(x + h * 0.45f, y - h * 0.4f)
                path.lineTo(x + h * 0.2f, y - h * 0.05f); path.lineTo(x + h * 0.2f, y + h * 0.85f); path.lineTo(x - h * 0.2f, y + h * 0.85f)
                path.lineTo(x - h * 0.2f, y - h * 0.05f); path.lineTo(x - h * 0.45f, y - h * 0.4f); path.close()
                c.drawPath(path, line(color, w))
                c.drawCircle(x, y + h * 0.25f, w * 0.8f, fill(color))
            }
            "wifi" -> {
                line(color, w)
                for (i in 1..3) {
                    val rr = h * 0.3f * i
                    r.set(x - rr, y + h * 0.6f - rr, x + rr, y + h * 0.6f + rr)
                    c.drawArc(r, 225f, 90f, false, p)
                }
                c.drawCircle(x, y + h * 0.55f, w * 0.9f, fill(color))
            }
            "bluetooth" -> {
                line(color, w)
                path.moveTo(x - h * 0.45f, y - h * 0.4f); path.lineTo(x + h * 0.4f, y + h * 0.4f); path.lineTo(x, y + h * 0.8f)
                path.lineTo(x, y - h * 0.8f); path.lineTo(x + h * 0.4f, y - h * 0.4f); path.lineTo(x - h * 0.45f, y + h * 0.4f)
                c.drawPath(path, p)
            }
            "dnd" -> {
                c.drawCircle(x, y, h * 0.8f, line(color, w))
                c.drawLine(x - h * 0.4f, y, x + h * 0.4f, y, line(color, w * 1.3f))
            }
            "data" -> {
                line(color, w)
                c.drawLine(x - h * 0.3f, y - h * 0.7f, x - h * 0.3f, y + h * 0.7f, p)
                c.drawLine(x - h * 0.3f, y - h * 0.7f, x - h * 0.6f, y - h * 0.35f, p)
                c.drawLine(x + h * 0.3f, y - h * 0.7f, x + h * 0.3f, y + h * 0.7f, p)
                c.drawLine(x + h * 0.3f, y + h * 0.7f, x + h * 0.6f, y + h * 0.35f, p)
            }
            "rotate" -> {
                line(color, w)
                r.set(x - h * 0.65f, y - h * 0.65f, x + h * 0.65f, y + h * 0.65f)
                c.drawArc(r, -60f, 280f, false, p)
                val ax = x + h * 0.33f; val ay = y - h * 0.56f
                c.drawLine(ax, ay, ax + h * 0.32f, ay - h * 0.12f, p)
                c.drawLine(ax, ay, ax + h * 0.1f, ay + h * 0.3f, p)
            }
            "lock" -> {
                line(color, w)
                r.set(x - h * 0.35f, y - h * 0.8f, x + h * 0.35f, y - h * 0.1f)
                c.drawArc(r, 180f, 180f, false, p)
                c.drawLine(x - h * 0.35f, y - h * 0.45f, x - h * 0.35f, y - h * 0.1f, p)
                c.drawLine(x + h * 0.35f, y - h * 0.45f, x + h * 0.35f, y - h * 0.1f, p)
                r.set(x - h * 0.6f, y - h * 0.15f, x + h * 0.6f, y + h * 0.75f)
                c.drawRoundRect(r, w, w, fill(color))
            }
            "bell", "bell_off" -> {
                line(color, w)
                path.moveTo(x - h * 0.6f, y + h * 0.45f); path.lineTo(x - h * 0.45f, y + h * 0.2f); path.lineTo(x - h * 0.45f, y - h * 0.2f)
                path.cubicTo(x - h * 0.45f, y - h * 0.8f, x + h * 0.45f, y - h * 0.8f, x + h * 0.45f, y - h * 0.2f)
                path.lineTo(x + h * 0.45f, y + h * 0.2f); path.lineTo(x + h * 0.6f, y + h * 0.45f); path.close()
                c.drawPath(path, p)
                c.drawLine(x - h * 0.15f, y + h * 0.7f, x + h * 0.15f, y + h * 0.7f, p)
                if (name == "bell_off") c.drawLine(x - h * 0.8f, y - h * 0.8f, x + h * 0.8f, y + h * 0.8f, line(color, w * 1.2f))
            }
            "vibrate" -> {
                line(color, w)
                r.set(x - h * 0.3f, y - h * 0.7f, x + h * 0.3f, y + h * 0.7f)
                c.drawRoundRect(r, w, w, p)
                c.drawLine(x - h * 0.55f, y - h * 0.35f, x - h * 0.55f, y + h * 0.35f, p)
                c.drawLine(x + h * 0.55f, y - h * 0.35f, x + h * 0.55f, y + h * 0.35f, p)
                c.drawLine(x - h * 0.8f, y - h * 0.2f, x - h * 0.8f, y + h * 0.2f, p)
                c.drawLine(x + h * 0.8f, y - h * 0.2f, x + h * 0.8f, y + h * 0.2f, p)
            }
            "camera" -> {
                line(color, w)
                r.set(x - h * 0.8f, y - h * 0.45f, x + h * 0.8f, y + h * 0.65f)
                c.drawRoundRect(r, w * 1.5f, w * 1.5f, p)
                c.drawLine(x - h * 0.3f, y - h * 0.6f, x + h * 0.3f, y - h * 0.6f, p)
                c.drawCircle(x, y + h * 0.1f, h * 0.3f, p)
            }
            "video" -> {
                r.set(x - h * 0.8f, y - h * 0.45f, x + h * 0.25f, y + h * 0.45f)
                c.drawRoundRect(r, w, w, fill(color))
                path.moveTo(x + h * 0.3f, y); path.lineTo(x + h * 0.8f, y - h * 0.4f); path.lineTo(x + h * 0.8f, y + h * 0.4f); path.close()
                c.drawPath(path, p)
            }
            "flip" -> {
                line(color, w)
                r.set(x - h * 0.6f, y - h * 0.6f, x + h * 0.6f, y + h * 0.6f)
                c.drawArc(r, 200f, 140f, false, p)
                c.drawArc(r, 20f, 140f, false, p)
                c.drawLine(x + h * 0.6f, y - h * 0.2f, x + h * 0.75f, y - h * 0.45f, p)
                c.drawLine(x - h * 0.6f, y + h * 0.2f, x - h * 0.75f, y + h * 0.45f, p)
            }
            "flash", "flash_off" -> {
                path.moveTo(x + h * 0.15f, y - h * 0.85f); path.lineTo(x - h * 0.45f, y + h * 0.1f); path.lineTo(x - h * 0.02f, y + h * 0.1f)
                path.lineTo(x - h * 0.15f, y + h * 0.85f); path.lineTo(x + h * 0.45f, y - h * 0.1f); path.lineTo(x + h * 0.02f, y - h * 0.1f); path.close()
                c.drawPath(path, fill(color))
                if (name == "flash_off") c.drawLine(x - h * 0.7f, y - h * 0.7f, x + h * 0.7f, y + h * 0.7f, line(color, w))
            }
            "timer" -> {
                line(color, w)
                c.drawCircle(x, y + h * 0.1f, h * 0.65f, p)
                c.drawLine(x, y + h * 0.1f, x, y - h * 0.3f, p)
                c.drawLine(x - h * 0.2f, y - h * 0.8f, x + h * 0.2f, y - h * 0.8f, p)
            }
            "moon" -> {
                path.addCircle(x, y, h * 0.7f, Path.Direction.CW)
                val cut = Path().apply { addCircle(x + h * 0.35f, y - h * 0.3f, h * 0.55f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, fill(color))
            }
            "close" -> {
                line(color, w * 1.2f)
                c.drawLine(x - h * 0.55f, y - h * 0.55f, x + h * 0.55f, y + h * 0.55f, p)
                c.drawLine(x + h * 0.55f, y - h * 0.55f, x - h * 0.55f, y + h * 0.55f, p)
            }
            "check" -> {
                line(color, w * 1.2f)
                c.drawLine(x - h * 0.6f, y, x - h * 0.15f, y + h * 0.45f, p)
                c.drawLine(x - h * 0.15f, y + h * 0.45f, x + h * 0.65f, y - h * 0.45f, p)
            }
            "phone", "phone_end" -> {
                val save = c.save()
                if (name == "phone_end") c.rotate(135f, x, y)
                line(color, w * 2.2f)
                r.set(x - h * 0.7f, y - h * 0.7f, x + h * 0.7f, y + h * 0.7f)
                c.drawArc(r, 100f, 70f, false, p)
                // earpiece and mouthpiece
                c.drawLine(x - h * 0.75f, y - h * 0.2f, x - h * 0.45f, y - h * 0.65f, p)
                c.drawLine(x + h * 0.2f, y + h * 0.75f, x + h * 0.65f, y + h * 0.45f, p)
                c.restoreToCount(save)
            }
            "mic", "mic_off" -> {
                r.set(x - h * 0.25f, y - h * 0.8f, x + h * 0.25f, y + h * 0.2f)
                c.drawRoundRect(r, h * 0.25f, h * 0.25f, fill(color))
                line(color, w)
                r.set(x - h * 0.5f, y - h * 0.3f, x + h * 0.5f, y + h * 0.45f)
                c.drawArc(r, 0f, 180f, false, p)
                c.drawLine(x, y + h * 0.45f, x, y + h * 0.8f, p)
                if (name == "mic_off") c.drawLine(x - h * 0.7f, y - h * 0.7f, x + h * 0.7f, y + h * 0.7f, line(color, w * 1.2f))
            }
            "speaker", "volume" -> {
                path.moveTo(x - h * 0.75f, y - h * 0.25f); path.lineTo(x - h * 0.4f, y - h * 0.25f); path.lineTo(x, y - h * 0.65f)
                path.lineTo(x, y + h * 0.65f); path.lineTo(x - h * 0.4f, y + h * 0.25f); path.lineTo(x - h * 0.75f, y + h * 0.25f); path.close()
                c.drawPath(path, fill(color))
                line(color, w)
                r.set(x - h * 0.3f, y - h * 0.4f, x + h * 0.4f, y + h * 0.4f); c.drawArc(r, -50f, 100f, false, p)
                r.set(x - h * 0.4f, y - h * 0.7f, x + h * 0.75f, y + h * 0.7f); c.drawArc(r, -50f, 100f, false, p)
            }
            "battery" -> {
                line(color, w)
                r.set(x - h * 0.4f, y - h * 0.65f, x + h * 0.4f, y + h * 0.85f)
                c.drawRoundRect(r, w, w, p)
                c.drawLine(x - h * 0.15f, y - h * 0.8f, x + h * 0.15f, y - h * 0.8f, p)
            }
            "bolt" -> {
                path.moveTo(x + h * 0.1f, y - h * 0.85f); path.lineTo(x - h * 0.45f, y + h * 0.1f); path.lineTo(x, y + h * 0.1f)
                path.lineTo(x - h * 0.1f, y + h * 0.85f); path.lineTo(x + h * 0.45f, y - h * 0.1f); path.lineTo(x, y - h * 0.1f); path.close()
                c.drawPath(path, fill(color))
            }
            "steps" -> {
                fill(color)
                r.set(x - h * 0.6f, y - h * 0.75f, x - h * 0.1f, y + h * 0.15f); c.drawOval(r, p)
                c.drawCircle(x - h * 0.35f, y + h * 0.42f, h * 0.18f, p)
                r.set(x + h * 0.1f, y - h * 0.45f, x + h * 0.6f, y + h * 0.45f); c.drawOval(r, p)
                c.drawCircle(x + h * 0.35f, y + h * 0.72f, h * 0.18f, p)
            }
            "compass" -> {
                c.drawCircle(x, y, h * 0.8f, line(color, w))
                path.moveTo(x + h * 0.35f, y - h * 0.35f); path.lineTo(x + h * 0.1f, y + h * 0.1f); path.lineTo(x - h * 0.35f, y + h * 0.35f)
                path.lineTo(x - h * 0.1f, y - h * 0.1f); path.close()
                c.drawPath(path, fill(color))
            }
            "level" -> {
                line(color, w)
                r.set(x - h * 0.85f, y - h * 0.3f, x + h * 0.85f, y + h * 0.3f)
                c.drawRoundRect(r, h * 0.3f, h * 0.3f, p)
                c.drawCircle(x, y, h * 0.15f, fill(color))
                line(color, w)
                c.drawLine(x - h * 0.3f, y - h * 0.3f, x - h * 0.3f, y + h * 0.3f, p)
                c.drawLine(x + h * 0.3f, y - h * 0.3f, x + h * 0.3f, y + h * 0.3f, p)
            }
            "stopwatch" -> {
                line(color, w)
                c.drawCircle(x, y + h * 0.1f, h * 0.65f, p)
                c.drawLine(x, y + h * 0.1f, x + h * 0.3f, y - h * 0.2f, p)
                c.drawLine(x, y - h * 0.55f, x, y - h * 0.8f, p)
                c.drawLine(x - h * 0.2f, y - h * 0.85f, x + h * 0.2f, y - h * 0.85f, p)
            }
            "photo" -> {
                line(color, w)
                r.set(x - h * 0.8f, y - h * 0.65f, x + h * 0.8f, y + h * 0.65f)
                c.drawRoundRect(r, w, w, p)
                path.moveTo(x - h * 0.65f, y + h * 0.5f); path.lineTo(x - h * 0.2f, y - h * 0.05f); path.lineTo(x + h * 0.1f, y + h * 0.25f)
                path.lineTo(x + h * 0.3f, y + h * 0.05f); path.lineTo(x + h * 0.65f, y + h * 0.5f); path.close()
                c.drawPath(path, fill(color))
                c.drawCircle(x + h * 0.35f, y - h * 0.3f, h * 0.12f, p)
            }
            "music" -> {
                fill(color)
                c.drawCircle(x - h * 0.35f, y + h * 0.5f, h * 0.25f, p)
                c.drawCircle(x + h * 0.45f, y + h * 0.35f, h * 0.25f, p)
                line(color, w)
                c.drawLine(x - h * 0.12f, y + h * 0.5f, x - h * 0.12f, y - h * 0.65f, p)
                c.drawLine(x + h * 0.68f, y + h * 0.35f, x + h * 0.68f, y - h * 0.8f, p)
                c.drawLine(x - h * 0.12f, y - h * 0.65f, x + h * 0.68f, y - h * 0.8f, line(color, w * 1.8f))
            }
            "gear" -> {
                line(color, w * 1.4f)
                for (i in 0 until 8) {
                    val a = i * 45f
                    c.drawLine(Draw.px(x, h * 0.5f, a), Draw.py(y, h * 0.5f, a), Draw.px(x, h * 0.8f, a), Draw.py(y, h * 0.8f, a), p)
                }
                c.drawCircle(x, y, h * 0.5f, fill(color))
                c.drawCircle(x, y, h * 0.2f, fill(0xFF000000.toInt()))
            }
            "power" -> {
                line(color, w)
                r.set(x - h * 0.65f, y - h * 0.6f, x + h * 0.65f, y + h * 0.7f)
                c.drawArc(r, -60f, 300f, false, p)
                c.drawLine(x, y - h * 0.8f, x, y, p)
            }
            "notif" -> {
                line(color, w)
                r.set(x - h * 0.75f, y - h * 0.6f, x + h * 0.75f, y + h * 0.6f)
                c.drawRoundRect(r, w * 1.5f, w * 1.5f, p)
                c.drawLine(x - h * 0.45f, y - h * 0.2f, x + h * 0.45f, y - h * 0.2f, p)
                c.drawLine(x - h * 0.45f, y + h * 0.15f, x + h * 0.15f, y + h * 0.15f, p)
            }
            "back" -> {
                line(color, w * 1.2f)
                c.drawLine(x + h * 0.15f, y - h * 0.5f, x - h * 0.35f, y, p)
                c.drawLine(x - h * 0.35f, y, x + h * 0.15f, y + h * 0.5f, p)
            }
            "plus", "minus" -> {
                line(color, w * 1.2f)
                c.drawLine(x - h * 0.55f, y, x + h * 0.55f, y, p)
                if (name == "plus") c.drawLine(x, y - h * 0.55f, x, y + h * 0.55f, p)
            }
            "reset" -> {
                line(color, w)
                r.set(x - h * 0.6f, y - h * 0.6f, x + h * 0.6f, y + h * 0.6f)
                c.drawArc(r, 200f, 290f, false, p)
                c.drawLine(x - h * 0.6f, y - h * 0.15f, x - h * 0.75f, y - h * 0.5f, p)
                c.drawLine(x - h * 0.6f, y - h * 0.15f, x - h * 0.25f, y - h * 0.25f, p)
            }
            "flag" -> {
                line(color, w)
                c.drawLine(x - h * 0.5f, y - h * 0.8f, x - h * 0.5f, y + h * 0.8f, p)
                path.moveTo(x - h * 0.5f, y - h * 0.75f); path.lineTo(x + h * 0.6f, y - h * 0.45f); path.lineTo(x - h * 0.5f, y - h * 0.1f); path.close()
                c.drawPath(path, fill(color))
            }
            else -> c.drawCircle(x, y, h * 0.5f, line(color, w))
        }
    }
}
