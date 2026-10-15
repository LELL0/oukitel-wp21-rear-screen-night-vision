package com.lello.wp21rear.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.core.RLog
import java.io.File
import java.io.FileOutputStream

/** Starts the rear screen after boot and after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (p.enabled && p.autostart) {
            RLog.i("autostart (${intent.action})")
            RearService.start(ctx)
        }
    }
}

/**
 * Debug hooks for adb (protected by android.permission.DUMP, which the shell has):
 *   am broadcast -n com.lello.wp21rear/.service.DebugReceiver -a com.lello.wp21rear.DUMP_FRAME
 *   ... -a com.lello.wp21rear.WAKE | SLEEP | PANEL_OFF
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "com.lello.wp21rear.DUMP_FRAME" -> {
                val b = if (Rear.running) Rear.panel.lastBitmap?.copy(Bitmap.Config.ARGB_8888, false) else null
                if (b == null) {
                    resultData = "no frame"
                    return
                }
                // External storage is unavailable before the first unlock; fall back to device-protected storage.
                val dirs = listOfNotNull(
                    try { ctx.getExternalFilesDir(null) } catch (_: Exception) { null },
                    ctx.createDeviceProtectedStorageContext().filesDir,
                )
                for (d in dirs) {
                    try {
                        val f = File(d, "last_frame.png")
                        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        resultData = f.absolutePath
                        RLog.i("frame dumped to $f")
                        return
                    } catch (e: Exception) {
                        RLog.w("frame dump to $d failed: $e")
                    }
                }
                resultData = "dump failed"
            }
            "com.lello.wp21rear.WAKE" -> Rear.service?.wakePanel()
            "com.lello.wp21rear.SLEEP" -> Rear.service?.sleepPanel()
            "com.lello.wp21rear.PANEL_OFF" -> Rear.service?.emergencyOff()
            "com.lello.wp21rear.GOTO" -> {
                val id = intent.getStringExtra("page") ?: return
                Rear.service?.wakePanel()
                if (Rear.running) Rear.ui.post { Rear.ui.goTo(id) }
            }
            "com.lello.wp21rear.SET" -> {
                // am broadcast ... -a com.lello.wp21rear.SET --es key K (--es value V | --ez bool B | --ei int N)
                val key = intent.getStringExtra("key") ?: return
                // privacy settings are changed only from the app itself
                if (key == Prefs.K_NOTIF_PRIVACY) { resultData = "refused"; return }
                val e = Prefs(ctx).sp.edit()
                when {
                    intent.hasExtra("value") -> e.putString(key, intent.getStringExtra("value"))
                    intent.hasExtra("bool") -> e.putBoolean(key, intent.getBooleanExtra("bool", false))
                    intent.hasExtra("int") -> e.putInt(key, intent.getIntExtra("int", 0))
                    else -> e.remove(key)
                }
                e.commit()
                Prefs.normalize(Prefs(ctx).sp)
                resultData = "ok"
            }
            "com.lello.wp21rear.TOUCH" -> {
                // synthetic touch for testing without the panel: x, y, action (0 down, 1 move, 2 up)
                if (!Rear.running) return
                val x = intent.getIntExtra("x", 180)
                val y = intent.getIntExtra("y", 180)
                Rear.ui.touch(intent.getIntExtra("a", 0), x, y, android.os.SystemClock.uptimeMillis())
            }
        }
    }
}
