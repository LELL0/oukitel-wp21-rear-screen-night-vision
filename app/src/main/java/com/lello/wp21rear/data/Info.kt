package com.lello.wp21rear.data

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.SystemClock
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.core.RLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

/** Battery details from the sticky ACTION_BATTERY_CHANGED broadcast and BatteryManager. */
object Battery {
    @Volatile var percent = 0
    @Volatile var charging = false
    @Volatile var plug = ""
    @Volatile var tempC = 0f
    @Volatile var voltage = 0f
    @Volatile var currentMa = 0          // + charging, - discharging
    @Volatile var health = ""
    @Volatile var chargeTimeLeftMs = -1L

    fun update(ctx: Context, i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) {
        i ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        percent = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> ""
        }
        tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "—"
        }
        val bm = ctx.getSystemService(BatteryManager::class.java)
        var ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // some kernels report mA instead of µA
        if (ua != Int.MIN_VALUE && kotlin.math.abs(ua) < 20000) ua *= 1000
        currentMa = if (ua == Int.MIN_VALUE) 0 else ua / 1000
        chargeTimeLeftMs = if (charging) bm.computeChargeTimeRemaining() else -1
    }

    /** Charging power in watts (positive while charging), or 0 if unknown. */
    fun watts(): Float = voltage * currentMa / 1000f
}

/** Daily step count from TYPE_STEP_COUNTER with a midnight baseline kept in preferences. */
class Steps(private val prefs: Prefs) {
    fun today(counter: Float): Int {
        if (counter < 0) return -1
        val sp = prefs.sp
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val savedDay = sp.getInt("steps_day", -1)
        var base = sp.getFloat("steps_base", -1f)
        // new day, first reading, or the counter reset after a reboot
        if (savedDay != day || base < 0 || counter < base) {
            base = if (savedDay == day && counter < base) counter - sp.getInt("steps_last", 0) else counter
            sp.edit().putInt("steps_day", day).putFloat("steps_base", base).apply()
        }
        val n = (counter - base).toInt().coerceAtLeast(0)
        sp.edit().putInt("steps_last", n).apply()
        return n
    }

    val goal: Int get() = prefs.sp.getString("steps_goal", "8000")?.toIntOrNull() ?: 8000
}

/** Current weather from Open-Meteo (no API key). Refreshed at most every 30 minutes. */
object Weather {
    @Volatile var tempC: Float? = null
    @Volatile var code: Int = -1
    @Volatile var updated = 0L
    @Volatile private var fetching = false

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        in 85..86 -> "Snow showers"
        in 95..99 -> "Storm"
        else -> ""
    }

    fun temp(prefs: Prefs): String {
        val t = tempC ?: return "--°"
        return if (prefs.weatherUnitF) "${(t * 9 / 5 + 32).toInt()}°F" else "${t.toInt()}°"
    }

    fun refreshIfStale(ctx: Context, prefs: Prefs, onDone: () -> Unit) {
        if (fetching || SystemClock.elapsedRealtime() - updated < 30 * 60_000L && updated != 0L) return
        var lat = prefs.weatherLat.toDoubleOrNull()
        var lon = prefs.weatherLon.toDoubleOrNull()
        if (lat == null || lon == null) {
            try {
                val lm = ctx.getSystemService(LocationManager::class.java)
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                lat = loc?.latitude
                lon = loc?.longitude
            } catch (_: SecurityException) {
            }
        }
        if (lat == null || lon == null) return
        fetching = true
        thread(name = "weather") {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val cur = JSONObject(body).getJSONObject("current")
                tempC = cur.getDouble("temperature_2m").toFloat()
                code = cur.getInt("weather_code")
                updated = SystemClock.elapsedRealtime()
                onDone()
            } catch (e: Exception) {
                RLog.w("weather: $e")
                updated = SystemClock.elapsedRealtime() - 25 * 60_000L // retry in ~5 min
            } finally {
                fetching = false
            }
        }
    }
}

/** Stopwatch and countdown timer shared by the pages and the service (which fires the alarm). */
object Chrono {
    // stopwatch
    @Volatile var swRunning = false
    @Volatile var swStart = 0L           // elapsedRealtime when (re)started
    @Volatile var swAccum = 0L           // ms accumulated before the last start
    val laps = ArrayList<Long>()

    fun swElapsed(): Long = swAccum + if (swRunning) SystemClock.elapsedRealtime() - swStart else 0

    fun swToggle() {
        if (swRunning) { swAccum = swElapsed(); swRunning = false }
        else { swStart = SystemClock.elapsedRealtime(); swRunning = true }
    }

    fun swLap() = synchronized(laps) { if (swRunning) laps.add(0, swElapsed()) }
    fun swReset() { swRunning = false; swAccum = 0; synchronized(laps) { laps.clear() } }

    // timer
    @Volatile var tmSetMs = 5 * 60_000L
    @Volatile var tmRunning = false
    @Volatile var tmEnd = 0L             // elapsedRealtime when it rings
    @Volatile var tmPausedLeft = -1L
    @Volatile var ringing = false
    var onTimerDone: (() -> Unit)? = null

    fun tmLeft(): Long = when {
        tmRunning -> (tmEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        tmPausedLeft >= 0 -> tmPausedLeft
        else -> tmSetMs
    }

    fun tmToggle() {
        if (tmRunning) { tmPausedLeft = tmLeft(); tmRunning = false }
        else {
            val left = if (tmPausedLeft >= 0) tmPausedLeft else tmSetMs
            tmEnd = SystemClock.elapsedRealtime() + left
            tmPausedLeft = -1
            tmRunning = true
        }
    }

    fun tmReset() { tmRunning = false; tmPausedLeft = -1; ringing = false }

    /** Next alarm clock set on the phone, as a wall-clock time in ms, or null. */
    fun nextAlarm(ctx: Context): Long? = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
}
