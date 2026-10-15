package com.lello.wp21rear.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny helpers for the programmatic settings screens. */
fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.vbox(pad: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply { addView(child) }

fun Context.label(text: String, size: Float = 15f, bold: Boolean = false, color: Int? = null): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    if (bold) typeface = Typeface.DEFAULT_BOLD
    if (color != null) setTextColor(color)
    setPadding(0, dp(4), 0, dp(4))
}

fun Context.header(text: String): TextView = label(text, 13f, true, 0xFF4FC3F7.toInt()).apply {
    setPadding(0, dp(18), 0, dp(6))
    isAllCaps = true
}

fun Context.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Context.dangerButton(text: String, onClick: () -> Unit): Button = button(text, onClick).apply {
    setTextColor(Color.WHITE)
    textSize = 18f
    typeface = Typeface.DEFAULT_BOLD
    background = GradientDrawable().apply {
        setColor(0xFFD32F2F.toInt())
        cornerRadius = dp(14).toFloat()
    }
    setPadding(dp(16), dp(18), dp(16), dp(18))
}

fun ViewGroup.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
    val lp = if (this is LinearLayout) LinearLayout.LayoutParams(w, h, weight) else ViewGroup.LayoutParams(w, h)
    addView(v, lp)
    return v
}
