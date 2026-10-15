package com.lello.wp21rear.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import com.lello.wp21rear.core.RLog
import java.util.concurrent.CopyOnWriteArrayList

/** Notifications mirrored from [com.lello.wp21rear.service.NotifListener]. Thread-safe. */
object NotifRepo {
    class Action(val title: String, val intent: PendingIntent)

    class Item(
        val key: String,
        val pkg: String,
        val appName: String,
        val icon: Bitmap?,
        val title: String,
        val text: String,
        val bigText: String,
        val time: Long,
        val actions: List<Action>,
        val clearable: Boolean,
        val publicTitle: String?,
        val isCall: Boolean,
    )

    interface Listener {
        fun onNotifsChanged() {}
        fun onNotifPosted(item: Item) {}
    }

    private val items = LinkedHashMap<String, Item>()
    val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var connected = false

    /** Implemented by the listener service; null while it is not bound. */
    @Volatile var dismisser: ((String) -> Unit)? = null

    private val iconCache = HashMap<String, Bitmap?>()

    fun list(): List<Item> = synchronized(items) { items.values.sortedByDescending { it.time } }

    fun count(): Int = synchronized(items) { items.size }

    fun dismiss(key: String) {
        dismisser?.invoke(key)
        remove(key)
    }

    /** Returns false when refused: actions are not run from the rear while the phone is locked and privacy is on. */
    fun runAction(ctx: Context, item: Item, a: Action, privacy: Boolean): Boolean {
        val locked = ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (locked && privacy) return false
        try {
            a.intent.send()
        } catch (e: Exception) {
            RLog.w("notification action failed: $e")
        }
        return true
    }

    fun replaceAll(ctx: Context, sbns: Array<StatusBarNotification>?) {
        synchronized(items) {
            items.clear()
            sbns?.forEach { toItem(ctx, it)?.let { i -> items[i.key] = i } }
        }
        for (l in listeners) l.onNotifsChanged()
    }

    fun post(ctx: Context, sbn: StatusBarNotification) {
        val item = toItem(ctx, sbn) ?: return
        val isNew: Boolean
        synchronized(items) {
            val old = items[item.key]
            // updates of an existing notification (progress, media) are not "new"
            isNew = old == null || (old.text != item.text && sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
            items[item.key] = item
        }
        for (l in listeners) l.onNotifsChanged()
        if (isNew && !sbn.isOngoing) for (l in listeners) l.onNotifPosted(item)
    }

    fun remove(key: String) {
        val removed = synchronized(items) { items.remove(key) } != null
        if (removed) for (l in listeners) l.onNotifsChanged()
    }

    private fun toItem(ctx: Context, sbn: StatusBarNotification): Item? {
        val n = sbn.notification
        if (sbn.packageName == ctx.packageName) return null
        // group summaries duplicate their children
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") ?: text
        if (title.isEmpty() && text.isEmpty()) return null
        // media playback notifications are shown on the music page instead
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val actions = n.actions?.filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }
            ?.take(3)?.map { Action(it.title?.toString() ?: "", it.actionIntent) } ?: emptyList()
        val publicTitle = n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return Item(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appName(ctx, sbn.packageName),
            icon = appIcon(ctx, sbn.packageName),
            title = title,
            text = text,
            bigText = big,
            time = if (n.`when` > 0) n.`when` else sbn.postTime,
            actions = actions,
            clearable = sbn.isClearable,
            publicTitle = publicTitle,
            isCall = n.category == Notification.CATEGORY_CALL,
        )
    }

    private val nameCache = HashMap<String, String>()

    fun appName(ctx: Context, pkg: String): String = synchronized(nameCache) {
        nameCache.getOrPut(pkg) {
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }

    fun appIcon(ctx: Context, pkg: String): Bitmap? = synchronized(iconCache) {
        iconCache.getOrPut(pkg) {
            try {
                drawableToBitmap(ctx.packageManager.getApplicationIcon(pkg), 72)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }
}
