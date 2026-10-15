package com.lello.wp21rear.pages

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.lello.wp21rear.Rear
import com.lello.wp21rear.data.NotifRepo
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Icons
import com.lello.wp21rear.ui.Overlay
import com.lello.wp21rear.ui.RearUi
import kotlin.math.abs
import kotlin.math.hypot

fun locked(): Boolean = Rear.app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

// ======================================================================== quick toggles

class QuickTogglesOverlay(ui: RearUi) : Overlay(ui) {
    override val id = "toggles"
    override val from = From.TOP
    private val ids = listOf("flashlight", "wifi", "bluetooth", "dnd", "data", "rotation", "ringer")
    private var brightDrag = false

    private fun pos(i: Int): Pair<Float, Float> = if (i < 4) Pair(81f + i * 66f, 128f) else Pair(114f + (i - 4) * 66f, 196f)

    override fun onShow() {
        Rear.toggles.onChange = { invalidate() }
    }

    override fun draw(c: Canvas, now: Long) {
        c.drawColor(0xF0101113.toInt())
        val time = android.text.format.DateFormat.getTimeFormat(Rear.app).format(java.util.Date())
        Draw.textC(c, time, Draw.C, 52f, 22f, Draw.WHITE, Draw.MED)
        for ((i, id) in ids.withIndex()) {
            val (x, y) = pos(i)
            val on = Rear.toggles.isOn(id)
            Draw.circle(c, x, y, 27f, if (on) Draw.ACCENT else Draw.CARD)
            val icon = if (id == "ringer") Rear.toggles.ringerIcon() else Rear.toggles.all.first { it.id == id }.icon
            Icons.draw(c, icon, x, y, 26f, if (on) 0xFF000000.toInt() else Draw.WHITE)
        }
        // rear brightness 0..7
        val lvl = Rear.prefs.brightness
        Draw.line(c, B_L, B_Y, B_R, B_Y, 10f, Draw.CARD)
        Draw.line(c, B_L, B_Y, B_L + (B_R - B_L) * lvl / 7f, B_Y, 10f, Draw.AMBER)
        Draw.circle(c, B_L + (B_R - B_L) * lvl / 7f, B_Y, 12f, Draw.WHITE)
        Draw.textC(c, "rear brightness $lvl", Draw.C, B_Y + 24f, 12f, Draw.DIM)
        Icons.draw(c, "power", Draw.C, 318f, 24f, Draw.RED)
    }

    override fun nextChangeIn(now: Long) = 2000L

    override fun onTap(x: Float, y: Float): Boolean {
        for ((i, id) in ids.withIndex()) {
            val (px, py) = pos(i)
            if (hypot(x - px, y - py) < 32f) {
                Rear.toggles.toggle(id)
                ui.postDelayed(400, Runnable { invalidate() })
                ui.postDelayed(1500, Runnable { invalidate() })
                return true
            }
        }
        if (y in B_Y - 22f..B_Y + 22f) { setBright(x); return true }
        if (hypot(x - Draw.C, y - 318f) < 26f) {
            ui.closeOverlay(false)
            ui.host.requestSleep("toggles power button")
            return true
        }
        return false
    }

    private fun setBright(x: Float) {
        val l = (((x - B_L) / (B_R - B_L)) * 7f + 0.5f).toInt().coerceIn(0, 7)
        Rear.prefs.sp.edit().putInt(com.lello.wp21rear.core.Prefs.K_BRIGHTNESS, l).apply()
        Rear.panel.setLevel(l)
        invalidate()
    }

    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float): Boolean {
        brightDrag = y0 in B_Y - 25f..B_Y + 25f && abs(dx) > abs(dy)
        return brightDrag
    }

    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) { if (brightDrag) setBright(x) }

    companion object {
        const val B_L = 90f
        const val B_R = 270f
        const val B_Y = 258f
    }
}

// ======================================================================== notifications list

class NotificationsOverlay(ui: RearUi) : Overlay(ui), NotifRepo.Listener {
    override val id = "notifications"
    override val from = From.BOTTOM

    private var items: List<NotifRepo.Item> = emptyList()
    private var expanded: String? = null
    private var scroll = 0f
    private var scrollStart = 0f
    private var dragMode = 0          // 1 scroll, 2 swipe card
    private var swipeKey: String? = null
    private var swipeDx = 0f
    private val tp = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Draw.DIM }
    private val rects = ArrayList<Triple<NotifRepo.Item, Float, Float>>()   // item, top, bottom (content coords)
    private val actionRects = ArrayList<Pair<Pair<NotifRepo.Item, NotifRepo.Action>, android.graphics.RectF>>()

    override fun onShow() {
        NotifRepo.listeners.add(this)
        items = NotifRepo.list()
    }

    override fun onHide() { NotifRepo.listeners.remove(this) }

    override fun onNotifsChanged() {
        ui.post { items = NotifRepo.list(); invalidate() }
    }

    private fun privateMode() = Rear.prefs.notifPrivacy && locked()

    override fun draw(c: Canvas, now: Long) {
        c.drawColor(0xF0101113.toInt())
        rects.clear()
        actionRects.clear()
        if (items.isEmpty()) {
            Icons.draw(c, "notif", Draw.C, 160f, 50f, Draw.FAINT)
            Draw.textC(c, if (NotifRepo.connected) "No notifications" else "Notification access off", Draw.C, 214f, 18f, Draw.DIM)
            return
        }
        val priv = privateMode()
        var y = 70f - scroll
        Draw.textC(c, "${items.size} notification" + if (items.size > 1) "s" else "", Draw.C, y - 30f, 14f, Draw.DIM)
        for (it in items) {
            val exp = expanded == it.key && !priv
            val top = y
            val off = if (swipeKey == it.key) swipeDx else 0f
            val textLayout = if (exp) StaticLayout.Builder.obtain(it.bigText, 0, it.bigText.length, tp, 232)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(8).build() else null
            val h = if (exp) 64f + textLayout!!.height + (if (it.actions.isNotEmpty()) 44f else 0f) + 10f else 74f
            if (y + h > -20f && y < Draw.S + 20f) {
                c.save()
                c.translate(off, 0f)
                Draw.roundRect(c, 50f, y, 310f, y + h, 18f, Draw.CARD)
                it.icon?.let { b -> drawIcon(c, b, 74f, y + 22f) }
                Draw.textL(c, it.appName, 92f, y + 22f, 12f, Draw.DIM, Draw.REG, 150f)
                Draw.textL(c, ago(it.time), 250f, y + 22f, 11f, Draw.FAINT)
                if (priv) {
                    Draw.textL(c, it.publicTitle ?: "New notification", 64f, y + 50f, 16f, Draw.WHITE, Draw.MED, 232f)
                } else {
                    Draw.textL(c, it.title, 64f, y + 46f, 16f, Draw.WHITE, Draw.MED, 232f)
                    if (exp) {
                        c.save(); c.translate(64f, y + 60f); textLayout!!.draw(c); c.restore()
                        if (it.actions.isNotEmpty()) {
                            val ay = y + 64f + textLayout.height + 20f
                            val w = 232f / it.actions.size
                            for ((k, a) in it.actions.withIndex()) {
                                val l = 64f + k * w
                                Draw.roundRect(c, l + 2f, ay - 16f, l + w - 2f, ay + 16f, 16f, 0xFF2D2F33.toInt())
                                Draw.textC(c, a.title, l + w / 2, ay, 13f, Draw.ACCENT, Draw.MED, w - 10f)
                                actionRects.add(Pair(Pair(it, a), android.graphics.RectF(l, ay - 16f + scroll, l + w, ay + 16f + scroll)))
                            }
                        }
                    } else {
                        Draw.textL(c, it.text, 64f, y + 64f, 13f, Draw.DIM, Draw.REG, 232f)
                    }
                }
                c.restore()
            }
            rects.add(Triple(it, top + scroll, top + h + scroll))
            y += h + 8f
        }
    }

    private fun drawIcon(c: Canvas, b: Bitmap, x: Float, y: Float) {
        val r = android.graphics.RectF(x - 10f, y - 10f, x + 10f, y + 10f)
        c.drawBitmap(b, null, r, Draw.bmpPaint)
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60000
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 1440 -> "${m / 60}h"
            else -> "${m / 1440}d"
        }
    }

    private fun contentHeight() = rects.lastOrNull()?.third ?: 0f

    private fun itemAt(y: Float): NotifRepo.Item? = rects.firstOrNull { y + scroll in it.second..it.third }?.first

    override fun onTap(x: Float, y: Float): Boolean {
        for ((pair, r) in actionRects) {
            if (r.contains(x, y + scroll)) {
                NotifRepo.runAction(pair.first, pair.second)
                ui.toast(pair.second.title)
                return true
            }
        }
        val it = itemAt(y) ?: return false
        if (privateMode()) { ui.toast("Unlock the phone to read"); return true }
        expanded = if (expanded == it.key) null else it.key
        invalidate()
        return true
    }

    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float): Boolean {
        if (abs(dx) > abs(dy)) {
            val it = itemAt(y0) ?: return false
            if (!it.clearable) return false
            dragMode = 2
            swipeKey = it.key
            return true
        }
        // scroll, except a downward drag at the top, which closes the list
        if (dy > 0 && scroll <= 0f) return false
        dragMode = 1
        scrollStart = scroll
        return true
    }

    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {
        if (dragMode == 1) {
            scroll = (scrollStart - dy).coerceIn(0f, (contentHeight() - 250f).coerceAtLeast(0f))
        } else if (dragMode == 2) {
            swipeDx = dx
        }
        invalidate()
    }

    override fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float) {
        if (dragMode == 2) {
            val key = swipeKey
            if (key != null && (abs(dx) > 110f || abs(vx) > 700f)) NotifRepo.dismiss(key)
            swipeKey = null
            swipeDx = 0f
        }
        dragMode = 0
        invalidate()
    }
}

// ======================================================================== new-notification popup

class NotifPopup(ui: RearUi, private val item: NotifRepo.Item) : Overlay(ui) {
    override val id = "popup"
    override val from = From.CENTER
    private val shownAt = SystemClock.uptimeMillis()
    private val close = Runnable { if (ui.overlay === this) ui.closeOverlay(false) }

    override fun onShow() {
        ui.postDelayed(6000, close)
    }

    override fun onHide() = ui.cancel(close)

    override fun draw(c: Canvas, now: Long) {
        c.drawColor(0xFF000000.toInt())
        val priv = Rear.prefs.notifPrivacy && locked()
        item.icon?.let { Draw.bitmapCircle(c, it, Draw.C, 92f, 30f) }
        Draw.textC(c, item.appName, Draw.C, 145f, 15f, Draw.DIM, Draw.MED, 220f)
        if (priv) {
            Draw.textC(c, item.publicTitle ?: "New notification", Draw.C, 190f, 22f, Draw.WHITE, Draw.MED, 260f)
        } else {
            Draw.textC(c, item.title, Draw.C, 185f, 22f, Draw.WHITE, Draw.MED, 270f)
            val tp = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; color = Draw.DIM }
            val l = StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, 240)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save(); c.translate(60f, 208f); l.draw(c); c.restore()
        }
        val t = ((now - shownAt) / 6000f).coerceIn(0f, 1f)
        Draw.arc(c, Draw.C, Draw.C, 174f, 0f, 360f * (1 - t), 3f, Draw.ACCENT)
    }

    override fun nextChangeIn(now: Long) = 500L

    override fun onTap(x: Float, y: Float): Boolean {
        ui.showOverlay(NotificationsOverlay(ui))
        return true
    }

    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float) = true
    override fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float) {
        if (abs(dx) > 80f && item.clearable) NotifRepo.dismiss(item.key)
        ui.closeOverlay(false)
    }
}

// ======================================================================== calls

interface CallControl {
    fun answer()
    fun reject()
    fun hangUp()
    fun toggleMute(): Boolean
    fun toggleSpeaker(): Boolean
    fun isMuted(): Boolean
    fun isSpeaker(): Boolean
}

class CallOverlay(ui: RearUi, private val ctl: CallControl) : Overlay(ui) {
    override val id = "call"
    override val from = From.CENTER
    override val dismissable = false
    override val keepAwake = true

    @Volatile var name: String = ""
    @Volatile var number: String = ""
    @Volatile var photo: Bitmap? = null
    @Volatile var ringing = true
    @Volatile var activeSince = 0L
    private var slide = 0f
    private var sliding = false

    override fun draw(c: Canvas, now: Long) {
        c.drawColor(0xFF000000.toInt())
        val p = photo
        if (p != null) Draw.bitmapCircle(c, p, Draw.C, 88f, 42f)
        else {
            Draw.circle(c, Draw.C, 88f, 42f, Draw.CARD)
            Draw.textC(c, (name.ifEmpty { "?" }).take(1).uppercase(), Draw.C, 88f, 36f, Draw.WHITE, Draw.MED)
        }
        Draw.textC(c, name.ifEmpty { number.ifEmpty { "Unknown" } }, Draw.C, 158f, 26f, Draw.WHITE, Draw.MED, 270f)
        if (name.isNotEmpty() && number.isNotEmpty()) Draw.textC(c, number, Draw.C, 188f, 16f, Draw.DIM, Draw.REG, 240f)
        if (ringing) {
            Draw.textC(c, "Incoming call", Draw.C, 52f, 14f, Draw.DIM)
            // slider: drag right to answer, left to reject
            Draw.roundRect(c, 60f, 246f, 300f, 306f, 30f, Draw.CARD)
            Icons.draw(c, "phone_end", 88f, 276f, 26f, Draw.RED)
            Icons.draw(c, "phone", 272f, 276f, 26f, Draw.GREEN)
            val kx = (Draw.C + slide).coerceIn(90f, 270f)
            val col = when {
                slide > 40 -> Draw.GREEN
                slide < -40 -> Draw.RED
                else -> Draw.WHITE
            }
            Draw.circle(c, kx, 276f, 26f, col)
            Icons.draw(c, "phone", kx, 276f, 22f, 0xFF000000.toInt())
            if (!sliding) Draw.textC(c, "‹ reject      answer ›", Draw.C, 322f, 12f, Draw.FAINT)
        } else {
            val s = if (activeSince > 0) (SystemClock.elapsedRealtime() - activeSince) / 1000 else 0
            Draw.textC(c, "%d:%02d".format(s / 60, s % 60), Draw.C, 52f, 16f, Draw.GREEN, Draw.MED)
            button(c, 102f, 250f, if (ctl.isMuted()) "mic_off" else "mic", ctl.isMuted())
            button(c, 258f, 250f, "speaker", ctl.isSpeaker())
            Draw.circle(c, Draw.C, 296f, 30f, Draw.RED)
            Icons.draw(c, "phone_end", Draw.C, 296f, 28f, Draw.WHITE)
        }
    }

    private fun button(c: Canvas, x: Float, y: Float, icon: String, on: Boolean) {
        Draw.circle(c, x, y, 26f, if (on) Draw.WHITE else Draw.CARD)
        Icons.draw(c, icon, x, y, 24f, if (on) 0xFF000000.toInt() else Draw.WHITE)
    }

    override fun nextChangeIn(now: Long) = 1000L
    override fun animating(now: Long) = sliding

    override fun onTap(x: Float, y: Float): Boolean {
        if (ringing) return true
        when {
            hypot(x - 102f, y - 250f) < 32f -> ctl.toggleMute()
            hypot(x - 258f, y - 250f) < 32f -> ctl.toggleSpeaker()
            hypot(x - Draw.C, y - 296f) < 36f -> ctl.hangUp()
        }
        invalidate()
        return true
    }

    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float): Boolean {
        sliding = ringing && y0 > 220f
        return true // nothing behind the call screen reacts to drags
    }

    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {
        if (sliding) { slide = dx; invalidate() }
    }

    override fun onDragEnd(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float) {
        if (sliding) {
            if (slide > 80f) ctl.answer() else if (slide < -80f) ctl.reject()
        }
        sliding = false
        slide = 0f
        invalidate()
    }
}

// ======================================================================== generic menu

class MenuOverlay(ui: RearUi, private val title: String, private val items: List<Pair<String, () -> Unit>>) : Overlay(ui) {
    override val id = "menu"
    override val from = From.CENTER

    override fun draw(c: Canvas, now: Long) {
        c.drawColor(0xF0101113.toInt())
        Draw.textC(c, title, Draw.C, 70f, 16f, Draw.DIM, Draw.MED)
        val top = Draw.C - items.size * 26f
        for ((i, it) in items.withIndex()) {
            val y = top + i * 52f + 26f
            Draw.roundRect(c, 70f, y - 22f, 290f, y + 22f, 22f, Draw.CARD)
            Draw.textC(c, it.first, Draw.C, y, 18f, Draw.WHITE, Draw.REG, 200f)
        }
        Icons.draw(c, "close", Draw.C, 316f, 22f, Draw.DIM)
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val top = Draw.C - items.size * 26f
        val i = ((y - top) / 52f).toInt()
        ui.closeOverlay(false)
        if (y >= top && i in items.indices) items[i].second()
        return true
    }
}
