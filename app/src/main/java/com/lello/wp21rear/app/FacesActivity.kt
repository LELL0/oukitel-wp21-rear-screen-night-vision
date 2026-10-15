package com.lello.wp21rear.app

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.pages.Face

/** Pick the watch face and edit its colours and photo background, with the live mirror on top. */
class FacesActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var mirror: MirrorView
    private lateinit var editor: LinearLayout

    private val palette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF9AA0A6.toInt(), 0xFF4FC3F7.toInt(), 0xFF2979FF.toInt(), 0xFF66BB6A.toInt(),
        0xFFC6FF00.toInt(), 0xFFFFB300.toInt(), 0xFFFF7043.toInt(), 0xFFEF5350.toInt(), 0xFFEC407A.toInt(),
        0xFFAB47BC.toInt(), 0xFF26A69A.toInt(),
    )

    private val pickBg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.sp.edit().putString("face_${prefs.face}_bg", uri.toString()).apply()
            buildEditor()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Watch faces"
        prefs = Prefs(this)
        val root = vbox()
        mirror = MirrorView(this)
        root.add(mirror)
        root.add(button("Show on the rear screen") {
            Rear.service?.wakePanel()
            if (Rear.running) Rear.ui.post { Rear.ui.goTo("watch") }
        })
        root.add(header("Face"))
        val group = RadioGroup(this)
        for ((i, f) in Face.ALL.withIndex()) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = f.name
                isChecked = i == prefs.face
                setOnClickListener {
                    prefs.face = i
                    buildEditor()
                    if (Rear.running) Rear.ui.invalidate()
                }
            })
        }
        root.add(group)
        editor = vbox(0)
        root.add(editor)
        root.add(label("Tip: long-press the watch face on the rear screen to switch faces there.", 12f))
        setContentView(scroll(root))
        buildEditor()
    }

    private fun buildEditor() {
        editor.removeAllViews()
        val i = prefs.face
        val f = Face.ALL[i]
        editor.add(header("Main colour"))
        editor.add(swatches(prefs.faceColor(i, f.defColor)) { c -> save("face_${i}_color", c) })
        editor.add(header("Accent colour"))
        editor.add(swatches(prefs.faceAccent(i, f.defAccent)) { c -> save("face_${i}_accent", c) })
        editor.add(header("Background photo"))
        val row = hbox()
        row.add(button(if (prefs.faceBgUri(i) == null) "Choose photo…" else "Change photo…") { pickBg.launch(arrayOf("image/*")) }, 0, -2, 1f)
        if (prefs.faceBgUri(i) != null) {
            row.add(button("Remove") {
                prefs.sp.edit().remove("face_${i}_bg").apply()
                buildEditor()
            }, 0, -2, 1f)
        }
        editor.add(row)
        editor.add(button("Reset colours") {
            prefs.sp.edit().remove("face_${i}_color").remove("face_${i}_accent").apply()
            buildEditor()
        })
    }

    private fun save(key: String, c: Int) {
        prefs.sp.edit().putInt(key, c).apply()
        buildEditor()
    }

    private fun swatches(selected: Int, onPick: (Int) -> Unit): View {
        val wrap = vbox(0)
        var row = hbox()
        for ((k, c) in palette.withIndex()) {
            if (k % 6 == 0 && k > 0) { wrap.add(row); row = hbox() }
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    if (c == selected) setStroke(dp(4), 0xFF4FC3F7.toInt()) else setStroke(dp(1), 0xFF555555.toInt())
                }
                setOnClickListener { onPick(c) }
            }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            row.addView(v, lp)
        }
        wrap.add(row)
        return wrap
    }
}
