package com.lello.wp21rear.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.lello.wp21rear.core.Panel
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.daemon.Proto
import kotlin.math.abs

/**
 * The rear-screen UI engine: a retained-mode pager of [Page]s plus one [Overlay], rendered on its
 * own thread into a 360x360 bitmap and pushed to the [Panel] only when something changed (or at
 * up to maxFps while animating).
 */
class RearUi(private val panel: Panel, val prefs: Prefs) : Gestures.Sink {
    interface Host {
        /** Panel power requests, handled by the wake controller. */
        fun requestSleep(reason: String)
        fun userActivity()
        fun overlayQuickToggles(): Overlay
        fun overlayNotifications(): Overlay
        /** Long-press on a page that has no long-press action of its own. */
        fun pageMenu(p: Page)
    }

    lateinit var host: Host

    private val thread = HandlerThread("rear-ui").apply { start() }
    val handler = Handler(thread.looper)
    val gestures = Gestures(handler, this)

    // pages
    var pages: List<Page> = emptyList()
        private set
    var index = 0
        private set
    private var pagerOffset = 0f          // px, + = content moved right
    private var pagerAnimFrom = 0f
    private var pagerAnimStart = 0L
    private var pagerAnimTarget = 0f
    private var pagerAnimating = false
    private var pagerDragging = false
    private var dotsUntil = 0L

    // overlay
    var overlay: Overlay? = null
        private set
    private var overlayPos = 1f           // 0 = fully shown, 1 = fully hidden
    private var overlayAnimFrom = 0f
    private var overlayAnimTo = 0f
    private var overlayAnimStart = 0L
    private var overlayAnimating = false
    private var overlayDragging = false
    private var pendingOverlay: Overlay? = null  // overlay being pulled in by a drag

    // drag routing
    private enum class DragTarget { NONE, PAGE, OVERLAY, PAGER, PULL_TOP, PULL_BOTTOM, OVERLAY_DISMISS }
    private var dragTarget = DragTarget.NONE

    // toast
    private var toastText: String? = null
    private var toastUntil = 0L

    // rendering
    @Volatile var active = false   // panel is on and frames should flow
        private set
    private val pool = Array(3) { Bitmap.createBitmap(Proto.W, Proto.H, Bitmap.Config.ARGB_8888) }
    private val physPool = Array(3) { Bitmap.createBitmap(Proto.W, Proto.H, Bitmap.Config.ARGB_8888) }
    private var poolIdx = 0
    private val canvas = Canvas()
    private val physCanvas = Canvas()
    private val clip = Path().apply { addCircle(Draw.C, Draw.C, Draw.C, Path.Direction.CW) }
    private var lastRender = 0L
    private var renderScheduledAt = Long.MAX_VALUE
    private val renderRunnable = Runnable { renderScheduledAt = Long.MAX_VALUE; render() }

    /** Optional full-screen renderer replacing the pager (always-on display). */
    @Volatile var aodPage: Page? = null

    fun post(r: () -> Unit) = handler.post(r)
    fun postDelayed(ms: Long, r: Runnable) = handler.postDelayed(r, ms)
    fun cancel(r: Runnable) = handler.removeCallbacks(r)

    // ------------------------------------------------------------------ pages

    fun setPages(list: List<Page>) {
        val curId = pages.getOrNull(index)?.id
        pages.getOrNull(index)?.let { if (active) it.onHide() }
        pages = list
        index = list.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        pagerOffset = 0f
        if (active) pages.getOrNull(index)?.onShow()
        invalidate()
    }

    fun current(): Page? = pages.getOrNull(index)

    fun goTo(id: String): Boolean {
        val i = pages.indexOfFirst { it.id == id }
        if (i < 0) return false
        pagerOffset = 0f
        pagerAnimating = false
        switchTo(i)
        return true
    }

    private fun switchTo(i: Int) {
        if (pages.isEmpty()) return
        val n = pages.size
        val ni = ((i % n) + n) % n
        if (ni == index) return
        if (active) pages.getOrNull(index)?.onHide()
        index = ni
        if (active) pages[index].onShow()
        dotsUntil = SystemClock.uptimeMillis() + 1500
        invalidate()
    }

    // ------------------------------------------------------------------ overlays

    fun showOverlay(o: Overlay, animate: Boolean = true) {
        val old = overlay
        if (old != null && old !== o) {
            old.onHide()
            old.onDismissed()
        }
        overlay = o
        if (old !== o) o.onShow()
        if (animate && o.from != Overlay.From.CENTER) animateOverlay(overlayPos.takeIf { old === o } ?: 1f, 0f)
        else { overlayPos = 0f; overlayAnimating = false }
        invalidate()
    }

    fun closeOverlay(animate: Boolean = true) {
        val o = overlay ?: return
        if (animate && o.from != Overlay.From.CENTER) {
            animateOverlay(overlayPos, 1f)
        } else {
            finishOverlayClose()
        }
        invalidate()
    }

    private fun finishOverlayClose() {
        val o = overlay ?: return
        overlay = null
        overlayPos = 1f
        overlayAnimating = false
        o.onHide()
        o.onDismissed()
    }

    private fun animateOverlay(from: Float, to: Float) {
        overlayAnimFrom = from
        overlayAnimTo = to
        overlayAnimStart = SystemClock.uptimeMillis()
        overlayAnimating = true
        overlayPos = from
    }

    // ------------------------------------------------------------------ toast

    fun toast(s: String, ms: Long = 1500) {
        toastText = s
        toastUntil = SystemClock.uptimeMillis() + ms
        invalidate()
        postDelayed(ms + 20, Runnable { invalidate() })
    }

    // ------------------------------------------------------------------ activation

    /** Called by the wake controller after the panel turned on (on this thread). */
    fun onActivated() {
        active = true
        current()?.onShow()
        overlay?.onShow()
        invalidate()
    }

    fun onDeactivated() {
        if (!active) return
        active = false
        gestures.cancel()
        current()?.onHide()
        overlay?.onHide()
        handler.removeCallbacks(renderRunnable)
        renderScheduledAt = Long.MAX_VALUE
    }

    /** Renders one frame synchronously for the power-on sequence (panel not yet "active"). */
    fun renderFirstFrame(): Bitmap {
        val (_, phys) = drawFrame(SystemClock.uptimeMillis())
        return phys
    }

    // ------------------------------------------------------------------ touch input (from the daemon)

    fun touch(action: Int, rawX: Int, rawY: Int, t: Long) {
        val (x, y) = mapTouch(rawX.toFloat(), rawY.toFloat())
        handler.post {
            when (action) {
                Proto.TOUCH_DOWN -> gestures.down(x, y, t)
                Proto.TOUCH_MOVE -> gestures.move(x, y, t)
                Proto.TOUCH_UP -> gestures.up(x, y, t)
            }
        }
    }

    /** Touch already in logical coordinates (taps on the live mirror in the app). */
    fun touchLogical(action: Int, x: Float, y: Float, t: Long) {
        handler.post {
            when (action) {
                Proto.TOUCH_DOWN -> gestures.down(x, y, t)
                Proto.TOUCH_MOVE -> gestures.move(x, y, t)
                Proto.TOUCH_UP -> gestures.up(x, y, t)
            }
        }
    }

    /** Maps raw controller coordinates to logical UI coordinates with the touch calibration. */
    fun mapTouch(rx: Float, ry: Float): Pair<Float, Float> {
        var x = rx
        var y = ry
        if (prefs.touchMirror) x = Draw.S - x
        when (((prefs.touchRotation % 360) + 360) % 360) {
            90 -> { val t = x; x = Draw.S - y; y = t }
            180 -> { x = Draw.S - x; y = Draw.S - y }
            270 -> { val t = x; x = y; y = Draw.S - t }
        }
        return Pair(x, y)
    }

    // Gestures.Sink -------------------------------------------------------

    override fun wantsDoubleTap(x: Float, y: Float): Boolean {
        val o = overlay
        if (o != null) return o.wantsDoubleTap(x, y)
        return current()?.wantsDoubleTap(x, y) ?: false
    }

    override fun onDown(x: Float, y: Float) {
        host.userActivity()
        if (pagerAnimating) { // catch the page mid-flight
            pagerAnimating = false
        }
    }

    override fun onTap(x: Float, y: Float) {
        val o = overlay
        if (o != null) {
            if (!o.onTap(x, y)) invalidate()
            return
        }
        current()?.onTap(x, y)
    }

    override fun onDoubleTap(x: Float, y: Float) {
        val o = overlay
        if (o != null) { o.onDoubleTap(x, y); return }
        val p = current()
        if (p?.onDoubleTap(x, y) != true && prefs.doubleTapSleep) host.requestSleep("double tap")
    }

    override fun onLongPress(x: Float, y: Float) {
        val o = overlay
        if (o != null) { o.onLongPress(x, y); return }
        val p = current() ?: return
        if (!p.onLongPress(x, y)) host.pageMenu(p)
    }

    override fun onDragStart(x0: Float, y0: Float, dx: Float, dy: Float) {
        val o = overlay
        val horizontal = Gestures.isHorizontal(dx, dy)
        dragTarget = when {
            o != null && o.claimDrag(x0, y0, dx, dy) -> DragTarget.OVERLAY
            o != null && o.dismissable && !horizontal &&
                ((o.from == Overlay.From.TOP && dy < 0) || (o.from == Overlay.From.BOTTOM && dy > 0)) -> DragTarget.OVERLAY_DISMISS
            o != null -> DragTarget.NONE
            current()?.claimDrag(x0, y0, dx, dy) == true -> DragTarget.PAGE
            horizontal -> DragTarget.PAGER
            dy > 0 -> DragTarget.PULL_TOP
            else -> DragTarget.PULL_BOTTOM
        }
        when (dragTarget) {
            DragTarget.PAGER -> { pagerDragging = true; pagerAnimating = false }
            DragTarget.PULL_TOP -> startPull(host.overlayQuickToggles())
            DragTarget.PULL_BOTTOM -> startPull(host.overlayNotifications())
            DragTarget.OVERLAY_DISMISS -> { overlayDragging = true; overlayAnimating = false }
            else -> {}
        }
        onDrag(x0 + dx, y0 + dy, dx, dy)
    }

    private fun startPull(o: Overlay) {
        pendingOverlay = o
        overlay = o
        overlayPos = 1f
        overlayDragging = true
        overlayAnimating = false
        o.onShow()
    }

    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {
        when (dragTarget) {
            DragTarget.PAGE -> current()?.onDrag(x, y, dx, dy)
            DragTarget.OVERLAY -> overlay?.onDrag(x, y, dx, dy)
            DragTarget.PAGER -> { pagerOffset = dx; invalidate() }
            DragTarget.PULL_TOP -> { overlayPos = (1f - dy / Draw.S).coerceIn(0f, 1f); invalidate() }
            DragTarget.PULL_BOTTOM -> { overlayPos = (1f + dy / Draw.S).coerceIn(0f, 1f); invalidate() }
            DragTarget.OVERLAY_DISMISS -> {
                overlayPos = (abs(dy) / Draw.S).coerceIn(0f, 1f); invalidate()
            }
            DragTarget.NONE -> {}
        }
    }

    override fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float) {
        when (dragTarget) {
            DragTarget.PAGE -> current()?.onDragEnd(x, y, dx, dy, vx, vy)
            DragTarget.OVERLAY -> overlay?.onDragEnd(x, y, dx, dy, vx, vy)
            DragTarget.PAGER -> {
                pagerDragging = false
                val go = abs(dx) > 90f || abs(vx) > 500f && abs(dx) > 30f
                if (go && pages.size > 1) {
                    val dir = if (dx < 0) 1 else -1
                    // the new page continues from where the finger left it
                    pagerOffset += dir * Draw.S
                    switchTo(index + dir)
                }
                pagerAnimFrom = pagerOffset
                pagerAnimTarget = 0f
                pagerAnimStart = SystemClock.uptimeMillis()
                pagerAnimating = true
            }
            DragTarget.PULL_TOP, DragTarget.PULL_BOTTOM -> {
                overlayDragging = false
                val v = if (dragTarget == DragTarget.PULL_TOP) vy else -vy
                if (overlayPos < 0.6f || v > 500f) {
                    animateOverlay(overlayPos, 0f)
                } else {
                    animateOverlay(overlayPos, 1f)
                }
                pendingOverlay = null
            }
            DragTarget.OVERLAY_DISMISS -> {
                overlayDragging = false
                val o = overlay
                val v = if (o?.from == Overlay.From.TOP) -vy else vy
                if (overlayPos > 0.35f || v > 500f) animateOverlay(overlayPos, 1f) else animateOverlay(overlayPos, 0f)
            }
            DragTarget.NONE -> {}
        }
        dragTarget = DragTarget.NONE
        invalidate()
    }

    override fun onUp() {}

    // ------------------------------------------------------------------ rendering

    /** Request a redraw soon (coalesced; limited to maxFps). Safe from any thread. */
    fun invalidate() {
        if (!active) return
        val now = SystemClock.uptimeMillis()
        val at = maxOf(now, lastRender + 1000L / prefs.maxFps)
        if (at >= renderScheduledAt) return
        renderScheduledAt = at
        handler.removeCallbacks(renderRunnable)
        handler.postAtTime(renderRunnable, at)
    }

    private fun scheduleIn(ms: Long) {
        if (!active || ms == Long.MAX_VALUE) return
        val at = SystemClock.uptimeMillis() + ms.coerceAtLeast(1)
        if (at >= renderScheduledAt) return
        renderScheduledAt = at
        handler.removeCallbacks(renderRunnable)
        handler.postAtTime(renderRunnable, at)
    }

    private fun render() {
        if (!active) return
        val now = SystemClock.uptimeMillis()
        lastRender = now
        val (logical, phys) = drawFrame(now)
        panel.push(phys, logical)

        // decide when to draw next
        val anyAnim = pagerAnimating || overlayAnimating || pagerDragging || overlayDragging ||
            (aodPage?.animating(now) ?: (current()?.animating(now) == true || overlay?.animating(now) == true)) ||
            (dotsUntil > now) || (toastUntil > now)
        if (anyAnim) {
            scheduleIn(1000L / prefs.maxFps)
        } else {
            val a = aodPage
            val next = if (a != null) a.nextChangeIn(now) else minOf(
                current()?.nextChangeIn(now) ?: Long.MAX_VALUE,
                overlay?.nextChangeIn(now) ?: Long.MAX_VALUE,
            )
            scheduleIn(next)
        }
    }

    private fun drawFrame(now: Long): Pair<Bitmap, Bitmap> {
        poolIdx = (poolIdx + 1) % pool.size
        val bmp = pool[poolIdx]
        val c = canvas
        c.setBitmap(bmp)
        c.drawColor(Color.BLACK)
        val save = c.save()
        c.clipPath(clip)
        try {
            val a = aodPage
            if (a != null) a.draw(c, now) else drawPagerAndOverlay(c, now)
        } catch (t: Throwable) {
            RLog.e("draw failed", t)
            Draw.textC(c, "draw error", Draw.C, Draw.C, 22f, Draw.RED)
        }
        c.restoreToCount(save)
        c.setBitmap(null)
        return Pair(bmp, toPhysical(bmp))
    }

    private fun drawPagerAndOverlay(c: Canvas, now: Long) {
        // pager animation step
        if (pagerAnimating) {
            val t = ((now - pagerAnimStart) / 260f).coerceIn(0f, 1f)
            pagerOffset = pagerAnimFrom + (pagerAnimTarget - pagerAnimFrom) * Draw.easeOut(t)
            if (t >= 1f) { pagerAnimating = false; pagerOffset = pagerAnimTarget }
        }
        if (overlayAnimating) {
            val t = ((now - overlayAnimStart) / 240f).coerceIn(0f, 1f)
            overlayPos = overlayAnimFrom + (overlayAnimTo - overlayAnimFrom) * Draw.easeOut(t)
            if (t >= 1f) {
                overlayAnimating = false
                overlayPos = overlayAnimTo
                if (overlayPos >= 1f) finishOverlayClose()
            }
        }

        val n = pages.size
        val o = overlay
        val overlayCovers = o != null && overlayPos <= 0.001f && !overlayAnimating && !overlayDragging
        if (n > 0 && !overlayCovers) {
            val cur = pages[index]
            c.save()
            c.translate(pagerOffset, 0f)
            cur.draw(c, now)
            c.restore()
            if (abs(pagerOffset) > 0.5f && n > 1) {
                val ni = if (pagerOffset < 0) (index + 1) % n else (index - 1 + n) % n
                c.save()
                c.translate(pagerOffset + if (pagerOffset < 0) Draw.S else -Draw.S, 0f)
                pages[ni].draw(c, now)
                c.restore()
            }
            if (dotsUntil > now || pagerDragging || pagerAnimating) {
                Draw.dots(c, n, index - pagerOffset / Draw.S)
            }
        }
        if (o != null) {
            c.save()
            when (o.from) {
                Overlay.From.TOP -> c.translate(0f, -overlayPos * Draw.S)
                Overlay.From.BOTTOM -> c.translate(0f, overlayPos * Draw.S)
                Overlay.From.CENTER -> {}
            }
            if (!overlayCovers) {
                // dim the page behind
                Draw.fill.color = Draw.withAlpha(Color.BLACK, (255 * (1 - overlayPos)).toInt())
                c.drawRect(0f, 0f, Draw.S, Draw.S, Draw.fill)
            }
            o.draw(c, now)
            c.restore()
        }
        val tt = toastText
        if (tt != null && toastUntil > now) {
            Draw.text.textSize = 20f
            val w = Draw.text.measureText(tt).coerceAtMost(240f) + 32f
            Draw.roundRect(c, Draw.C - w / 2, 280f, Draw.C + w / 2, 318f, 19f, 0xE0303134.toInt())
            Draw.textC(c, tt, Draw.C, 299f, 20f, Draw.WHITE, Draw.REG, 240f)
        }
    }

    private val matrix = Matrix()

    /** Applies the display calibration (rotation/mirror) for the panel; the mirror keeps the logical image. */
    private fun toPhysical(logical: Bitmap): Bitmap {
        val rot = ((prefs.rotation % 360) + 360) % 360
        val mir = prefs.mirror
        if (rot == 0 && !mir) return logical
        val out = physPool[poolIdx]
        matrix.reset()
        if (mir) { matrix.postScale(-1f, 1f, Draw.C, Draw.C) }
        if (rot != 0) matrix.postRotate(rot.toFloat(), Draw.C, Draw.C)
        physCanvas.setBitmap(out)
        physCanvas.drawColor(Color.BLACK)
        physCanvas.drawBitmap(logical, matrix, Draw.bmpPaint)
        physCanvas.setBitmap(null)
        return out
    }

    fun quit() {
        thread.quitSafely()
    }
}
