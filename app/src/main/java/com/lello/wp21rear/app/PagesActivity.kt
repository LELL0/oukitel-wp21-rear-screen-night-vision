package com.lello.wp21rear.app

import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lello.wp21rear.core.Prefs

/** Reorder (drag the handle) and enable/disable the rear pages. */
class PagesActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val items = ArrayList<Pair<String, Boolean>>()

    private val names = mapOf(
        "watch" to "Watch face (always on)", "music" to "Music", "camera" to "Camera", "tools" to "Tools",
        "compass" to "Compass", "level" to "Spirit level", "stopwatch" to "Stopwatch", "timer" to "Timer & alarm",
        "battery" to "Battery", "steps" to "Steps", "photos" to "Photo frame", "idle" to "GIF / video idle screen",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rear pages"
        prefs = Prefs(this)
        val listed = prefs.pages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (raw in listed) items.add(raw.removePrefix("-") to !raw.startsWith("-"))
        for (id in Prefs.ALL_PAGES) if (items.none { it.first == id }) items.add(id to false)

        val root = vbox(8)
        root.add(label("Drag ≡ to reorder. Swipe left/right on the rear screen moves through the enabled pages in this order.", 13f))
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = Adapter()
        rv.adapter = adapter
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(r: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition
                val to = b.bindingAdapterPosition
                items.add(to, items.removeAt(from))
                adapter.notifyItemMoved(from, to)
                save()
                return true
            }
            override fun onSwiped(v: RecyclerView.ViewHolder, d: Int) {}
            override fun isLongPressDragEnabled() = true
        })
        helper.attachToRecyclerView(rv)
        adapter.helper = helper
        root.add(rv, -1, 0, 1f)
        setContentView(root)
    }

    private fun save() {
        prefs.pages = items.joinToString(",") { (id, on) -> if (on || id == "watch") id else "-$id" }
    }

    private inner class VH(val row: LinearLayout, val handle: TextView, val check: CheckBox) : RecyclerView.ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        var helper: ItemTouchHelper? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = hbox().apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
            val handle = label("≡", 26f).apply { setPadding(dp(4), 0, dp(16), 0) }
            val check = CheckBox(this@PagesActivity).apply { textSize = 16f }
            row.add(handle, -2, -2)
            row.add(check, 0, -2, 1f)
            return VH(row, handle, check)
        }

        override fun getItemCount() = items.size

        @Suppress("ClickableViewAccessibility")
        override fun onBindViewHolder(h: VH, position: Int) {
            val (id, on) = items[position]
            h.check.setOnCheckedChangeListener(null)
            h.check.text = names[id] ?: id
            h.check.isChecked = on || id == "watch"
            h.check.isEnabled = id != "watch"
            h.check.setOnCheckedChangeListener { _, c ->
                val i = h.bindingAdapterPosition
                if (i >= 0) { items[i] = items[i].first to c; save() }
            }
            h.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) helper?.startDrag(h)
                false
            }
        }
    }
}
