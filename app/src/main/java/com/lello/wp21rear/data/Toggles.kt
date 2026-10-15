package com.lello.wp21rear.data

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.telephony.TelephonyManager
import com.lello.wp21rear.core.DaemonClient
import com.lello.wp21rear.core.RLog

/**
 * Quick toggles. State is read with normal APIs; changes that apps may not make on Android 14
 * (Wi-Fi, Bluetooth, mobile data, DND, rotation, ringer) go through the root daemon.
 */
class Toggles(private val ctx: Context, private val daemon: DaemonClient) {
    data class Toggle(val id: String, val label: String, val icon: String)

    val all = listOf(
        Toggle("flashlight", "Torch", "flashlight"),
        Toggle("wifi", "Wi-Fi", "wifi"),
        Toggle("bluetooth", "Bluetooth", "bluetooth"),
        Toggle("dnd", "Do not disturb", "dnd"),
        Toggle("data", "Mobile data", "data"),
        Toggle("rotation", "Auto-rotate", "rotate"),
        Toggle("ringer", "Ringer", "bell"),
    )

    @Volatile var torchOn = false
        private set
    private var torchId: String? = null
    private val cm = ctx.getSystemService(CameraManager::class.java)

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) { torchOn = enabled; onChange?.invoke() }
        }
    }

    /** Called (on any thread) when a state may have changed. */
    var onChange: (() -> Unit)? = null

    init {
        try {
            torchId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.registerTorchCallback(torchCb, null)
        } catch (e: Exception) {
            RLog.w("torch: $e")
        }
    }

    fun release() {
        cm.unregisterTorchCallback(torchCb)
    }

    fun isOn(id: String): Boolean = try {
        when (id) {
            "flashlight" -> torchOn
            "wifi" -> ctx.getSystemService(WifiManager::class.java).isWifiEnabled
            "bluetooth" -> ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
            "dnd" -> ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
            "data" -> ctx.getSystemService(TelephonyManager::class.java).isDataEnabled
            "rotation" -> Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "ringer" -> ctx.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    /** Ringer mode icon: bell / vibrate / bell_off. */
    fun ringerIcon(): String = when (ctx.getSystemService(AudioManager::class.java).ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> "bell"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "bell_off"
    }

    fun setTorch(on: Boolean) {
        val id = torchId ?: return
        try {
            cm.setTorchMode(id, on)
        } catch (e: Exception) {
            RLog.w("torch: $e")
        }
    }

    fun toggle(id: String) {
        val on = isOn(id)
        when (id) {
            "flashlight" -> setTorch(!on)
            "wifi" -> root("svc wifi ${if (on) "disable" else "enable"}")
            "bluetooth" -> root("svc bluetooth ${if (on) "disable" else "enable"}")
            "data" -> root("svc data ${if (on) "disable" else "enable"}")
            "dnd" -> root("cmd notification set_dnd ${if (on) "off" else "priority"}")
            "rotation" -> root("settings put system accelerometer_rotation ${if (on) 0 else 1}")
            "ringer" -> {
                // normal -> vibrate -> silent -> normal
                val am = ctx.getSystemService(AudioManager::class.java)
                val next = when (am.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> "VIBRATE"
                    AudioManager.RINGER_MODE_VIBRATE -> "SILENT"
                    else -> "NORMAL"
                }
                root("cmd audio set-ringer-mode $next")
            }
        }
    }

    private fun root(cmd: String) {
        daemon.exec(cmd) { code, out ->
            if (code != 0) RLog.w("toggle '$cmd' -> $code ${out.trim()}")
            onChange?.invoke()
        }
    }
}
