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
import com.farloh.tv.player.Playable
import com.farloh.tv.data.RecFile
import com.farloh.tv.data.RecSchedule
import com.farloh.tv.data.Recordings
import com.farloh.tv.record.RecordScheduler
import android.net.Uri

/**
 * Movies and episodes saved for offline viewing (works without an internet connection), and Live TV
 * recordings: set up, being made, and finished.
 */
class DownloadsActivity : BaseActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_RECORDINGS = "recordings"
    }

    /** One line on the Recordings tab. */
    private sealed class RecRow {
        object New : RecRow()
        class Scheduled(val s: RecSchedule, val next: Long?) : RecRow()
        class File(val f: RecFile) : RecRow()
    }

    private var showRecordings = false
    private val recAdapter = SimpleAdapter<RecRow>(R.layout.row_download, 1.02f) { v, r, _ -> bindRec(v, r) }

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var space: TextView
    private var statuses: Map<String, DownloadStatus> = emptyMap()
    private val handler = Handler(Looper.getMainLooper())

    private val adapter = SimpleAdapter<DownloadEntry>(R.layout.row_download, 1.02f) { v, e, _ -> bind(v, e) }

    private val poll = object : Runnable {
        override fun run() {
            reload()
            val active = showRecordings || statuses.values.any { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
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
        recAdapter.onClick = { r, _ -> openRec(r) }
        recAdapter.onLongClick = { r, _ -> recOptions(r) }
        findViewById<View>(R.id.tabDownloads).setOnClickListener { setTab(false) }
        findViewById<View>(R.id.tabRecordings).setOnClickListener { setTab(true) }
        setTab(intent.getStringExtra(EXTRA_TAB) == TAB_RECORDINGS)
    }

    private fun setTab(recordings: Boolean) {
        showRecordings = recordings
        findViewById<View>(R.id.tabDownloads).isSelected = !recordings
        findViewById<View>(R.id.tabRecordings).isSelected = recordings
        list.adapter = if (recordings) recAdapter else adapter
        reload()
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
        if (showRecordings) { reloadRecordings(); return }
        empty.text = "No downloads yet.\n\nOpen a movie and press Download, or hold OK on an episode."
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

    // ---------------- Recordings ----------------

    private fun reloadRecordings() {
        val rows = ArrayList<RecRow>()
        rows += RecRow.New
        val files = Recordings.files(this)
        files.filter { it.state == "recording" }.sortedBy { it.end }.forEach { rows += RecRow.File(it) }
        Recordings.schedules(this)
            .map { it to RecordScheduler.upcoming(this, it) }
            .filter { (s, next) -> next != null && files.none { f -> f.scheduleId == s.id && f.occurrence == next && f.state == "recording" } }
            .sortedBy { it.second }
            .forEach { (s, next) -> rows += RecRow.Scheduled(s, next) }
        files.filter { it.state == "done" || it.state == "failed" }.sortedByDescending { it.start }.forEach { rows += RecRow.File(it) }
        val sameRows = rows.size == recAdapter.items.size && rows.zip(recAdapter.items).all { (a, b) -> key(a) == key(b) }
        if (sameRows) recAdapter.notifyItemRangeChanged(0, rows.size) else recAdapter.submit(rows)
        empty.visibility = View.GONE
        space.text = Recordings.folder(this)?.let { "${Formatter.formatShortFileSize(this, Recordings.freeSpace(it))} free" } ?: ""
    }

    private fun key(r: RecRow) = when (r) {
        RecRow.New -> "new"
        is RecRow.Scheduled -> "s:${r.s.id}:${r.next}"
        is RecRow.File -> "f:${r.f.id}:${r.f.state}"
    }

    private fun bindRec(v: View, r: RecRow) {
        val poster = v.findViewById<ImageView>(R.id.poster)
        val title = v.findViewById<TextView>(R.id.title)
        val show = v.findViewById<TextView>(R.id.show)
        val status = v.findViewById<TextView>(R.id.status)
        val bar = v.findViewById<ProgressBar>(R.id.progress)
        bar.visibility = View.GONE
        when (r) {
            RecRow.New -> {
                poster.setImageResource(R.drawable.ic_record)
                title.text = "＋  New recording…"
                show.text = "Choose a channel, the day and the time (once, every day or every week)"
                status.text = "Or: OK on a programme in the TV guide → Record, or Record in the player's Settings"
            }
            is RecRow.Scheduled -> {
                val s = r.s
                poster.loadImage(s.logo)
                title.text = s.title
                show.text = s.channelName
                val next = r.next
                status.text = listOfNotNull(
                    next?.let { "⏰ " + RecordUi.whenLabel(this, it, it + s.durationMs) },
                    if (s.repeats) RecordUi.repeatLabel(s.days) else null,
                ).joinToString("  •  ")
            }
            is RecRow.File -> {
                val f = r.f
                poster.loadImage(f.logo)
                title.text = f.title
                show.text = "${f.channelName}  •  ${RecordUi.whenLabel(this, f.start, f.end)}"
                val size = java.io.File(f.path).length()
                val sz = Formatter.formatShortFileSize(this, size)
                status.text = when (f.state) {
                    "recording" -> {
                        bar.visibility = View.VISIBLE
                        val total = (f.end - f.start).coerceAtLeast(1)
                        bar.progress = (((System.currentTimeMillis() - f.start) * 100) / total).toInt().coerceIn(0, 100)
                        "● Recording  •  $sz  •  until ${Fmt.time(this, f.end)}"
                    }
                    "done" -> "✓ Recorded  •  $sz" + (if (f.notes.isNotBlank()) "  •  ${f.notes}" else "")
                    else -> "✗ Not recorded" + (if (f.notes.isNotBlank()) "  •  ${f.notes}" else "")
                }
            }
        }
    }

    private fun playRecording(f: RecFile, external: Boolean) {
        val file = java.io.File(f.path)
        if (!file.exists()) { toast("File not found"); return }
        val p = Playable(Kind.MOVIE, "rec_${f.id}", f.title, Uri.fromFile(file).toString(), f.logo,
            subtitle = f.channelName, searchTitle = f.title, playlistId = f.playlistId)
        Launcher.play(this, listOf(p), 0, external = external)
    }

    private fun openRec(r: RecRow) {
        when (r) {
            RecRow.New -> RecordUi.manual(this) { reload() }
            is RecRow.Scheduled -> recOptions(r)
            is RecRow.File -> if (r.f.state == "done") playRecording(r.f, external = false) else recOptions(r)
        }
    }

    private fun recOptions(r: RecRow) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        val title: String
        when (r) {
            RecRow.New -> { RecordUi.manual(this) { reload() }; return }
            is RecRow.Scheduled -> {
                val s = r.s
                title = s.title
                opts += "Change…" to { RecordUi.editSchedule(this, s) { reload() } }
                if (s.repeats && r.next != null) opts += "Skip the next one only" to {
                    // Marks that showing as handled, so the following one is next.
                    Recordings.saveFile(this, RecFile(scheduleId = s.id, occurrence = r.next, channelId = s.channelId,
                        channelName = s.channelName, logo = s.logo, playlistId = s.playlistId, title = s.title,
                        start = r.next, end = r.next + s.durationMs, path = "", state = "skipped"))
                    RecordScheduler.reschedule(this); reload()
                }
                opts += (if (s.repeats) "Delete this repeating recording" else "Cancel this recording") to {
                    Recordings.removeSchedule(this, s.id); RecordScheduler.reschedule(this); reload(); toast("Cancelled")
                }
            }
            is RecRow.File -> {
                val f = r.f
                title = f.title
                if (f.state == "recording") {
                    opts += "Watch from the start" to { playRecording(f, external = false) }
                    opts += "■  Stop recording" to { RecordScheduler.stop(this, f.id); toast("Stopping…"); handler.postDelayed({ reload() }, 1500) }
                } else if (f.state == "done") {
                    opts += "Play" to { playRecording(f, external = false) }
                    opts += "Open in external" to { playRecording(f, external = true) }
                }
                if (f.state != "recording") opts += "Delete" to {
                    Dialogs.confirm(this, "Delete ${f.title}?", "The recording will be removed from this device.", "Delete") {
                        Recordings.deleteFile(this, f); reload()
                    }
                }
            }
        }
        if (opts.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            val holder = currentFocus?.let { list.findContainingViewHolder(it) }
            val pos = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (showRecordings && pos != RecyclerView.NO_POSITION && pos < recAdapter.items.size) { recOptions(recAdapter.items[pos]); return true }
            if (!showRecordings && pos != RecyclerView.NO_POSITION && pos < adapter.items.size) { options(adapter.items[pos]); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
