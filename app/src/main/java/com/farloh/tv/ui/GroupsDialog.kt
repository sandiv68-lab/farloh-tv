package com.farloh.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.R
import com.farloh.tv.data.Category

/**
 * Settings → Parental control → Hide & arrange groups.
 * Each group: ✓ = shown (untick to hide), ▲ ▼ = move it up or down. Save keeps both.
 */
object GroupsDialog {

    private class Row(val cat: Category, var shown: Boolean)

    fun show(
        activity: BaseActivity,
        title: String,
        categories: List<Category>,
        hidden: Set<String>,
        onSave: (hidden: Set<String>, order: List<String>) -> Unit,
    ) {
        val d = activity.resources.displayMetrics.density
        val rows = categories.map { Row(it, it.id !in hidden) }.toMutableList()

        lateinit var rv: RecyclerView
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.row_group, parent, false)
                val holder = object : RecyclerView.ViewHolder(v) {}
                val check = v.findViewById<CheckBox>(R.id.gShown)
                check.setOnClickListener {
                    val p = holder.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) rows[p].shown = check.isChecked
                }
                fun move(by: Int) {
                    val from = holder.bindingAdapterPosition
                    val to = from + by
                    if (from == RecyclerView.NO_POSITION || to !in rows.indices) return
                    rows.add(to, rows.removeAt(from))
                    notifyItemMoved(from, to) // the row (and the remote's selection on its arrow) moves with it
                    rv.scrollToPosition(to)
                }
                v.findViewById<View>(R.id.gUp).setOnClickListener { move(-1) }
                v.findViewById<View>(R.id.gDown).setOnClickListener { move(1) }
                return holder
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = rows[position]
                holder.itemView.findViewById<CheckBox>(R.id.gShown).apply {
                    text = row.cat.name
                    isChecked = row.shown
                }
                holder.itemView.findViewById<View>(R.id.gUp).alpha = if (position == 0) 0.3f else 1f
                holder.itemView.findViewById<View>(R.id.gDown).alpha = if (position == rows.size - 1) 0.3f else 1f
            }
        }

        rv = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
            this.adapter = adapter
            val p = (12 * d).toInt()
            setPadding(p, (4 * d).toInt(), p, (4 * d).toInt())
            clipToPadding = false
        }
        val hint = TextView(activity).apply {
            text = "✓ = shown   •   untick to hide   •   ▲ ▼ to move"
            setTextColor(ContextCompat.getColor(activity, R.color.text2))
            textSize = 13f
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (4 * d).toInt())
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                onSave(rows.filter { !it.shown }.map { it.cat.id }.toSet(), rows.map { it.cat.id })
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Show all", null)
            .create()
        dialog.setOnShowListener {
            // "Show all" / "Hide all" without closing the box.
            val toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            fun label() { toggle.text = if (rows.all { it.shown }) "Hide all" else "Show all" }
            label()
            toggle.setOnClickListener {
                val show = !rows.all { it.shown }
                rows.forEach { it.shown = show }
                adapter.notifyDataSetChanged()
                label()
            }
            rv.post { rv.layoutManager?.findViewByPosition(0)?.findViewById<View>(R.id.gShown)?.requestFocus() }
        }
        dialog.show()
        // A roomier box on TVs.
        if (activity.screenWidthDp >= 720) {
            dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.6f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
