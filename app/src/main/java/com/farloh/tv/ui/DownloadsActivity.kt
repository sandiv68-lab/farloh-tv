package com.farloh.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.R
import com.farloh.tv.data.DownloadEntry
import com.farloh.tv.data.DownloadState
import com.farloh.tv.data.DownloadStatus
import com.farloh.tv.data.Downloads
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Launcher

/** Movies and episodes saved for offline viewing. Works without an internet connection. */
class DownloadsActivity : BaseActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var space: TextView
    private var statuses: Map<String, DownloadStatus> = emptyMap()
    private val handler = Handler(Looper.getMainLooper())

    private val adapter = SimpleAdapter<DownloadEntry>(R.layout.row_download, 1.02f) { v, e, _ -> bind(v, e) }

    private val poll = object : Runnable {
        override fun run() {
            reload()
            val active = statuses.values.any { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
            handler.postDelayed(this, if (active) 1500L else 5000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        space = findViewById(R.id.space)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter
        adapter.onClick = { e, _ -> open(e) }
        adapter.onLongClick = { e, _ -> options(e) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
        list.post { if (currentFocus == null || currentFocus?.id == R.id.back) list.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun reload() {
        val entries = Downloads.list(this).sortedByDescending { it.added }
        statuses = Downloads.status(this, entries)
        if (entries.map { it.key } == adapter.items.map { it.key }) {
            adapter.notifyItemRangeChanged(0, entries.size)
        } else {
            adapter.submit(entries)
        }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        space.text = "${Formatter.formatShortFileSize(this, Downloads.freeSpace(this))} free"
    }

    private fun bind(v: View, e: DownloadEntry) {
        v.findViewById<ImageView>(R.id.poster).loadImage(e.image)
        v.findViewById<TextView>(R.id.title).text = e.title
        v.findViewById<TextView>(R.id.show).text = e.showTitle ?: if (e.kind == Kind.MOVIE) "Movie" else ""
        val st = statuses[e.key] ?: DownloadStatus(DownloadState.MISSING, 0, 0, 0)
        val bar = v.findViewById<ProgressBar>(R.id.progress)
        val status = v.findViewById<TextView>(R.id.status)
        val size = { b: Long -> Formatter.formatShortFileSize(this, b) }
        when (st.state) {
            DownloadState.DONE -> {
                bar.visibility = View.GONE
                status.text = "✓ Downloaded • ${size(st.total)}"
            }
            DownloadState.RUNNING -> {
                bar.visibility = View.VISIBLE; bar.progress = st.percent
                status.text = if (st.total > 0) "Downloading ${st.percent}% • ${size(st.bytes)} of ${size(st.total)}"
                              else "Downloading • ${size(st.bytes)}"
            }
            DownloadState.QUEUED -> { bar.visibility = View.VISIBLE; bar.progress = 0; status.text = "Waiting to start…" }
            DownloadState.PAUSED -> {
                bar.visibility = View.VISIBLE; bar.progress = st.percent
                status.text = "Paused (waiting for network or storage) • ${st.percent}%"
            }
            DownloadState.FAILED -> { bar.visibility = View.GONE; status.text = "Failed — hold OK / long-press to retry" }
            else -> { bar.visibility = View.GONE; status.text = "File missing — hold OK / long-press to download again" }
        }
    }

    private fun open(e: DownloadEntry) {
        if (statuses[e.key]?.state == DownloadState.DONE) {
            val p = Downloads.playable(this, e) ?: run { toast("File not found"); return }
            Launcher.play(this, listOf(p), 0, external = false)
        } else {
            options(e)
        }
    }

    private fun options(e: DownloadEntry) {
        val done = statuses[e.key]?.state == DownloadState.DONE
        val failed = statuses[e.key]?.state.let { it == DownloadState.FAILED || it == DownloadState.MISSING }
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (done) {
            opts += "Play" to { open(e) }
            opts += "Open in external" to {
                Downloads.playable(this, e)?.let { Launcher.play(this, listOf(it), 0, external = true) }
                Unit
            }
        }
        if (failed) opts += "Download again" to {
            val err = Downloads.retry(this, e)
            toast(err ?: "Download restarted"); reload()
        }
        opts += (if (done) "Delete download" else "Cancel download") to {
            Dialogs.confirm(this, "Delete ${e.title}?", "The downloaded file will be removed from this device.", "Delete") {
                Downloads.delete(this, e); reload()
            }
        }
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            val holder = currentFocus?.let { list.findContainingViewHolder(it) }
            val pos = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (pos != RecyclerView.NO_POSITION && pos < adapter.items.size) { options(adapter.items[pos]); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
