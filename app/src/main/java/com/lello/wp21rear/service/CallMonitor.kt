package com.lello.wp21rear.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.pages.CallControl
import com.lello.wp21rear.pages.CallOverlay

/**
 * Incoming / ongoing call screen on the rear panel. Uses TelecomManager with ANSWER_PHONE_CALLS;
 * if that permission is missing the root daemon answers/ends via `input keyevent`.
 */
class CallMonitor(private val ctx: Context, private val wake: WakeController) : CallControl {
    private val tm = ctx.getSystemService(TelecomManager::class.java)
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var overlay: CallOverlay? = null
    private var wokeForCall = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            Rear.ui.post { onState(state, number) }
        }
    }

    fun start() {
        ctx.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
    }

    fun stop() {
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun onState(state: String, number: String?) {
        if (!Rear.prefs.callScreen) return
        val ui = Rear.ui
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val o = overlay ?: CallOverlay(ui, this).also { overlay = it }
                o.ringing = true
                if (!number.isNullOrEmpty()) {
                    o.number = number
                    lookup(o, number)
                }
                wokeForCall = !wake.isOn
                wake.wake("call")
                ui.showOverlay(o, false)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val o = overlay
                if (o != null) {
                    o.ringing = false
                    o.activeSince = SystemClock.elapsedRealtime()
                    if (wake.isOn) ui.showOverlay(o, false)
                    ui.invalidate()
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val o = overlay
                overlay = null
                if (o != null && ui.overlay === o) ui.closeOverlay(false)
                if (wokeForCall) wake.sleep("call ended")
                wokeForCall = false
            }
        }
    }

    private fun lookup(o: CallOverlay, number: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    o.name = c.getString(0) ?: ""
                    c.getString(1)?.let { p ->
                        ctx.contentResolver.openInputStream(Uri.parse(p))?.use { o.photo = BitmapFactory.decodeStream(it) }
                    }
                }
            }
        } catch (e: Exception) {
            RLog.w("contact lookup: $e")
        }
    }

    private fun canAnswer() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun answer() {
        if (canAnswer()) tm.acceptRingingCall() else Rear.daemon.exec("input keyevent KEYCODE_CALL")
    }

    @SuppressLint("MissingPermission")
    override fun reject() {
        @Suppress("DEPRECATION")
        if (canAnswer()) tm.endCall() else Rear.daemon.exec("input keyevent KEYCODE_ENDCALL")
    }

    override fun hangUp() = reject()

    override fun isMuted() = am.isMicrophoneMute

    override fun toggleMute(): Boolean {
        am.isMicrophoneMute = !am.isMicrophoneMute
        return am.isMicrophoneMute
    }

    override fun isSpeaker(): Boolean = am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    override fun toggleSpeaker(): Boolean {
        if (isSpeaker()) {
            am.clearCommunicationDevice()
        } else {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { am.setCommunicationDevice(it) }
        }
        return isSpeaker()
    }
}
