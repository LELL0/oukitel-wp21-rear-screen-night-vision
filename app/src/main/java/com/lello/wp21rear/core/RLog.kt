package com.lello.wp21rear.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory ring log shown in Diagnostics and exported from there; mirrored to logcat (tag WP21Rear). */
object RLog {
    private const val TAG = "WP21Rear"
    private const val MAX = 2000
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String, t: Throwable? = null) {
        add("E", if (t != null) "$msg: $t" else msg)
        if (t != null) Log.e(TAG, msg, t)
    }

    private fun add(level: String, msg: String) {
        when (level) {
            "W" -> Log.w(TAG, msg)
            "E" -> Log.e(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        val line = synchronized(fmt) { fmt.format(Date()) } + " $level $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        for (l in listeners) l(line)
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}
