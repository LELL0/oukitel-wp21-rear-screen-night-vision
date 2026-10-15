package com.lello.wp21rear.ui

import android.os.Handler
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns raw rear touches (logical 360x360 coordinates) into taps, double-taps, long-presses and
 * drags. Runs on the UI thread of [RearUi]; every call must come from that thread.
 */
class Gestures(private val handler: Handler, private val sink: Sink) {
    interface Sink {
        fun wantsDoubleTap(x: Float, y: Float): Boolean
        fun onDown(x: Float, y: Float)
        fun onTap(x: Float, y: Float)
        fun onDoubleTap(x: Float, y: Float)
        fun onLongPress(x: Float, y: Float)
        fun onDragStart(x0: Float, y0: Float, dx: Float, dy: Float)
        fun onDrag(x: Float, y: Float, dx: Float, dy: Float)
        fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float)
        fun onUp()
    }

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var vx = 0f
    private var vy = 0f
    private var dragging = false
    private var longFired = false
    private var isDown = false

    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var pendingTapT = 0L
    private var tapPending = false

    private val longPress = Runnable {
        if (isDown && !dragging) {
            longFired = true
            tapPending = false
            sink.onLongPress(downX, downY)
        }
    }
    private val tapTimeout = Runnable {
        if (tapPending) {
            tapPending = false
            sink.onTap(pendingTapX, pendingTapY)
        }
    }

    fun down(x: Float, y: Float, t: Long) {
        isDown = true
        dragging = false
        longFired = false
        downX = x; downY = y; downT = t
        lastX = x; lastY = y; lastT = t
        vx = 0f; vy = 0f
        handler.removeCallbacks(longPress)
        handler.postDelayed(longPress, LONG_PRESS_MS)
        sink.onDown(x, y)
    }

    fun move(x: Float, y: Float, t: Long) {
        if (!isDown) {
            down(x, y, t)
            return
        }
        val dt = (t - lastT).coerceAtLeast(1)
        // light smoothing of the velocity (px per second)
        vx = 0.6f * vx + 0.4f * (x - lastX) * 1000f / dt
        vy = 0.6f * vy + 0.4f * (y - lastY) * 1000f / dt
        lastX = x; lastY = y; lastT = t
        val dx = x - downX
        val dy = y - downY
        if (!dragging && !longFired && hypot(dx, dy) > SLOP) {
            dragging = true
            handler.removeCallbacks(longPress)
            sink.onDragStart(downX, downY, dx, dy)
        }
        if (dragging) sink.onDrag(x, y, dx, dy)
    }

    fun up(x: Float, y: Float, t: Long) {
        if (!isDown) return
        isDown = false
        handler.removeCallbacks(longPress)
        if (dragging) {
            if (t - lastT > 80) { vx = 0f; vy = 0f } // finger rested before lifting: no fling
            sink.onDragEnd(lastX, lastY, lastX - downX, lastY - downY, vx, vy)
        } else if (!longFired) {
            if (tapPending && t - pendingTapT < DOUBLE_TAP_MS && hypot(x - pendingTapX, y - pendingTapY) < DOUBLE_TAP_SLOP) {
                tapPending = false
                handler.removeCallbacks(tapTimeout)
                sink.onDoubleTap(x, y)
            } else if (sink.wantsDoubleTap(downX, downY)) {
                tapPending = true
                pendingTapX = downX; pendingTapY = downY; pendingTapT = t
                handler.removeCallbacks(tapTimeout)
                handler.postDelayed(tapTimeout, DOUBLE_TAP_MS)
            } else {
                sink.onTap(downX, downY)
            }
        }
        sink.onUp()
    }

    fun cancel() {
        isDown = false
        dragging = false
        tapPending = false
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(tapTimeout)
    }

    companion object {
        const val SLOP = 14f
        const val LONG_PRESS_MS = 550L
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_SLOP = 60f

        fun isHorizontal(dx: Float, dy: Float) = abs(dx) > abs(dy)
    }
}
