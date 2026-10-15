package com.lello.wp21rear.service

import android.app.AlarmManager
import android.content.Context
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.Panel
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.pages.AodPage
import com.lello.wp21rear.pages.WatchPage
import java.util.Calendar

/**
 * Decides when the rear panel is on, off or in always-on (dim) mode. All methods run on the
 * RearUi thread (use [post] from elsewhere).
 *
 * Battery rules: a partial wakelock is held only while the panel is fully on (it expires by
 * itself), the always-on mode wakes the CPU once a minute with an alarm, and sensors used for
 * waking (flip, proximity) are registered only while the panel is off and the feature is enabled.
 */
class WakeController(private val ctx: Context) {
    private val ui get() = Rear.ui
    private val panel get() = Rear.panel
    private val prefs get() = Rear.prefs
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wp21rear:panel").apply { setReferenceCounted(false) }

    var watch: WatchPage? = null
    @Volatile var aod = false
        private set
    /** Wakes from notifications/calls use a shorter timeout. */
    private var shortTimeoutMs = 0L
    private var lastActivity = 0L

    private val timeoutRunnable = Runnable { onTimeout() }
    private val homeRunnable = Runnable { if (panel.state == Panel.State.ON && ui.overlay == null) ui.goTo("watch") }
    private val aodAlarm = AlarmManager.OnAlarmListener { onAodTick() }

    private var faceDownSince = 0L
    private var wasFaceDown = false

    fun post(r: () -> Unit) = ui.post(r)

    val isOn get() = panel.state == Panel.State.ON

    // ------------------------------------------------------------------ wake / sleep

    /** Turn the panel on (or leave always-on mode). [shortMs] > 0 = use a short timeout this time. */
    fun wake(reason: String, shortMs: Long = 0) {
        if (!prefs.enabled) return
        if (!Rear.daemon.isUp) {
            RLog.w("wake ($reason) ignored: daemon not running")
            return
        }
        shortTimeoutMs = shortMs
        if (aod) exitAod()
        if (panel.state != Panel.State.ON) {
            RLog.i("wake: $reason")
            stopOffSensors()
            val first = ui.renderFirstFrame()
            panel.turnOn(first, level())
            if (panel.state != Panel.State.ON) return
            ui.onActivated()
            startOnSensors()
        }
        activity()
    }

    fun sleep(reason: String) {
        if (panel.state == Panel.State.OFF && !aod) return
        RLog.i("sleep: $reason")
        ui.cancel(timeoutRunnable)
        ui.cancel(homeRunnable)
        if (aod) exitAodState()
        ui.onDeactivated()
        stopOnSensors()
        panel.turnOff()
        releaseWakelock()
        ui.closeOverlay(false)
        startOffSensors()
    }

    fun toggle(reason: String) {
        if (panel.state == Panel.State.ON && !aod) sleep(reason) else wake(reason)
    }

    /** Any touch or explicit interaction. */
    fun activity() {
        lastActivity = SystemClock.uptimeMillis()
        if (aod) { exitAod(); return }
        if (panel.state != Panel.State.ON) return
        val t = timeoutMs()
        acquireWakelock(t + 30_000)
        ui.cancel(timeoutRunnable)
        if (t > 0) ui.postDelayed(t, timeoutRunnable)
        ui.cancel(homeRunnable)
        val home = prefs.homeReturnSec * 1000L
        if (home > 0) ui.postDelayed(home, homeRunnable)
    }

    private fun timeoutMs(): Long {
        if (prefs.wakeMode == "always" && !prefs.aodWhenIdle) return 0
        if (prefs.wakeMode == "follow" && isMainScreenOn() && !prefs.aodWhenIdle) return 0
        return if (shortTimeoutMs > 0) shortTimeoutMs else prefs.timeoutSec * 1000L
    }

    private fun onTimeout() {
        if (panel.state != Panel.State.ON) return
        val keep = ui.current()?.keepAwake == true || ui.overlay?.keepAwake == true
        if (keep) {
            ui.postDelayed(5000, timeoutRunnable)
            acquireWakelock(40_000)
            return
        }
        shortTimeoutMs = 0
        if (prefs.aodWhenIdle || prefs.wakeMode == "always") enterAod() else sleep("timeout")
    }

    // ------------------------------------------------------------------ always-on

    private fun enterAod() {
        val w = watch ?: return sleep("timeout (no watch page)")
        RLog.i("always-on display")
        ui.closeOverlay(false)
        ui.aodPage = AodPage(ui, w)
        aod = true
        panel.setLevel(0)
        ui.invalidate()
        releaseWakelock()
        scheduleAodTick()
    }

    private fun exitAodState() {
        aod = false
        ui.aodPage = null
        am.cancel(aodAlarm)
    }

    private fun exitAod() {
        exitAodState()
        panel.setLevel(level())
        ui.invalidate()
        activity()
    }

    private fun scheduleAodTick() {
        val now = System.currentTimeMillis()
        val next = now - now % 60_000 + 60_000 + 50
        try {
            if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC_WAKEUP, next, "wp21rear:aod", aodAlarm, ui.handler)
            else am.set(AlarmManager.RTC_WAKEUP, next, "wp21rear:aod", aodAlarm, ui.handler)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, next, "wp21rear:aod", aodAlarm, ui.handler)
        }
    }

    private fun onAodTick() {
        if (!aod) return
        acquireWakelock(3000)
        panel.setLevel(nightAdjusted(0))
        ui.invalidate()
        scheduleAodTick()
    }

    // ------------------------------------------------------------------ brightness

    fun level(): Int {
        var l = prefs.brightness
        if (prefs.autoBrightness) {
            val lux = Rear.sensors.lux
            if (lux >= 0) l = when {
                lux < 5 -> 0
                lux < 30 -> 1
                lux < 100 -> 2
                lux < 300 -> 3
                lux < 800 -> 4
                lux < 2000 -> 5
                lux < 6000 -> 6
                else -> 7
            }
        }
        return nightAdjusted(l)
    }

    private fun nightAdjusted(l: Int): Int {
        if (!prefs.nightDim || !inNight()) return l
        return minOf(l, prefs.nightLevel)
    }

    fun inNight(): Boolean {
        fun mins(s: String): Int {
            val p = s.split(':')
            return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val s = mins(prefs.nightStart)
        val e = mins(prefs.nightEnd)
        return if (s <= e) now in s until e else now >= s || now < e
    }

    fun refreshLevel() {
        if (panel.state == Panel.State.ON && !aod) panel.setLevel(level())
    }

    // ------------------------------------------------------------------ sensors

    private val lightListener: () -> Unit = { post { refreshLevel() } }
    private val accelListener: () -> Unit = { post { checkFlip() } }

    private fun startOnSensors() {
        if (prefs.autoBrightness) {
            Rear.sensors.lightListeners.add(lightListener)
            Rear.sensors.acquire(Rear.sensors.light, "wake", SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopOnSensors() {
        Rear.sensors.lightListeners.remove(lightListener)
        Rear.sensors.release(Rear.sensors.light, "wake")
    }

    /** Sensors that can wake the panel while it is off. */
    fun startOffSensors() {
        if (panel.state != Panel.State.OFF || !prefs.enabled) return
        if (prefs.flipToWake) {
            wasFaceDown = Rear.sensors.faceDown()
            Rear.sensors.accelListeners.add(accelListener)
            // 5 Hz, batched up to 1 s: enough for "placed face down", cheap on the sensor hub
            Rear.sensors.acquire(Rear.sensors.accel, "flip", 200_000, 1_000_000)
        }
        if (prefs.proximityCheck) Rear.sensors.acquire(Rear.sensors.proximity, "prox", SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stopOffSensors() {
        Rear.sensors.accelListeners.remove(accelListener)
        Rear.sensors.release(Rear.sensors.accel, "flip")
        Rear.sensors.release(Rear.sensors.proximity, "prox")
    }

    fun restartOffSensors() {
        stopOffSensors()
        startOffSensors()
    }

    private fun checkFlip() {
        val down = Rear.sensors.faceDown()
        val now = SystemClock.uptimeMillis()
        if (down && !wasFaceDown) faceDownSince = now
        if (down && faceDownSince > 0 && now - faceDownSince > 600 && panel.state == Panel.State.OFF) {
            faceDownSince = 0
            wake("flip")
        }
        if (!down) faceDownSince = 0
        wasFaceDown = down
    }

    /** False when the phone is probably in a pocket (front covered and not lying face down). */
    fun proximityAllows(): Boolean {
        if (!prefs.proximityCheck) return true
        return !(Rear.sensors.near && !Rear.sensors.faceDown())
    }

    fun isMainScreenOn() = pm.isInteractive

    // ------------------------------------------------------------------ wakelock

    private fun acquireWakelock(ms: Long) {
        try { wl.acquire(ms) } catch (_: Exception) {}
    }

    private fun releaseWakelock() {
        try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
    }

    fun shutdown() {
        ui.cancel(timeoutRunnable)
        ui.cancel(homeRunnable)
        am.cancel(aodAlarm)
        stopOffSensors()
        stopOnSensors()
        releaseWakelock()
    }
}
