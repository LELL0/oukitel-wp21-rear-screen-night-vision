package com.lello.wp21rear.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.lello.wp21rear.R
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.DaemonClient
import com.lello.wp21rear.core.Panel
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.data.Battery
import com.lello.wp21rear.data.Chrono
import com.lello.wp21rear.data.MediaRepo
import com.lello.wp21rear.data.NotifRepo
import com.lello.wp21rear.data.SensorHub
import com.lello.wp21rear.data.Steps
import com.lello.wp21rear.data.Toggles
import com.lello.wp21rear.pages.CameraPage
import com.lello.wp21rear.pages.CompassPage
import com.lello.wp21rear.pages.IdlePage
import com.lello.wp21rear.pages.LevelPage
import com.lello.wp21rear.pages.MenuOverlay
import com.lello.wp21rear.pages.MusicPage
import com.lello.wp21rear.pages.NotifPopup
import com.lello.wp21rear.pages.NotificationsOverlay
import com.lello.wp21rear.pages.PhotosPage
import com.lello.wp21rear.pages.QuickTogglesOverlay
import com.lello.wp21rear.pages.BatteryPage
import com.lello.wp21rear.pages.StepsPage
import com.lello.wp21rear.pages.StopwatchPage
import com.lello.wp21rear.pages.TimerPage
import com.lello.wp21rear.pages.ToolsPage
import com.lello.wp21rear.pages.WatchPage
import com.lello.wp21rear.ui.Overlay
import com.lello.wp21rear.ui.Page
import com.lello.wp21rear.ui.RearUi
import com.lello.wp21rear.app.MainActivity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground service that runs the rear screen: root daemon, panel, UI, wake logic, side key,
 * calls, notifications and music.
 */
class RearService : Service(), DaemonClient.Listener, RearUi.Host, NotifRepo.Listener, MediaRepo.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wake: WakeController
    private lateinit var button: ButtonHandler
    private lateinit var calls: CallMonitor
    private var watchPage: WatchPage? = null
    private var cameraFgs = false
    private val cameraWaiters = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var ringtone: Ringtone? = null
    private val timerAlarm = AlarmManager.OnAlarmListener { onTimerDone() }

    // double-tap on the dark panel
    private var offTapAt = 0L
    private var offDownAt = 0L
    private var lastDarkTouchLog = -100_000L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> Rear.ui.post {
                    if (Rear.prefs.wakeMode == "follow") wake.wake("main screen on")
                }
                Intent.ACTION_SCREEN_OFF -> Rear.ui.post {
                    if (Rear.prefs.wakeMode == "follow") wake.sleep("main screen off")
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    Battery.update(c, i)
                    Rear.ui.invalidate()
                }
                Intent.ACTION_SHUTDOWN -> {
                    // Reboot/power-off: switch the panel off synchronously before the system dies.
                    RLog.i("shutdown: panel off")
                    Rear.panel.turnOff()
                    Rear.daemon.stop(false)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext
        Rear.app = app
        Rear.prefs = Prefs(app)
        Rear.daemon = DaemonClient(app)
        Rear.panel = Panel(Rear.daemon, Rear.prefs)
        Rear.ui = RearUi(Rear.panel, Rear.prefs)
        Rear.sensors = SensorHub(app)
        Rear.toggles = Toggles(app, Rear.daemon)
        Rear.steps = Steps(Rear.prefs)
        Rear.ui.host = this
        wake = WakeController(app)
        button = ButtonHandler(wake)
        calls = CallMonitor(app, wake)
        Rear.service = this

        startForegroundCompat(false)
        Battery.update(app)
        buildPages()

        Rear.daemon.listener = this
        Rear.daemon.start()

        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
        })
        calls.start()
        NotifRepo.listeners.add(this)
        MediaRepo.listeners.add(this)
        Rear.prefs.sp.registerOnSharedPreferenceChangeListener(this)
        Chrono.onTimerDone = { Rear.ui.post { onTimerDone() } }
        RLog.i("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAMERA_FGS -> {
                // Started by the root daemon: the system treats a root caller as exempt from the
                // background "while-in-use" restriction, so the camera type is allowed now.
                startForegroundCompat(true)
            }
            ACTION_WAKE -> Rear.ui.post { wake.wake("intent") }
            ACTION_SLEEP -> Rear.ui.post { wake.sleep("intent") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RLog.i("service stopping")
        Rear.service = null
        Rear.prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        NotifRepo.listeners.remove(this)
        MediaRepo.listeners.remove(this)
        calls.stop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        val latch = java.util.concurrent.CountDownLatch(1)
        Rear.ui.post {
            wake.sleep("service stopped")
            wake.shutdown()
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        Thread { Rear.daemon.stop(true) }.start()
        Rear.toggles.release()
        Rear.sensors.quit()
        Rear.ui.quit()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground notification

    private fun startForegroundCompat(camera: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rear screen", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Rear screen active")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (camera) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        try {
            startForeground(NOTIF_ID, n, type)
            if (camera) {
                cameraFgs = true
                RLog.i("foreground type now includes camera")
            }
        } catch (e: Exception) {
            RLog.w("startForeground(camera=$camera): $e")
            if (camera) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        if (camera) {
            val ok = cameraFgs
            for (w in cameraWaiters) w(ok)
            cameraWaiters.clear()
        }
    }

    /** Makes sure the service may use the camera (and mic) from the background, then calls [cb]. */
    fun ensureCameraForeground(cb: (Boolean) -> Unit) {
        if (cameraFgs) { cb(true); return }
        cameraWaiters.add(cb)
        Rear.daemon.exec("am start-foreground-service --user 0 -a $ACTION_CAMERA_FGS -n $packageName/${RearService::class.java.name}") { code, out ->
            if (code != 0) {
                RLog.w("camera fgs via root failed: $code $out")
                for (w in cameraWaiters) w(false)
                cameraWaiters.clear()
            }
        }
    }

    // ------------------------------------------------------------------ pages

    fun buildPages() {
        val ui = Rear.ui
        val list = ArrayList<Page>()
        for (raw in Rear.prefs.pages.split(',')) {
            val id = raw.trim()
            if (id.isEmpty() || id.startsWith("-")) continue
            val p: Page = when (id) {
                "watch" -> (watchPage ?: WatchPage(ui).also { watchPage = it })
                "music" -> MusicPage(ui)
                "camera" -> CameraPage(ui)
                "tools" -> ToolsPage(ui)
                "compass" -> CompassPage(ui)
                "level" -> LevelPage(ui)
                "stopwatch" -> StopwatchPage(ui)
                "timer" -> TimerPage(ui)
                "battery" -> BatteryPage(ui)
                "steps" -> StepsPage(ui)
                "photos" -> PhotosPage(ui)
                "idle" -> IdlePage(ui)
                else -> continue
            }
            list.add(p)
        }
        if (list.none { it.id == "watch" }) {
            // the watch face is the home page and the always-on face; it cannot be removed
            list.add(0, watchPage ?: WatchPage(ui).also { watchPage = it })
        }
        wake.watch = watchPage
        ui.post { ui.setPages(list) }
    }

    // ------------------------------------------------------------------ RearUi.Host

    override fun requestSleep(reason: String) = wake.sleep(reason)
    override fun userActivity() = wake.activity()
    override fun overlayQuickToggles(): Overlay = QuickTogglesOverlay(Rear.ui)
    override fun overlayNotifications(): Overlay = NotificationsOverlay(Rear.ui)

    override fun pageMenu(p: Page) {
        val ui = Rear.ui
        ui.showOverlay(MenuOverlay(ui, "Page options", listOf(
            "Watch face" to { ui.goTo("watch"); Unit },
            "Hide this page" to {
                if (p.id != "watch") {
                    Rear.prefs.pages = Rear.prefs.pages.split(',').joinToString(",") { if (it == p.id) "-${p.id}" else it }
                }
            },
            "Turn panel off" to { wake.sleep("menu") },
        )))
    }

    // ------------------------------------------------------------------ daemon events

    override fun onDaemonState(up: Boolean, info: String) {
        Rear.ui.post {
            if (up) {
                Rear.daemon.setGrab(Rear.prefs.grabTouch)
                wake.startOffSensors()
                if (Rear.prefs.wakeMode == "always" || (Rear.prefs.wakeMode == "follow" && wake.isMainScreenOn())) wake.wake("daemon up")
            } else {
                // The daemon switches the panel off when it exits.
                Rear.ui.onDeactivated()
                Rear.panel.markOff()
            }
        }
    }

    override fun onTouch(action: Int, x: Int, y: Int, timeMs: Long) {
        for (l in Rear.touchListeners) l(action, x, y)
        Rear.touchCapture?.let { it(action, x, y); return }
        if (Rear.panel.state == Panel.State.ON) {
            Rear.ui.touch(action, x, y, timeMs)
            return
        }
        // Panel dark: only a double-tap wakes it.
        if (action == com.lello.wp21rear.daemon.Proto.TOUCH_DOWN && timeMs - lastDarkTouchLog > 10_000) {
            lastDarkTouchLog = timeMs
            RLog.i("touch while panel off at $x,$y")
        }
        if (!Rear.prefs.doubleTapWake) return
        when (action) {
            com.lello.wp21rear.daemon.Proto.TOUCH_DOWN -> offDownAt = timeMs
            com.lello.wp21rear.daemon.Proto.TOUCH_UP -> {
                if (timeMs - offDownAt < 300) {
                    if (timeMs - offTapAt < 400) {
                        offTapAt = 0
                        Rear.ui.post { if (wake.proximityAllows()) wake.wake("double tap") }
                    } else {
                        offTapAt = timeMs
                    }
                }
            }
        }
    }

    override fun onKey(code: Int, value: Int, timeMs: Long) = button.onKey(code, value)

    // ------------------------------------------------------------------ notifications & media

    override fun onNotifPosted(item: NotifRepo.Item) {
        RLog.i("notification posted: ${item.pkg}")
        val p = Rear.prefs
        if (!p.notifWake || item.pkg in p.notifMutedApps || item.isCall) return
        Rear.ui.post {
            val ui = Rear.ui
            if (!wake.isOn || wake.aod) {
                if (!wake.proximityAllows()) {
                    RLog.i("notification ${item.pkg}: not waking (front covered)")
                    return@post
                }
                wake.wake("notification ${item.pkg}", 8000)
            } else if (ui.overlay?.id == "call" || ui.current()?.id == "camera") {
                return@post
            }
            ui.showOverlay(NotifPopup(ui, item), false)
            wake.activity()
        }
    }

    override fun onNotifsChanged() = Rear.ui.invalidate()

    override fun onPlaybackStarted() {
        if (!Rear.prefs.musicAutoShow) return
        Rear.ui.post {
            if (wake.isOn && Rear.ui.overlay == null && Rear.ui.current()?.id != "camera") Rear.ui.goTo("music")
        }
    }

    override fun onMediaChanged() = Rear.ui.invalidate()

    // ------------------------------------------------------------------ timer alarm

    fun scheduleTimer() {
        val am = getSystemService(AlarmManager::class.java)
        am.cancel(timerAlarm)
        if (!Chrono.tmRunning) return
        val at = Chrono.tmEnd
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "wp21rear:timer", timerAlarm, Rear.ui.handler)
                return
            }
        } catch (_: SecurityException) {}
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "wp21rear:timer", timerAlarm, Rear.ui.handler)
    }

    private fun onTimerDone() {
        if (!Chrono.tmRunning || SystemClock.elapsedRealtime() < Chrono.tmEnd - 500) return
        Chrono.tmRunning = false
        Chrono.ringing = true
        wake.wake("timer")
        Rear.ui.goTo("timer")
        val v = getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true
                play()
            }
        } catch (e: Exception) {
            RLog.w("timer sound: $e")
        }
        // stop by itself after a minute
        Rear.ui.postDelayed(60_000, Runnable { stopTimerAlarm() })
    }

    fun stopTimerAlarm() {
        Chrono.ringing = false
        Chrono.tmReset()
        getSystemService(Vibrator::class.java).cancel()
        ringtone?.stop()
        ringtone = null
        Rear.ui.invalidate()
    }

    // ------------------------------------------------------------------ settings

    override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
        val ui = Rear.ui
        when (key) {
            Prefs.K_PAGES -> buildPages()
            Prefs.K_BRIGHTNESS, Prefs.K_AUTO_BRIGHTNESS, Prefs.K_NIGHT_DIM, Prefs.K_NIGHT_LEVEL,
            Prefs.K_NIGHT_START, Prefs.K_NIGHT_END -> ui.post { wake.refreshLevel() }
            Prefs.K_GRAB_TOUCH -> Rear.daemon.setGrab(Rear.prefs.grabTouch)
            Prefs.K_FLIP_WAKE, Prefs.K_PROX_CHECK -> ui.post { wake.restartOffSensors() }
            Prefs.K_ENABLED -> if (!Rear.prefs.enabled) ui.post { wake.sleep("disabled") }
            Prefs.K_WAKE_MODE -> ui.post { if (Rear.prefs.wakeMode == "always") wake.wake("always on") else wake.activity() }
            else -> {
                if (key != null && key.startsWith("face_")) watchPage?.clearBackgrounds()
            }
        }
        ui.invalidate()
    }

    // ------------------------------------------------------------------ public controls (settings app)

    fun wakePanel() = Rear.ui.post { wake.wake("app") }
    fun sleepPanel() = Rear.ui.post { wake.sleep("app") }

    /** Emergency switch: panel off now, rear screen disabled until re-enabled in the app. */
    fun emergencyOff() {
        Rear.prefs.sp.edit().putBoolean(Prefs.K_ENABLED, false).commit()
        Rear.ui.post { wake.sleep("emergency switch") }
        stopSelf()
    }

    fun runButtonAction(action: String, app: String?) = Rear.ui.post { button.run(action, app) }

    companion object {
        const val CHANNEL = "rear"
        const val NOTIF_ID = 1
        const val ACTION_CAMERA_FGS = "com.lello.wp21rear.CAMERA_FGS"
        const val ACTION_WAKE = "com.lello.wp21rear.WAKE"
        const val ACTION_SLEEP = "com.lello.wp21rear.SLEEP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RearService::class.java))
        }
    }
}
