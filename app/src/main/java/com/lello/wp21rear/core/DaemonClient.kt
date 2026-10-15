package com.lello.wp21rear.core

import android.content.Context
import android.os.SystemClock
import com.lello.wp21rear.daemon.Proto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * App side of the root daemon. Starts it with `su`, speaks the binary protocol in [Proto], and
 * restarts it with backoff when it dies. Thread-safe.
 */
class DaemonClient(ctx: Context) {
    interface Listener {
        fun onDaemonState(up: Boolean, info: String) {}
        fun onTouch(action: Int, x: Int, y: Int, timeMs: Long) {}
        fun onKey(code: Int, value: Int, timeMs: Long) {}
    }

    class Reply(val ret: Int, val micros: Int, val error: String?)

    private val app = ctx.applicationContext
    @Volatile var listener: Listener? = null
    @Volatile var isUp = false
        private set
    @Volatile var lastError: String? = "not started"
        private set
    @Volatile var info: String = ""
        private set
    @Volatile var restarts = 0
        private set

    private var proc: Process? = null
    private var out: DataOutputStream? = null
    private val writeLock = Object()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (Reply) -> Unit>()
    private val execPending = ConcurrentHashMap<Int, (Int, String) -> Unit>()
    @Volatile private var wanted = false
    private var supervisor: Thread? = null
    @Volatile private var grab = true

    fun start() {
        synchronized(this) {
            if (wanted) return
            wanted = true
            supervisor = Thread(::supervise, "daemon-supervisor").apply { isDaemon = true; start() }
        }
    }

    /** Stops the daemon. If [panelOff] the panel is switched off first. */
    fun stop(panelOff: Boolean) {
        synchronized(this) { wanted = false }
        if (panelOff && isUp) panelOffBlocking()
        sendRaw(Proto.C_QUIT) {}
        proc?.destroy()
        supervisor?.interrupt()
    }

    private fun supervise() {
        var backoff = 1000L
        while (wanted) {
            val started = SystemClock.elapsedRealtime()
            runOnce()
            if (!wanted) break
            restarts++
            // Fast restarts on a crash loop are pointless; back off up to 30 s.
            backoff = if (SystemClock.elapsedRealtime() - started > 60_000) 1000L else (backoff * 2).coerceAtMost(30_000)
            RLog.w("daemon exited (${lastError ?: "?"}), restarting in ${backoff} ms")
            try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
        }
    }

    private fun runOnce() {
        val apk = app.applicationInfo.sourceDir
        val p = try {
            ProcessBuilder("su").start()
        } catch (e: Exception) {
            lastError = "su not available (root missing): $e"
            RLog.e(lastError!!)
            return
        }
        proc = p
        lastError = null
        val dout = DataOutputStream(BufferedOutputStream(p.outputStream, 1 shl 18))
        try {
            val cmd = "export CLASSPATH='$apk'\nexec app_process /system/bin ${RearDaemonName} 2>/dev/null\n"
            dout.write(cmd.toByteArray())
            dout.flush()
        } catch (e: Exception) {
            lastError = "could not start daemon: $e"
            p.destroy()
            return
        }
        val din = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 16))
        try {
            readLoop(din, dout)
        } catch (_: EOFException) {
            if (lastError == null) {
                lastError = if (isUp) "daemon closed the pipe" else "root denied or daemon failed to start (check Magisk → Superuser)"
            }
        } catch (e: Exception) {
            lastError = "daemon pipe: $e"
        } finally {
            val wasUp = isUp
            isUp = false
            synchronized(writeLock) { out = null }
            p.destroy()
            failPending("daemon down")
            if (wasUp) listener?.onDaemonState(false, lastError ?: "down")
        }
    }

    private fun readLoop(din: DataInputStream, dout: DataOutputStream) {
        while (true) {
            val type = din.readUnsignedByte()
            din.readUnsignedByte()
            din.readUnsignedShort()
            val len = din.readInt()
            val payload = ByteArray(len)
            din.readFully(payload)
            val b = ByteBuffer.wrap(payload)
            when (type) {
                Proto.D_READY -> {
                    val version = b.int
                    info = String(payload, 4, len - 4)
                    synchronized(writeLock) { out = dout }
                    isUp = true
                    lastError = null
                    RLog.i("daemon ready v$version: $info")
                    setGrab(grab)
                    listener?.onDaemonState(true, info)
                }
                Proto.D_REPLY -> {
                    val id = b.int
                    val r = Reply(b.int, b.int, null)
                    pending.remove(id)?.invoke(r)
                }
                Proto.D_ERROR -> {
                    val id = b.int
                    val msg = String(payload, 4, len - 4)
                    RLog.w("daemon error #$id: $msg")
                    pending.remove(id)?.invoke(Reply(Int.MIN_VALUE, 0, msg))
                }
                Proto.D_TOUCH -> {
                    val action = b.get().toInt()
                    val x = b.short.toInt()
                    val y = b.short.toInt()
                    listener?.onTouch(action, x, y, b.long)
                }
                Proto.D_KEY -> {
                    val code = b.short.toInt()
                    val value = b.get().toInt()
                    listener?.onKey(code, value, b.long)
                }
                Proto.D_LOG -> RLog.i("daemon: " + String(payload))
                Proto.D_EXEC -> {
                    val id = b.int
                    val code = b.int
                    execPending.remove(id)?.invoke(code, String(payload, 8, len - 8))
                }
            }
        }
    }

    private fun failPending(msg: String) {
        val p = HashMap(pending)
        pending.clear()
        for (cb in p.values) cb(Reply(Int.MIN_VALUE, 0, msg))
        val e = HashMap(execPending)
        execPending.clear()
        for (cb in e.values) cb(-1, msg)
    }

    private fun nextId() = ids.getAndIncrement()

    private fun sendRaw(type: Int, body: (DataOutputStream) -> Unit): Boolean {
        val bytes = java.io.ByteArrayOutputStream(64)
        body(DataOutputStream(bytes))
        return sendBytes(type, bytes.toByteArray(), null)
    }

    private fun sendBytes(type: Int, head: ByteArray, tail: ByteArray?): Boolean {
        synchronized(writeLock) {
            val o = out ?: return false
            return try {
                o.writeByte(type)
                o.writeByte(0)
                o.writeShort(0)
                o.writeInt(head.size + (tail?.size ?: 0))
                o.write(head)
                if (tail != null) o.write(tail)
                o.flush()
                true
            } catch (e: Exception) {
                lastError = "write: $e"
                false
            }
        }
    }

    private fun request(type: Int, tail: ByteArray?, cb: ((Reply) -> Unit)?, body: (DataOutputStream) -> Unit = {}) {
        val id = nextId()
        val bytes = java.io.ByteArrayOutputStream(64)
        val d = DataOutputStream(bytes)
        d.writeInt(id)
        body(d)
        if (cb != null) pending[id] = cb
        if (!sendBytes(type, bytes.toByteArray(), tail)) {
            pending.remove(id)
            cb?.invoke(Reply(Int.MIN_VALUE, 0, lastError ?: "daemon not running"))
        }
    }

    private fun callBlocking(type: Int, timeoutMs: Long): Reply {
        val latch = CountDownLatch(1)
        var result = Reply(Int.MIN_VALUE, 0, "timeout")
        request(type, null, { result = it; latch.countDown() })
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    fun hal(code: Int, arg: Int, cb: ((Reply) -> Unit)? = null) =
        request(Proto.C_HAL, null, cb) { it.writeInt(code); it.writeInt(arg) }

    fun halBlocking(code: Int, arg: Int, timeoutMs: Long = 2000): Reply {
        val latch = CountDownLatch(1)
        var result = Reply(Int.MIN_VALUE, 0, "timeout")
        hal(code, arg) { result = it; latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    fun frame(rgb565be: ByteArray, cb: (Reply) -> Unit) = request(Proto.C_FRAME, rgb565be, cb)

    fun panelOffBlocking(timeoutMs: Long = 3000): Reply = callBlocking(Proto.C_PANEL_OFF, timeoutMs)

    fun exec(cmd: String, cb: ((Int, String) -> Unit)? = null) {
        val id = nextId()
        if (cb != null) execPending[id] = cb
        val c = cmd.toByteArray()
        val head = ByteBuffer.allocate(4 + c.size).putInt(id).put(c).array()
        if (!sendBytes(Proto.C_EXEC, head, null)) {
            execPending.remove(id)
            cb?.invoke(-1, lastError ?: "daemon not running")
        }
    }

    fun execBlocking(cmd: String, timeoutMs: Long = 20_000): Pair<Int, String> {
        val latch = CountDownLatch(1)
        var r = Pair(-1, "timeout")
        exec(cmd) { c, o -> r = Pair(c, o); latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return r
    }

    fun setGrab(on: Boolean) {
        grab = on
        sendRaw(Proto.C_GRAB) { it.writeByte(if (on) 1 else 0) }
    }

    fun ping(cb: (Reply) -> Unit) = request(Proto.C_PING, null, cb)

    companion object {
        const val RearDaemonName = "com.lello.wp21rear.daemon.RearDaemon"

        /** adb one-liner that turns the panel off without the app (documented in README). */
        const val ADB_OFF =
            "adb shell su -c 'am force-stop com.lello.wp21rear; CLASSPATH=\$(pm path com.lello.wp21rear | cut -d: -f2) app_process /system/bin com.lello.wp21rear.daemon.RearDaemon off'"
    }
}
