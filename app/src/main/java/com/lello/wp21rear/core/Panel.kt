package com.lello.wp21rear.core

import android.graphics.Bitmap
import android.os.SystemClock
import com.lello.wp21rear.daemon.Proto
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Panel power state and frame pushing. Frames are "latest wins": while one frame is in flight to
 * the HAL, newer frames replace the queued one, so a slow panel never builds a backlog.
 */
class Panel(private val daemon: DaemonClient, private val prefs: Prefs) {
    enum class State { OFF, TURNING_ON, ON, TURNING_OFF }

    @Volatile var state = State.OFF
        private set
    @Volatile var level = -1
        private set

    // stats
    @Volatile var framesSent = 0L
        private set
    @Volatile var lastFrameMicros = 0
        private set
    @Volatile var lastHalRet = 0
        private set
    @Volatile var lastHalError: String? = null
        private set
    private val frameTimes = LongArray(30)
    private var frameTimesIdx = 0

    /** Last frame as shown (after rotation/mirror), for the live mirror and the PNG dump. */
    @Volatile var lastBitmap: Bitmap? = null
        private set
    val frameListeners = CopyOnWriteArrayList<(Bitmap) -> Unit>()

    private val lock = Object()
    private var inFlight = false
    private var queued: ByteArray? = null
    private val pixels = IntArray(Proto.W * Proto.H)

    fun fps(): Float {
        synchronized(frameTimes) {
            val n = frameTimes.count { it > 0 }
            if (n < 2) return 0f
            val newest = frameTimes[(frameTimesIdx - 1 + frameTimes.size) % frameTimes.size]
            val oldest = frameTimes[if (n < frameTimes.size) 0 else frameTimesIdx]
            if (newest <= oldest) return 0f
            // only meaningful while frames keep coming
            if (SystemClock.uptimeMillis() - newest > 1500) return 0f
            return (n - 1) * 1000f / (newest - oldest)
        }
    }

    /** Converts an ARGB_8888 360x360 bitmap to RGB565 big-endian (or little-endian if calibrated so). */
    fun encode(bmp: Bitmap): ByteArray {
        bmp.getPixels(pixels, 0, Proto.W, 0, 0, Proto.W, Proto.H)
        val out = ByteArray(Proto.FRAME_BYTES)
        val swap = prefs.swapBytes
        var o = 0
        for (c in pixels) {
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val v = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            if (!swap) {
                out[o] = (v shr 8).toByte(); out[o + 1] = v.toByte()
            } else {
                out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte()
            }
            o += 2
        }
        return out
    }

    /**
     * Push a rendered frame. [phys] (360x360 ARGB_8888, already rotated/mirrored for the panel) is
     * sent; [logical] is what the user sees and feeds the live mirror. Listeners must copy the
     * bitmap if they keep it: the renderer reuses it a few frames later.
     */
    fun push(phys: Bitmap, logical: Bitmap) {
        if (state != State.ON) return
        val data = encode(phys)
        lastBitmap = logical
        for (l in frameListeners) l(logical)
        submit(data)
    }

    private fun submit(data: ByteArray) {
        synchronized(lock) {
            if (inFlight) {
                queued = data
                return
            }
            inFlight = true
        }
        send(data)
    }

    private fun send(data: ByteArray) {
        daemon.frame(data) { r ->
            if (r.error != null) {
                lastHalError = r.error
            } else {
                lastHalRet = r.ret
                lastFrameMicros = r.micros
                framesSent++
                synchronized(frameTimes) {
                    frameTimes[frameTimesIdx] = SystemClock.uptimeMillis()
                    frameTimesIdx = (frameTimesIdx + 1) % frameTimes.size
                }
            }
            val next: ByteArray?
            synchronized(lock) {
                next = queued
                queued = null
                if (next == null) inFlight = false
            }
            if (next != null) send(next)
        }
    }

    /**
     * Power-on sequence copied from the stock app: power(1), frame, ~400 ms, bl(1), level, frame.
     * Blocking; call from a background thread. [first] is the first frame to show.
     */
    fun turnOn(first: Bitmap, lvl: Int) {
        // [first] is the physical frame; the mirror catches up with the next regular frame.
        if (state == State.ON || state == State.TURNING_ON) {
            setLevel(lvl)
            return
        }
        state = State.TURNING_ON
        RLog.i("panel on (level $lvl)")
        check(daemon.halBlocking(Proto.HAL_POWER, 1), "power 1")
        val data = encode(first)
        frameBlocking(data)
        SystemClock.sleep(400)
        check(daemon.halBlocking(Proto.HAL_BL, 1), "bl 1")
        check(daemon.halBlocking(Proto.HAL_LEVEL, lvl), "level $lvl")
        level = lvl
        frameBlocking(data)
        state = State.ON
    }

    /** Power-off sequence from the stock app: bl(0), ~100 ms, power(0). Blocking. */
    fun turnOff() {
        if (state == State.OFF) return
        state = State.TURNING_OFF
        RLog.i("panel off")
        // wait for the in-flight frame so it does not land after power-off
        synchronized(lock) { queued = null }
        val until = SystemClock.uptimeMillis() + 500
        while (synchronized(lock) { inFlight } && SystemClock.uptimeMillis() < until) SystemClock.sleep(10)
        val r = daemon.panelOffBlocking()
        if (r.error != null) lastHalError = r.error
        state = State.OFF
        level = -1
    }

    /** Forget the power state (the daemon restarted and turned the panel off on its way out). */
    fun markOff() {
        state = State.OFF
        level = -1
        synchronized(lock) { inFlight = false; queued = null }
    }

    fun setLevel(lvl: Int) {
        val l = lvl.coerceIn(0, 7)
        if (state != State.ON || l == level) return
        level = l
        daemon.hal(Proto.HAL_LEVEL, l) { r -> if (r.error != null) lastHalError = r.error else lastHalRet = r.ret }
    }

    private fun frameBlocking(data: ByteArray) {
        val latch = java.util.concurrent.CountDownLatch(1)
        daemon.frame(data) { r ->
            if (r.error != null) lastHalError = r.error else { lastHalRet = r.ret; lastFrameMicros = r.micros; framesSent++ }
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun check(r: DaemonClient.Reply, what: String) {
        if (r.error != null) {
            lastHalError = "$what: ${r.error}"
            RLog.w("HAL $what failed: ${r.error}")
        } else {
            lastHalRet = r.ret
        }
    }
}
