package com.lello.wp21rear.pages

import android.graphics.Canvas
import android.os.SystemClock
import com.lello.wp21rear.Rear
import com.lello.wp21rear.data.MediaRepo
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Icons
import com.lello.wp21rear.ui.Page
import com.lello.wp21rear.ui.RearUi
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Active media session: art, title, artist, prev/play/next, a seek bar (drag it) and volume by
 * sliding a finger around the edge of the screen.
 */
class MusicPage(ui: RearUi) : Page(ui), MediaRepo.Listener {
    override val id = "music"

    private var seeking = false
    private var seekFrac = 0f
    private var volumeDrag = false
    private var volStartAngle = 0f
    private var volStart = 0
    private var volShownUntil = 0L

    override fun onShow() { MediaRepo.listeners.add(this) }
    override fun onHide() { MediaRepo.listeners.remove(this) }
    override fun onMediaChanged() = invalidate()

    override fun draw(c: Canvas, now: Long) {
        if (!MediaRepo.hasSession()) {
            Icons.draw(c, "music", Draw.C, 150f, 64f, Draw.FAINT)
            Draw.textC(c, "No music playing", Draw.C, 222f, 22f, Draw.DIM)
            Draw.textC(c, "Start a player on the phone", Draw.C, 252f, 15f, Draw.FAINT)
            return
        }
        MediaRepo.art?.let { Draw.bitmapCover(c, it, 0f, 0f, Draw.S, Draw.S, 70) }
        Draw.textC(c, MediaRepo.appName, Draw.C, 62f, 14f, Draw.DIM, Draw.MED, 160f)
        Draw.textC(c, MediaRepo.title.ifEmpty { "Unknown" }, Draw.C, 112f, 26f, Draw.WHITE, Draw.MED, 260f)
        Draw.textC(c, MediaRepo.artist, Draw.C, 145f, 19f, Draw.DIM, Draw.REG, 240f)

        // controls
        Icons.draw(c, "prev", 92f, 212f, 34f, Draw.WHITE)
        Draw.circle(c, Draw.C, 212f, 40f, Draw.WHITE)
        Icons.draw(c, if (MediaRepo.isPlaying()) "pause" else "play", Draw.C + if (MediaRepo.isPlaying()) 0f else 3f, 212f, 36f, 0xFF000000.toInt())
        Icons.draw(c, "next", 268f, 212f, 34f, Draw.WHITE)

        // seek bar
        val dur = MediaRepo.duration
        val pos = if (seeking) (seekFrac * dur).toLong() else MediaRepo.position()
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Draw.line(c, SEEK_L, SEEK_Y, SEEK_R, SEEK_Y, 5f, Draw.FAINT)
        if (dur > 0) {
            Draw.line(c, SEEK_L, SEEK_Y, SEEK_L + (SEEK_R - SEEK_L) * frac, SEEK_Y, 5f, Draw.ACCENT)
            Draw.circle(c, SEEK_L + (SEEK_R - SEEK_L) * frac, SEEK_Y, if (seeking) 9f else 6f, Draw.WHITE)
            Draw.textL(c, fmt(pos), SEEK_L, SEEK_Y + 22f, 13f, Draw.DIM)
            Draw.textC(c, fmt(dur), SEEK_R - 16f, SEEK_Y + 22f, 13f, Draw.DIM)
        }

        // volume ring while adjusting
        if (volumeDrag || volShownUntil > now) {
            val (v, max) = MediaRepo.volume(Rear.app)
            Draw.ring(c, Draw.C, Draw.C, 172f, 8f, Draw.FAINT)
            Draw.arc(c, Draw.C, Draw.C, 172f, 0f, 360f * v / max.coerceAtLeast(1), 8f, Draw.ACCENT)
            Icons.draw(c, "volume", Draw.C, 30f, 22f, Draw.WHITE)
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun animating(now: Long) = seeking || volumeDrag || volShownUntil > now

    override fun nextChangeIn(now: Long) = if (MediaRepo.isPlaying()) 1000L else Long.MAX_VALUE

    override fun onTap(x: Float, y: Float): Boolean {
        if (!MediaRepo.hasSession()) return false
        if (y in 170f..255f) {
            when {
                x < 135f -> MediaRepo.prev()
                x > 225f -> MediaRepo.next()
                else -> MediaRepo.playPause()
            }
            ui.postDelayed(300, Runnable { invalidate() })
            return true
        }
        return false
    }

    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float): Boolean {
        if (!MediaRepo.hasSession()) return false
        val r = hypot(x0 - Draw.C, y0 - Draw.C)
        if (y0 in SEEK_Y - 25f..SEEK_Y + 25f && x0 in SEEK_L - 20f..SEEK_R + 20f && MediaRepo.duration > 0 &&
            kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            seeking = true
            return true
        }
        if (r > 135f) {
            // tangential movement along the rim = volume
            val radial = ((x0 - Draw.C) * dx + (y0 - Draw.C) * dy) / r
            val tangential = kotlin.math.sqrt((dx * dx + dy * dy - radial * radial).coerceAtLeast(0f))
            if (tangential > kotlin.math.abs(radial)) {
                volumeDrag = true
                volStartAngle = angle(x0, y0)
                volStart = MediaRepo.volume(Rear.app).first
                return true
            }
        }
        return false
    }

    private fun angle(x: Float, y: Float) = Math.toDegrees(atan2((x - Draw.C).toDouble(), -(y - Draw.C).toDouble())).toFloat()

    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {
        if (seeking) {
            seekFrac = ((x - SEEK_L) / (SEEK_R - SEEK_L)).coerceIn(0f, 1f)
        } else if (volumeDrag) {
            var d = angle(x, y) - volStartAngle
            if (d > 180) d -= 360f
            if (d < -180) d += 360f
            val max = MediaRepo.volume(Rear.app).second
            MediaRepo.setVolume(Rear.app, volStart + (d / 360f * max * 1.5f).toInt())
        }
        invalidate()
    }

    override fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float) {
        if (seeking) {
            MediaRepo.seek((seekFrac * MediaRepo.duration).toLong())
            seeking = false
        }
        if (volumeDrag) {
            volumeDrag = false
            volShownUntil = SystemClock.uptimeMillis() + 1200
        }
        invalidate()
    }

    companion object {
        const val SEEK_L = 92f
        const val SEEK_R = 268f
        const val SEEK_Y = 282f
    }
}
