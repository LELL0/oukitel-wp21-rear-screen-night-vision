package com.lello.wp21rear.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.Panel
import com.lello.wp21rear.daemon.Proto

/**
 * Live mirror of the exact frames pushed to the rear panel. Touching it drives the rear UI as if
 * the rear screen itself was touched.
 */
class MirrorView(ctx: Context) : View(ctx) {
    @Volatile private var frame: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3C4043.toInt() }
    private val clip = Path()
    private val rect = RectF()

    private val listener: (Bitmap) -> Unit = { b ->
        frame = b.copy(Bitmap.Config.ARGB_8888, false)
        postInvalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Rear.running) {
            Rear.panel.frameListeners.add(listener)
            frame = Rear.panel.lastBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    override fun onDetachedFromWindow() {
        if (Rear.running) Rear.panel.frameListeners.remove(listener)
        super.onDetachedFromWindow()
    }

    fun reattach() {
        onDetachedFromWindow()
        onAttachedToWindow()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = minOf(w, context.dp(300))
        setMeasuredDimension(w, s)
    }

    override fun onDraw(c: Canvas) {
        val s = height.toFloat()
        val l = (width - s) / 2
        rect.set(l, 0f, l + s, s)
        clip.reset()
        clip.addCircle(rect.centerX(), rect.centerY(), s / 2, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawColor(Color.BLACK)
        val on = Rear.running && Rear.panel.state == Panel.State.ON
        val f = frame
        if (on && f != null) c.drawBitmap(f, null, rect, paint)
        c.restore()
        ring.strokeWidth = context.dp(3).toFloat()
        c.drawCircle(rect.centerX(), rect.centerY(), s / 2 - ring.strokeWidth / 2, ring)
        if (!on) {
            text.textSize = context.dp(16).toFloat()
            c.drawText(if (Rear.running) "panel off" else "service stopped", rect.centerX(), rect.centerY(), text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!Rear.running || Rear.panel.state != Panel.State.ON) return false
        val s = height.toFloat()
        val l = (width - s) / 2
        val x = (e.x - l) / s * Proto.W
        val y = e.y / s * Proto.H
        val a = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> Proto.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> Proto.TOUCH_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Proto.TOUCH_UP
            else -> return true
        }
        Rear.ui.touchLogical(a, x, y, SystemClock.uptimeMillis())
        return true
    }
}
