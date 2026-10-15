package com.lello.wp21rear.core

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * All settings, backed by the default SharedPreferences so the preference screens and the service
 * share them. Keys here must match res/xml/prefs.xml.
 */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    private fun int(key: String, def: Int) = try {
        sp.getInt(key, def)
    } catch (_: ClassCastException) {
        sp.getString(key, null)?.toIntOrNull() ?: def
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)

    // --- general
    val enabled get() = bool(K_ENABLED, true)
    val autostart get() = bool(K_AUTOSTART, true)

    // --- panel / calibration
    val brightness get() = int(K_BRIGHTNESS, 4).coerceIn(0, 7)
    val autoBrightness get() = bool(K_AUTO_BRIGHTNESS, false)
    /** Rotation of the drawn image in degrees (0/90/180/270). */
    val rotation get() = str(K_ROTATION, "0").toIntOrNull() ?: 0
    val mirror get() = bool(K_MIRROR, false)
    val swapBytes get() = bool(K_SWAP_BYTES, false)
    /** Touch transform, independent from the image transform. */
    val touchRotation get() = str(K_TOUCH_ROTATION, "0").toIntOrNull() ?: 0
    val touchMirror get() = bool(K_TOUCH_MIRROR, false)
    val grabTouch get() = bool(K_GRAB_TOUCH, true)
    val maxFps get() = int(K_MAX_FPS, 15).coerceIn(1, 30)

    // --- wake behaviour
    /** manual | follow | always */
    val wakeMode get() = str(K_WAKE_MODE, "manual")
    val timeoutSec get() = str(K_TIMEOUT, "15").toIntOrNull() ?: 15
    val flipToWake get() = bool(K_FLIP_WAKE, true)
    val proximityCheck get() = bool(K_PROX_CHECK, true)
    val doubleTapWake get() = bool(K_DOUBLE_TAP_WAKE, true)
    val doubleTapSleep get() = bool(K_DOUBLE_TAP_SLEEP, true)
    val aodWhenIdle get() = bool(K_AOD, false)
    val nightDim get() = bool(K_NIGHT_DIM, false)
    val nightStart get() = str(K_NIGHT_START, "23:00")
    val nightEnd get() = str(K_NIGHT_END, "07:00")
    val nightLevel get() = int(K_NIGHT_LEVEL, 1).coerceIn(0, 7)
    val homeReturnSec get() = str(K_HOME_RETURN, "30").toIntOrNull() ?: 30

    // --- pages
    /** Ordered, comma-separated page ids; a leading '-' marks a disabled page. */
    var pages: String
        get() = str(K_PAGES, DEFAULT_PAGES)
        set(v) = sp.edit().putString(K_PAGES, v).apply()

    // --- watch faces
    var face: Int
        get() = int(K_FACE, 0)
        set(v) = sp.edit().putInt(K_FACE, v).apply()
    fun faceColor(face: Int, def: Int) = sp.getInt("face_${face}_color", def)
    fun faceAccent(face: Int, def: Int) = sp.getInt("face_${face}_accent", def)
    fun faceBgUri(face: Int): String? = sp.getString("face_${face}_bg", null)
    val worldClockZones get() = str(K_WORLD_ZONES, "Europe/London,America/New_York,Asia/Tokyo")
    val weatherLat get() = str(K_WEATHER_LAT, "")
    val weatherLon get() = str(K_WEATHER_LON, "")
    val weatherUnitF get() = bool(K_WEATHER_F, false)
    val use24h get() = bool(K_24H, true)

    // --- music
    val musicAutoShow get() = bool(K_MUSIC_AUTO, true)

    // --- notifications
    val notifWake get() = bool(K_NOTIF_WAKE, true)
    val notifPrivacy get() = bool(K_NOTIF_PRIVACY, true)
    /** Apps whose notifications do NOT wake the panel. */
    val notifMutedApps: Set<String> get() = sp.getStringSet(K_NOTIF_MUTED, emptySet()) ?: emptySet()

    // --- calls
    val callScreen get() = bool(K_CALL_SCREEN, true)

    // --- camera
    val cameraTimerSec get() = str(K_CAM_TIMER, "0").toIntOrNull() ?: 0
    val cameraMirrorPreview get() = bool(K_CAM_MIRROR, true)

    // --- photo frame / idle
    val photoFolder: String? get() = sp.getString(K_PHOTO_FOLDER, null)
    val photoIntervalSec get() = str(K_PHOTO_INTERVAL, "10").toIntOrNull() ?: 10
    val idleMedia: String? get() = sp.getString(K_IDLE_MEDIA, null)

    // --- physical button (KEY_F1)
    val buttonEnabled get() = bool(K_BTN_ENABLED, true)
    fun buttonAction(kind: String): String = str("btn_$kind", if (kind == "single") "toggle_panel" else "none")
    fun buttonApp(kind: String): String? = sp.getString("btn_${kind}_app", null)

    companion object {
        const val K_ENABLED = "enabled"
        const val K_AUTOSTART = "autostart"
        const val K_BRIGHTNESS = "brightness"
        const val K_AUTO_BRIGHTNESS = "auto_brightness"
        const val K_ROTATION = "rotation"
        const val K_MIRROR = "mirror"
        const val K_SWAP_BYTES = "swap_bytes"
        const val K_TOUCH_ROTATION = "touch_rotation"
        const val K_TOUCH_MIRROR = "touch_mirror"
        const val K_GRAB_TOUCH = "grab_touch"
        const val K_MAX_FPS = "max_fps"
        const val K_WAKE_MODE = "wake_mode"
        const val K_TIMEOUT = "timeout"
        const val K_FLIP_WAKE = "flip_wake"
        const val K_PROX_CHECK = "prox_check"
        const val K_DOUBLE_TAP_WAKE = "double_tap_wake"
        const val K_DOUBLE_TAP_SLEEP = "double_tap_sleep"
        const val K_AOD = "aod"
        const val K_NIGHT_DIM = "night_dim"
        const val K_NIGHT_START = "night_start"
        const val K_NIGHT_END = "night_end"
        const val K_NIGHT_LEVEL = "night_level"
        const val K_HOME_RETURN = "home_return"
        const val K_PAGES = "pages"
        const val K_FACE = "face"
        const val K_WORLD_ZONES = "world_zones"
        const val K_WEATHER_LAT = "weather_lat"
        const val K_WEATHER_LON = "weather_lon"
        const val K_WEATHER_F = "weather_f"
        const val K_24H = "use_24h"
        const val K_MUSIC_AUTO = "music_auto"
        const val K_NOTIF_WAKE = "notif_wake"
        const val K_NOTIF_PRIVACY = "notif_privacy"
        const val K_NOTIF_MUTED = "notif_muted"
        const val K_CALL_SCREEN = "call_screen"
        const val K_CAM_TIMER = "cam_timer"
        const val K_CAM_MIRROR = "cam_mirror"
        const val K_PHOTO_FOLDER = "photo_folder"
        const val K_PHOTO_INTERVAL = "photo_interval"
        const val K_IDLE_MEDIA = "idle_media"
        const val K_BTN_ENABLED = "btn_enabled"

        const val DEFAULT_PAGES = "watch,music,camera,tools,compass,level,stopwatch,timer,battery,steps,photos,-idle"
        val ALL_PAGES = listOf(
            "watch", "music", "camera", "tools", "compass", "level", "stopwatch", "timer",
            "battery", "steps", "photos", "idle",
        )
        val BUTTON_KINDS = listOf("single", "double", "triple", "long")
    }
}
