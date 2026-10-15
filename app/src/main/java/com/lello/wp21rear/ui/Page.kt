package com.lello.wp21rear.ui

import android.graphics.Canvas

/**
 * One full-screen page of the rear UI. Coordinates are logical panel pixels (0..360, centre 180).
 * All callbacks run on the RearUi thread.
 */
abstract class Page(val ui: RearUi) {
    abstract val id: String

    abstract fun draw(c: Canvas, now: Long)

    /** True while the page needs continuous frames (animation, live camera, smooth seconds). */
    open fun animating(now: Long): Boolean = false

    /** Milliseconds until the content changes by itself (clock tick). Long.MAX_VALUE = only on events. */
    open fun nextChangeIn(now: Long): Long = Long.MAX_VALUE

    /** Pages that react to double-tap delay single taps by the double-tap window. */
    open fun wantsDoubleTap(x: Float, y: Float): Boolean = false
    open fun onTap(x: Float, y: Float): Boolean = false
    open fun onDoubleTap(x: Float, y: Float): Boolean = false
    open fun onLongPress(x: Float, y: Float): Boolean = false

    /** Return true to receive this drag instead of the page navigation. */
    open fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float): Boolean = false
    open fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {}
    open fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float) {}

    open fun onShow() {}
    open fun onHide() {}

    /** While true the panel does not time out (camera, running timer screen, call). */
    open val keepAwake: Boolean get() = false

    fun invalidate() = ui.invalidate()
}

/** A page shown on top of the pager. [from] is where it slides in from. */
abstract class Overlay(ui: RearUi) : Page(ui) {
    enum class From { TOP, BOTTOM, CENTER }
    abstract val from: From
    /** Overlays like the incoming call cannot be swiped away. */
    open val dismissable: Boolean get() = true
    open fun onDismissed() {}
}
