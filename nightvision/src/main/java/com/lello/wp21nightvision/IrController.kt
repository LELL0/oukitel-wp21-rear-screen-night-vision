package com.lello.wp21nightvision

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Controls the WP21 infrared illuminator through the vendor driver (irled-gpio):
 *
 *   /sys/bus/platform/drivers/irled-gpio/yft_irled_ctl   "on" / "off"  (GPIO 0 + PWM, what the stock camera uses)
 *   /sys/bus/platform/drivers/irled-gpio/irmotor         "gpio <pin 0..3> <0|1>" or "pwm <pin> <freq> <0|1>"
 *
 * Apps may not write these files under SELinux, so a small root shell does it. The shell switches the
 * IR LEDs off by itself when its stdin closes, so they never stay on after the app dies.
 */
class IrController {
    @Volatile var available = false
        private set
    @Volatile var isOn = false
        private set
    @Volatile var lastError: String? = null
        private set

    private var proc: Process? = null
    private var out: OutputStream? = null
    private val replies = LinkedBlockingQueue<String>()

    /** Starts the root shell. Blocking (the Magisk prompt may appear); call off the main thread. */
    fun start(): Boolean {
        if (proc != null) return available
        try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            proc = p
            out = p.outputStream
            thread(isDaemon = true, name = "ir-reader") {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).forEachLine { replies.offer(it) }
                } catch (_: Exception) {
                }
                replies.offer("eof")
            }
            val script = """
                CTL=$CTL; PIN=$PIN
                trap 'echo off > ${'$'}CTL' EXIT
                [ -w ${'$'}CTL ] && echo ready || echo missing
                while read c a b; do
                  case "${'$'}c" in
                    on|off) echo ${'$'}c > ${'$'}CTL ;;
                    pin) echo "gpio ${'$'}a ${'$'}b" > ${'$'}PIN ;;
                    quit) break ;;
                  esac
                  echo "state $(cat ${'$'}CTL)"
                done
                echo off > ${'$'}CTL
            """.trimIndent()
            out!!.write((script + "\n").toByteArray())
            out!!.flush()
            val r = replies.poll(60, TimeUnit.SECONDS)
            available = r == "ready"
            lastError = when (r) {
                "ready" -> null
                "missing" -> "IR driver not found (not a WP21?)"
                null -> "timed out waiting for root"
                else -> "root denied"
            }
        } catch (e: Exception) {
            lastError = "no root: $e"
            available = false
        }
        if (!available) stop()
        return available
    }

    private fun send(cmd: String): Boolean {
        val o = out ?: return false
        return try {
            replies.clear()
            o.write("$cmd\n".toByteArray())
            o.flush()
            val r = replies.poll(3, TimeUnit.SECONDS) ?: return false
            isOn = r.trim() == "state on"
            true
        } catch (e: Exception) {
            lastError = e.toString()
            Log.w("WP21NightVision", "ir: $e")
            false
        }
    }

    fun set(on: Boolean): Boolean = send(if (on) "on" else "off")

    /** Raw access to one of the four driver pins (advanced). */
    fun pin(n: Int, high: Boolean) = send("pin $n ${if (high) 1 else 0}")

    /** Turns the LEDs off and ends the root shell. */
    fun stop() {
        try {
            out?.write("quit\n".toByteArray())
            out?.flush()
        } catch (_: Exception) {
        }
        proc?.destroy()
        proc = null
        out = null
        isOn = false
    }

    companion object {
        const val CTL = "/sys/bus/platform/drivers/irled-gpio/yft_irled_ctl"
        const val PIN = "/sys/bus/platform/drivers/irled-gpio/irmotor"
    }
}
