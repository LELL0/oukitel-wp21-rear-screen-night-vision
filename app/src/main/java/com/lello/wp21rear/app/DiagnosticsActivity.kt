package com.lello.wp21rear.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.RLog
import com.lello.wp21rear.daemon.Proto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Daemon and panel status, rear touch viewer, test actions and the log (with export). */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var touchView: TouchView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            update()
            h.postDelayed(this, 500)
        }
    }
    private val touchListener: (Int, Int, Int) -> Unit = { a, x, y -> touchView.add(a, x, y) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Diagnostics"
        val root = vbox()
        status = label("", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        root.add(status)
        root.add(header("Rear touch (raw controller coordinates)"))
        touchView = TouchView(this)
        root.add(touchView)
        root.add(header("Tests"))
        val r1 = hbox()
        r1.add(button("Restart daemon") { restartDaemon() }, 0, -2, 1f)
        r1.add(button("Dump frame PNG") { dumpFrame() }, 0, -2, 1f)
        root.add(r1)
        val r2 = hbox()
        r2.add(button("Brightness sweep") { sweep() }, 0, -2, 1f)
        r2.add(button("Export log") { exportLog() }, 0, -2, 1f)
        root.add(r2)
        root.add(header("Log"))
        log = label("", 10f).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.add(log)
        setContentView(scroll(root))
    }

    override fun onResume() {
        super.onResume()
        Rear.touchListeners.add(touchListener)
        h.post(tick)
    }

    override fun onPause() {
        Rear.touchListeners.remove(touchListener)
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun update() {
        status.text = if (!Rear.running) "service stopped" else {
            val d = Rear.daemon
            val p = Rear.panel
            buildString {
                append("daemon     ").append(if (d.isUp) "up" else "DOWN").append("  restarts ${d.restarts}\n")
                append("info       ${d.info}\n")
                d.lastError?.let { append("last error $it\n") }
                append("panel      ${p.state}  level ${p.level}\n")
                append("fps        %.1f   frames %d\n".format(p.fps(), p.framesSent))
                append("frame time %.1f ms  HAL ret %d\n".format(p.lastFrameMicros / 1000f, p.lastHalRet))
                p.lastHalError?.let { append("HAL error  $it\n") }
                append("page       ${Rear.ui.current()?.id}  overlay ${Rear.ui.overlay?.id}")
            }
        }
        log.text = RLog.snapshot().takeLast(150).reversed().joinToString("\n")
    }

    private fun restartDaemon() {
        if (!Rear.running) return
        Thread {
            // the supervisor notices the exit and starts a fresh daemon
            Rear.daemon.exec("pkill -f com.lello.wp21rear.daemon.RearDaemon")
        }.start()
    }

    private fun dumpFrame() {
        val b = if (Rear.running) Rear.panel.lastBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else null
        if (b == null) { toast("No frame yet"); return }
        val f = java.io.File(getExternalFilesDir(null), "last_frame.png")
        f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        toast("Saved $f")
    }

    private fun sweep() {
        if (!Rear.running) return
        Rear.service?.wakePanel()
        Thread {
            for (l in 0..7) {
                Rear.ui.post { Rear.ui.toast("level $l", 900) }
                Rear.panel.setLevel(l)
                Thread.sleep(1200)
            }
            Rear.panel.setLevel(Rear.prefs.brightness)
        }.start()
    }

    private fun exportLog() {
        val name = "wp21rear-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("Export failed"); return }
        contentResolver.openOutputStream(uri)?.use { out ->
            out.write((status.text.toString() + "\n\n" + RLog.snapshot().joinToString("\n")).toByteArray())
        }
        toast("Saved Download/$name")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Draws the last rear touches in the 360x360 controller space. */
    class TouchView(ctx: Context) : View(ctx) {
        private val pts = ArrayDeque<Triple<Int, Int, Int>>()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = ctx.dp(12).toFloat() }

        fun add(a: Int, x: Int, y: Int) {
            synchronized(pts) {
                pts.addLast(Triple(a, x, y))
                while (pts.size > 200) pts.removeFirst()
            }
            postInvalidate()
        }

        override fun onMeasure(w: Int, hSpec: Int) {
            val s = minOf(MeasureSpec.getSize(w), context.dp(240))
            setMeasuredDimension(MeasureSpec.getSize(w), s)
        }

        override fun onDraw(c: Canvas) {
            val s = height.toFloat()
            val l = (width - s) / 2
            p.style = Paint.Style.STROKE
            p.color = 0xFF3C4043.toInt()
            c.drawCircle(l + s / 2, s / 2, s / 2 - 2, p)
            p.style = Paint.Style.FILL
            val list = synchronized(pts) { pts.toList() }
            for ((i, t) in list.withIndex()) {
                val (a, x, y) = t
                p.color = when (a) {
                    Proto.TOUCH_DOWN -> Color.GREEN
                    Proto.TOUCH_UP -> Color.RED
                    else -> Color.argb(80 + 175 * i / list.size.coerceAtLeast(1), 79, 195, 247)
                }
                c.drawCircle(l + x / 360f * s, y / 360f * s, if (a == Proto.TOUCH_MOVE) 3f else 7f, p)
            }
            list.lastOrNull()?.let { (a, x, y) ->
                c.drawText("${listOf("down", "move", "up")[a]} $x,$y", l, s - 4, text)
            }
        }
    }
}
