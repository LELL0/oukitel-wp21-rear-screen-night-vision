package com.lello.wp21rear.pages

import android.graphics.Canvas
import com.lello.wp21rear.ui.Draw
import com.lello.wp21rear.ui.Overlay
import com.lello.wp21rear.ui.RearUi

/**
 * Shown on the rear while the calibration screen is open in the app.
 * mode "orient": arrow + text, the user picks rotation/mirror until it reads correctly.
 * mode "colors": red/green/blue bands with labels, to detect swapped bytes.
 * mode "touch": a target dot at [target]; the app records the raw touches.
 */
class CalibrationOverlay(ui: RearUi) : Overlay(ui) {
    override val id = "calibration"
    override val from = From.CENTER
    override val dismissable = false
    override val keepAwake = true

    @Volatile var mode = "orient"
    @Volatile var target: Pair<Float, Float>? = null
    @Volatile var message = ""

    override fun draw(c: Canvas, now: Long) {
        c.drawColor(0xFF000000.toInt())
        when (mode) {
            "orient" -> {
                Draw.fill.color = Draw.ACCENT
                val p = android.graphics.Path().apply {
                    moveTo(Draw.C, 40f); lineTo(Draw.C - 50f, 110f); lineTo(Draw.C - 18f, 110f); lineTo(Draw.C - 18f, 170f)
                    lineTo(Draw.C + 18f, 170f); lineTo(Draw.C + 18f, 110f); lineTo(Draw.C + 50f, 110f); close()
                }
                c.drawPath(p, Draw.fill)
                Draw.textC(c, "TOP", Draw.C, 205f, 34f, Draw.WHITE, Draw.BOLD)
                Draw.textC(c, "LEFT", 60f, 260f, 22f, Draw.AMBER, Draw.MED)
                Draw.textC(c, "RIGHT", 296f, 260f, 22f, Draw.GREEN, Draw.MED)
            }
            "colors" -> {
                val bands = listOf(0xFFFF0000.toInt() to "RED", 0xFF00FF00.toInt() to "GREEN", 0xFF0000FF.toInt() to "BLUE")
                for ((i, b) in bands.withIndex()) {
                    Draw.fill.color = b.first
                    c.drawRect(0f, 60f + i * 80f, Draw.S, 140f + i * 80f, Draw.fill)
                    Draw.textC(c, b.second, Draw.C, 100f + i * 80f, 28f, if (i == 1) 0xFF000000.toInt() else Draw.WHITE, Draw.BOLD)
                }
            }
            "touch" -> {
                target?.let { (x, y) ->
                    Draw.ring(c, x, y, 26f, 3f, Draw.WHITE)
                    Draw.circle(c, x, y, 8f, Draw.RED)
                }
            }
        }
        if (message.isNotEmpty()) Draw.textC(c, message, Draw.C, if (mode == "touch") Draw.C else 318f, 16f, Draw.DIM, Draw.REG, 280f)
    }

    override fun nextChangeIn(now: Long) = 500L
    override fun claimDrag(x0: Float, y0: Float, dx: Float, dy: Float) = true
}
