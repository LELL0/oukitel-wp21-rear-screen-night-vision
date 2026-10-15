package com.lello.wp21rear.service

import android.os.SystemClock
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.data.MediaRepo
import com.lello.wp21rear.pages.NotificationsOverlay

/**
 * The programmable side key (mtk-kpd KEY_F1). Single, double, triple and long presses map to
 * configurable actions; by default a single press toggles the rear panel.
 */
class ButtonHandler(private val wake: WakeController) {
    private var downAt = 0L
    private var count = 0
    private var longFired = false
    private val ui get() = Rear.ui

    private val longRunnable = Runnable {
        longFired = true
        count = 0
        fire("long")
    }
    private val resolveRunnable = Runnable {
        val kind = when (count) {
            1 -> "single"
            2 -> "double"
            else -> "triple"
        }
        count = 0
        fire(kind)
    }

    /** Linux key event from the daemon (any thread). */
    fun onKey(code: Int, value: Int) {
        if (code != KEY_F1 || !Rear.prefs.buttonEnabled) return
        ui.post {
            if (value == 1) {
                downAt = SystemClock.uptimeMillis()
                longFired = false
                ui.cancel(resolveRunnable)
                ui.postDelayed(LONG_MS, longRunnable)
            } else if (value == 0) {
                ui.cancel(longRunnable)
                if (longFired) return@post
                count++
                // wait for more presses only if a multi-press action is configured
                val needWait = when (count) {
                    1 -> configured("double") || configured("triple")
                    2 -> configured("triple")
                    else -> false
                }
                if (needWait) ui.postDelayed(MULTI_MS, resolveRunnable) else resolveRunnable.run()
            }
        }
    }

    private fun configured(kind: String) = Rear.prefs.buttonAction(kind) != "none"

    private fun fire(kind: String) {
        val action = Rear.prefs.buttonAction(kind)
        RLog.i("button $kind -> $action")
        run(action, Rear.prefs.buttonApp(kind))
    }

    fun run(action: String, app: String?) {
        val d = Rear.daemon
        when (action) {
            "none" -> {}
            "toggle_panel" -> wake.toggle("button")
            "wake_panel" -> wake.wake("button")
            "camera" -> { wake.wake("button"); ui.goTo("camera") }
            "torch" -> Rear.toggles.setTorch(!Rear.toggles.torchOn)
            "play_pause" -> MediaRepo.playPause()
            "next_track" -> MediaRepo.next()
            "prev_track" -> MediaRepo.prev()
            "notifications" -> { wake.wake("button"); ui.showOverlay(NotificationsOverlay(ui)) }
            "next_page" -> {
                wake.wake("button")
                val i = ui.pages.indexOf(ui.current())
                ui.pages.getOrNull((i + 1) % ui.pages.size.coerceAtLeast(1))?.let { ui.goTo(it.id) }
            }
            "dnd" -> Rear.toggles.toggle("dnd")
            "ringer" -> Rear.toggles.toggle("ringer")
            "screenshot" -> d.exec("input keyevent KEYCODE_SYSRQ")
            "main_screen" -> d.exec("input keyevent KEYCODE_POWER")
            "assistant" -> d.exec("input keyevent KEYCODE_WAKEUP; am start -a android.intent.action.VOICE_COMMAND")
            "launch_app" -> {
                if (app.isNullOrEmpty()) { ui.toast("No app chosen"); return }
                // Root start: works from the background and over the lock screen (the app opens after unlock).
                val comp = app.replace("'", "")
                d.exec("input keyevent KEYCODE_WAKEUP; am start --user 0 -n '$comp'") { code, out ->
                    if (code != 0) RLog.w("launch $comp -> $code $out")
                }
            }
            else -> RLog.w("unknown button action $action")
        }
    }

    companion object {
        const val KEY_F1 = 59
        const val LONG_MS = 600L
        const val MULTI_MS = 350L

        /** id to label, in the order shown in the settings app. */
        val ACTIONS = linkedMapOf(
            "none" to "Nothing",
            "toggle_panel" to "Rear screen on/off",
            "wake_panel" to "Rear screen on",
            "camera" to "Rear camera",
            "torch" to "Flashlight",
            "play_pause" to "Play / pause",
            "next_track" to "Next track",
            "prev_track" to "Previous track",
            "notifications" to "Notifications on rear",
            "next_page" to "Next rear page",
            "dnd" to "Do not disturb",
            "ringer" to "Ringer mode",
            "screenshot" to "Screenshot",
            "main_screen" to "Main screen on/off",
            "assistant" to "Voice assistant",
            "launch_app" to "Open an app…",
        )
    }
}
