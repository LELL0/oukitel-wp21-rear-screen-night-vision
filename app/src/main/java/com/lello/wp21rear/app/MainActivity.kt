package com.lello.wp21rear.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.DaemonClient
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.service.NotifListener
import com.lello.wp21rear.service.RearService
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var mirror: MirrorView
    private lateinit var status: TextView
    private lateinit var perms: TextView
    private lateinit var enable: Switch
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = vbox()

        mirror = MirrorView(this)
        root.add(mirror)
        root.add(label("Touch the mirror to control the rear screen.", 12f).apply { textAlignment = TextView.TEXT_ALIGNMENT_CENTER })

        status = label("", 13f)
        root.add(status)

        val row = hbox()
        row.add(button("Panel on") { Rear.service?.wakePanel() ?: toast("Service is not running") }, 0, -2, 1f)
        row.add(button("Panel off") { Rear.service?.sleepPanel() }, 0, -2, 1f)
        root.add(row)

        root.add(dangerButton("EMERGENCY: PANEL OFF") { emergencyOff() })

        enable = Switch(this).apply {
            text = "Rear screen enabled"
            textSize = 16f
            setOnCheckedChangeListener { _, on ->
                if (on == prefs.enabled && (on == Rear.running)) return@setOnCheckedChangeListener
                prefs.sp.edit().putBoolean(Prefs.K_ENABLED, on).apply()
                if (on) RearService.start(this@MainActivity) else stopService(Intent(this@MainActivity, RearService::class.java))
                h.postDelayed({ mirror.reattach() }, 800)
            }
        }
        root.add(enable)

        root.add(header("Configure"))
        root.add(button("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.add(button("Pages: order and visibility") { startActivity(Intent(this, PagesActivity::class.java)) })
        root.add(button("Side button actions") { startActivity(Intent(this, ButtonActivity::class.java)) })
        root.add(button("Watch faces") { startActivity(Intent(this, FacesActivity::class.java)) })
        root.add(button("Calibration (rotation, touch, colours)") { startActivity(Intent(this, CalibrationActivity::class.java)) })
        root.add(button("Diagnostics and log") { startActivity(Intent(this, DiagnosticsActivity::class.java)) })

        root.add(header("Permissions"))
        perms = label("", 13f)
        root.add(perms)
        root.add(button("Ask for missing permissions") { askPermissions() })
        root.add(button("Grant everything with root") { grantWithRoot() })
        root.add(button("Notification access settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        root.add(header("Emergency from a PC"))
        root.add(label(DaemonClient.ADB_OFF, 11f).apply { setTextIsSelectable(true) })

        setContentView(scroll(root))

        if (prefs.enabled && !Rear.running) {
            RearService.start(this)
            h.postDelayed({ mirror.reattach() }, 800)
        }
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
        enable.isChecked = prefs.enabled && Rear.running
        updatePerms()
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun updateStatus() {
        if (!Rear.running) {
            status.text = "Service: stopped"
            return
        }
        val d = Rear.daemon
        val p = Rear.panel
        status.text = buildString {
            append("Root daemon: ").append(if (d.isUp) "running (${d.info})" else "down — ${d.lastError}").append('\n')
            append("Panel: ${p.state}  level ${p.level}  fps %.1f".format(p.fps()))
            p.lastHalError?.let { append("\nLast HAL error: $it") }
        }
        mirror.invalidate()
    }

    private fun emergencyOff() {
        val s = Rear.service
        if (s != null) {
            s.emergencyOff()
        } else {
            // Service not running: run the daemon's off mode directly.
            thread {
                try {
                    val apk = applicationInfo.sourceDir
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "CLASSPATH='$apk' app_process /system/bin ${DaemonClient.RearDaemonName} off")).waitFor()
                } catch (_: Exception) {}
            }
        }
        prefs.sp.edit().putBoolean(Prefs.K_ENABLED, false).apply()
        enable.isChecked = false
        toast("Panel off. Rear screen disabled.")
    }

    // ------------------------------------------------------------------ permissions

    private val runtimePerms = listOf(
        Manifest.permission.POST_NOTIFICATIONS to "Notifications (service icon)",
        Manifest.permission.CAMERA to "Camera (rear viewfinder)",
        Manifest.permission.RECORD_AUDIO to "Microphone (video sound)",
        Manifest.permission.READ_PHONE_STATE to "Phone state (call screen)",
        Manifest.permission.READ_CALL_LOG to "Call log (caller number)",
        Manifest.permission.ANSWER_PHONE_CALLS to "Answer calls",
        Manifest.permission.READ_CONTACTS to "Contacts (caller name)",
        Manifest.permission.ACTIVITY_RECOGNITION to "Activity (steps)",
        Manifest.permission.ACCESS_COARSE_LOCATION to "Location (weather)",
        Manifest.permission.READ_MEDIA_IMAGES to "Photos (slideshow)",
        Manifest.permission.READ_MEDIA_VIDEO to "Videos (idle screen)",
    )

    private fun missing() = runtimePerms.filter { ContextCompat.checkSelfPermission(this, it.first) != PackageManager.PERMISSION_GRANTED }

    private fun listenerOn() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun updatePerms() {
        val m = missing()
        perms.text = buildString {
            append(if (listenerOn()) "✓ Notification access\n" else "✗ Notification access (notifications, music)\n")
            if (m.isEmpty()) append("✓ All runtime permissions granted")
            else m.forEach { append("✗ ").append(it.second).append('\n') }
        }
    }

    private fun askPermissions() {
        val m = missing().map { it.first }
        if (m.isEmpty()) toast("Nothing missing") else requestPermissions(m.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePerms()
    }

    /**
     * One-tap setup with root. Shows exactly what will run; every command is a normal, revertible
     * permission grant (pm revoke / appops reset / Settings undo them).
     */
    private fun grantWithRoot() {
        val pkg = packageName
        val listener = ComponentName(this, NotifListener::class.java).flattenToString()
        val cmds = runtimePerms.map { "pm grant $pkg ${it.first}" } + listOf(
            "cmd notification allow_listener $listener",
            "appops set $pkg SCHEDULE_EXACT_ALARM allow",
            "dumpsys deviceidle whitelist +$pkg",
        )
        AlertDialog.Builder(this)
            .setTitle("Run these as root?")
            .setMessage(cmds.joinToString("\n") + "\n\nThese grant this app its permissions, notification access, exact alarms (always-on clock, timer) and exclude it from battery optimisation.")
            .setPositiveButton("Run") { _, _ ->
                thread {
                    val out = try {
                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmds.joinToString("; ")))
                        val text = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
                        p.waitFor()
                        text
                    } catch (e: Exception) {
                        e.toString()
                    }
                    runOnUiThread {
                        updatePerms()
                        toast(if (out.isBlank()) "Done" else out.take(300))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
