package com.lello.wp21nightvision

import android.graphics.Bitmap
import kotlin.math.pow

/**
 * Colour modes for the viewfinder and saved photos. Every mode except Natural works on luminance
 * with a gain and gamma, through a 256-entry lookup table, so it is cheap per pixel.
 */
enum class Mode(val label: String) {
    NATURAL("Natural"),
    GREEN("Night green"),
    MONO("Mono"),
    THERMAL("Heat map"),
    AMBER("Amber"),
}

class Filter {
    private val lut = IntArray(256)
    private var builtFor = ""

    var mode = Mode.GREEN
    /** 1..8: brightens dark scenes. */
    var gain = 2f
    /** < 1 lifts shadows. */
    var gamma = 0.8f

    private fun build() {
        val key = "$mode/$gain/$gamma"
        if (key == builtFor) return
        builtFor = key
        for (i in 0 until 256) {
            val v = ((i / 255f).pow(gamma) * gain).coerceIn(0f, 1f)
            lut[i] = when (mode) {
                Mode.NATURAL -> gray(v)
                Mode.GREEN -> rgb(v * 0.25f, 0.15f + v * 0.85f, v * 0.25f)
                Mode.MONO -> gray(v)
                Mode.AMBER -> rgb(0.1f + v * 0.9f, 0.05f + v * 0.62f, v * 0.12f)
                Mode.THERMAL -> iron(v)
            }
        }
    }

    private fun gray(v: Float) = rgb(v, v, v)

    private fun rgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or
            (b.coerceIn(0f, 1f) * 255).toInt()

    /** "Iron" false-colour palette: black → purple → red → orange → yellow → white. */
    private fun iron(v: Float): Int {
        val stops = arrayOf(
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0.2f, 0.25f, 0f, 0.45f), floatArrayOf(0.45f, 0.75f, 0.05f, 0.35f),
            floatArrayOf(0.65f, 0.95f, 0.35f, 0f), floatArrayOf(0.85f, 1f, 0.8f, 0f), floatArrayOf(1f, 1f, 1f, 1f),
        )
        for (k in 1 until stops.size) {
            if (v <= stops[k][0]) {
                val a = stops[k - 1]
                val b = stops[k]
                val t = (v - a[0]) / (b[0] - a[0])
                return rgb(a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t)
            }
        }
        return rgb(1f, 1f, 1f)
    }

    /** Applies the mode in place to ARGB pixels. */
    fun apply(px: IntArray, count: Int = px.size) {
        if (mode == Mode.NATURAL && gain == 1f && gamma == 1f) return
        build()
        if (mode == Mode.NATURAL) {
            // brighten colour without discarding it
            for (i in 0 until count) {
                val c = px[i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = (r * 77 + g * 150 + b * 29) shr 8
                val ny = (lut[y] and 0xff).coerceAtLeast(1)
                val k = if (y == 0) 1f else ny.toFloat() / y
                px[i] = (0xFF shl 24) or ((r * k).toInt().coerceAtMost(255) shl 16) or
                    ((g * k).toInt().coerceAtMost(255) shl 8) or (b * k).toInt().coerceAtMost(255)
            }
            return
        }
        for (i in 0 until count) {
            val c = px[i]
            val y = (((c shr 16) and 0xff) * 77 + ((c shr 8) and 0xff) * 150 + (c and 0xff) * 29) shr 8
            px[i] = lut[y]
        }
    }

    /** Applies the mode to a whole bitmap, row by row (works for full-size photos). */
    fun apply(b: Bitmap): Bitmap {
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(out.width)
        for (y in 0 until out.height) {
            out.getPixels(row, 0, out.width, 0, y, out.width, 1)
            apply(row)
            out.setPixels(row, 0, out.width, 0, y, out.width, 1)
        }
        return out
    }
}
