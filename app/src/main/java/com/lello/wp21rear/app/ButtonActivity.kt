package com.lello.wp21rear.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lello.wp21rear.Rear
import com.lello.wp21rear.core.Prefs
import com.lello.wp21rear.service.ButtonHandler

/** Actions for the programmable side key: single, double, triple and long press. */
class ButtonActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val appLabels = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Side button"
        prefs = Prefs(this)
        val root = vbox()
        root.add(label("The programmable key on the side of the WP21 (KEY_F1). The rear screen reads it through the root daemon, so it works with the main screen off.", 13f))
        val ids = ButtonHandler.ACTIONS.keys.toList()
        val names = ButtonHandler.ACTIONS.values.toList()
        for (kind in Prefs.BUTTON_KINDS) {
            root.add(header(when (kind) {
                "single" -> "Single press"
                "double" -> "Double press"
                "triple" -> "Triple press"
                else -> "Long press"
            }))
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            sp.setSelection(ids.indexOf(prefs.buttonAction(kind)).coerceAtLeast(0), false)
            val appLabel = label("", 13f)
            appLabels[kind] = appLabel
            updateAppLabel(kind)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val action = ids[pos]
                    if (action == prefs.buttonAction(kind) && action != "launch_app") return
                    prefs.sp.edit().putString("btn_$kind", action).apply()
                    if (action == "launch_app" && prefs.buttonApp(kind) == null) pickApp(kind)
                    updateAppLabel(kind)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            root.add(sp)
            root.add(appLabel)
            val row = hbox()
            row.add(button("Choose app…") { pickApp(kind) }, 0, -2, 1f)
            row.add(button("Test") { Rear.service?.runButtonAction(prefs.buttonAction(kind), prefs.buttonApp(kind)) }, 0, -2, 1f)
            root.add(row)
        }
        setContentView(scroll(root))
    }

    private fun updateAppLabel(kind: String) {
        val l = appLabels[kind] ?: return
        val app = prefs.buttonApp(kind)
        l.visibility = if (prefs.buttonAction(kind) == "launch_app") View.VISIBLE else View.GONE
        l.text = if (app == null) "No app chosen" else "Opens: " + appName(app)
    }

    private fun appName(flat: String): String = try {
        val cn = ComponentName.unflattenFromString(flat)!!
        packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString()
    } catch (_: Exception) {
        flat
    }

    private fun pickApp(kind: String) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString() to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Open which app?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                prefs.sp.edit().putString("btn_${kind}_app", apps[i].first).putString("btn_$kind", "launch_app").apply()
                recreate()
            }
            .show()
    }
}
