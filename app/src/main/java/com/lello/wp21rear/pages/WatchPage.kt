package com.lello.wp21rear.pages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.data.Weather
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Overlay
import com.lello.wp21rear.ui.Page
import com.lello.wp21rear.ui.RearUi
import java.util.Calendar
import kotlin.concurrent.thread

class WatchPage(ui: RearUi) : Page(ui) {
    override val id = "watch"

    private val cal = Calendar.getInstance()
    private val bgCache = HashMap<String, Bitmap?>()

    val face: Face get() = Face.ALL[Rear.prefs.face.coerceIn(0, Face.ALL.size - 1)]

    fun colors(i: Int): Pair<Int, Int> {
        val f = Face.ALL[i]
        return Pair(Rear.prefs.faceColor(i, f.defColor), Rear.prefs.faceAccent(i, f.defAccent))
    }

    override fun draw(c: Canvas, now: Long) = drawFace(c, Rear.prefs.face.coerceIn(0, Face.ALL.size - 1), false)

    fun drawFace(c: Canvas, i: Int, aod: Boolean) {
        val f = Face.ALL[i]
        val ms = System.currentTimeMillis()
        cal.timeZone = java.util.TimeZone.getDefault()
        cal.timeInMillis = ms
        if (!aod) {
            val bg = background(i)
            if (bg != null) {
                Draw.bitmapCover(c, bg, 0f, 0f, Draw.S, Draw.S, 110)
            }
        }
        val (color, accent) = colors(i)
        f.draw(c, cal, ms, color, accent, aod)
    }

    private fun background(i: Int): Bitmap? {
        val uri = Rear.prefs.faceBgUri(i) ?: return null
        synchronized(bgCache) {
            if (bgCache.containsKey(uri)) return bgCache[uri]
            bgCache[uri] = null
        }
        thread(name = "face-bg") {
            val b = try {
                val src = ImageDecoder.createSource(Rear.app.contentResolver, Uri.parse(uri))
                ImageDecoder.decodeBitmap(src) { d, info, _ ->
                    val s = 360f / minOf(info.size.width, info.size.height)
                    if (s < 1f) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                RLog.w("face background: $e")
                null
            }
            synchronized(bgCache) { bgCache[uri] = b }
            invalidate()
        }
        return null
    }

    fun clearBackgrounds() = synchronized(bgCache) { bgCache.clear() }

    override fun animating(now: Long) = face.smooth

    override fun nextChangeIn(now: Long): Long {
        val ms = System.currentTimeMillis()
        return if (face.showsSeconds) 1000 - ms % 1000 + 5 else 60_000 - ms % 60_000 + 5
    }

    override fun onShow() {
        if (face is ComplicationsFace) Weather.refreshIfStale(Rear.app, Rear.prefs) { invalidate() }
        if (face is ComplicationsFace) Rear.sensors.acquire(Rear.sensors.stepCounter, "watch")
    }

    override fun onHide() {
        Rear.sensors.release(Rear.sensors.stepCounter, "watch")
    }

    override fun wantsDoubleTap(x: Float, y: Float) = true

    override fun onLongPress(x: Float, y: Float): Boolean {
        ui.showOverlay(FacePicker(ui, this))
        return true
    }
}

/** Swipe left/right through the faces, tap to choose. */
class FacePicker(ui: RearUi, private val watch: WatchPage) : Overlay(ui) {
    override val id = "facepicker"
    override val from = From.CENTER
    private var sel = Rear.prefs.face
    private var drag = 0f

    override fun draw(c: Canvas, now: Long) {
        c.drawColor(0xFF000000.toInt())
        val n = Face.ALL.size
        for (k in -1..1) {
            val i = ((sel + k) % n + n) % n
            c.save()
            val x = k * 250f + drag
            c.translate(x, 0f)
            c.scale(0.62f, 0.62f, Draw.C, Draw.C)
            Draw.ring(c, Draw.C, Draw.C, 176f, 4f, if (k == 0) Draw.ACCENT else Draw.FAINT)
            watch.drawFace(c, i, false)
            c.restore()
        }
        Draw.textC(c, Face.ALL[sel].name, Draw.C, 50f, 22f, Draw.WHITE, Draw.MED)
        Draw.textC(c, "tap to choose", Draw.C, 310f, 15f, Draw.DIM)
    }

    override fun nextChangeIn(now: Long) = 1000L
    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float) = true
    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) { drag = dx; invalidate() }
    override fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float) {
        val n = Face.ALL.size
        if (dx < -60) sel = (sel + 1) % n else if (dx > 60) sel = (sel - 1 + n) % n
        drag = 0f
        invalidate()
    }

    override fun onTap(x: Float, y: Float): Boolean {
        Rear.prefs.face = sel
        ui.closeOverlay(false)
        return true
    }
}

/** Always-on display: the current face, dim, updated once a minute. */
class AodPage(ui: RearUi, private val watch: WatchPage) : Page(ui) {
    override val id = "aod"
    override fun draw(c: Canvas, now: Long) = watch.drawFace(c, Rear.prefs.face.coerceIn(0, Face.ALL.size - 1), true)
    override fun nextChangeIn(now: Long): Long {
        val ms = System.currentTimeMillis()
        return 60_000 - ms % 60_000 + 20
    }
}
