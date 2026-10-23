package com.farloh.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A simple built-in file browser for TVs that have no system file picker.
 * Shows the device's storage and USB drives, folders first, and only files with the wanted endings.
 * Returns the chosen file as a file:// Uri.
 */
class FileBrowserActivity : BaseActivity() {

    companion object {
        private const val EXTRA_EXTS = "exts"
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, extensions: List<String>, title: String): Intent =
            Intent(ctx, FileBrowserActivity::class.java)
                .putExtra(EXTRA_EXTS, extensions.toTypedArray())
                .putExtra(EXTRA_TITLE, title)
    }

    /** [up] = the "go up" row: [file] is where it goes (null = the list of storage places). */
    private class Entry(val file: File?, val label: String, val detail: String, val isDir: Boolean, val up: Boolean = false)

    private lateinit var pathView: TextView
    private lateinit var empty: TextView
    private lateinit var listRv: RecyclerView
    private var exts: List<String> = emptyList()
    /** null = the list of storage places. */
    private var current: File? = null
    private val positions = HashMap<String, Int>()

    private val adapter = SimpleAdapter<Entry>(R.layout.row_category, 1.02f) { v, e, _ ->
        v.findViewById<TextView>(R.id.name).text = when {
            e.up -> "▲  " + e.label
            e.isDir -> "▸  " + e.label
            else -> "•  " + e.label
        }
        v.findViewById<TextView>(R.id.count).text = e.detail
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)
        exts = intent.getStringArrayExtra(EXTRA_EXTS)?.map { it.lowercase() }.orEmpty()
        findViewById<TextView>(R.id.title).text = intent.getStringExtra(EXTRA_TITLE) ?: "Choose a file"
        pathView = findViewById(R.id.path)
        empty = findViewById(R.id.empty)
        listRv = findViewById(R.id.list)
        listRv.layoutManager = LinearLayoutManager(this)
        listRv.adapter = adapter
        listRv.itemAnimator = null
        findViewById<View>(R.id.back).setOnClickListener { goUp() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goUp()
        })
        adapter.onClick = { e, pos ->
            when {
                e.up -> goUp()
                e.isDir -> { current?.let { positions[it.path] = pos }; open(e.file) }
                else -> {
                    setResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(e.file!!)))
                    finish()
                }
            }
        }
        findViewById<TextView>(R.id.hint).text = "Press a folder (▸) to open it. \"▲\" or Back goes up / to other places (internal storage, USB).\n" +
            "Tip: you can also open the file from your file manager app and choose Farloh TV."
        // Start in Download if it's readable, otherwise at the list of storage places.
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        open(if (dl.canRead() && dl.list() != null) dl else null)
    }

    /** Internal storage plus any SD card / USB drive (found via the app's own folder on each). */
    private fun roots(): List<Entry> {
        val out = ArrayList<Entry>()
        val main = Environment.getExternalStorageDirectory()
        out += Entry(main, "Internal storage", "", true)
        for (dir in getExternalFilesDirs(null).filterNotNull()) {
            val root = dir.path.substringBefore("/Android/data").let(::File)
            if (root.path != main.path && out.none { it.file?.path == root.path }) out += Entry(root, "USB / SD card (${root.name})", "", true)
        }
        // Mount points that show up without an app folder (some TV boxes).
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }
            ?.forEach { r -> if (out.none { it.file?.path == r.path }) out += Entry(r, "USB / SD card (${r.name})", "", true) }
        File("/mnt/media_rw").listFiles()?.filter { it.isDirectory && it.canRead() }
            ?.forEach { r -> if (out.none { it.file?.name == r.name }) out += Entry(r, "USB drive (${r.name})", "", true) }
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        out.add(0, Entry(dl, "Download folder", "", true))
        return out
    }

    private fun upTarget(dir: File): File? {
        val isRoot = roots().any { it.file?.path == dir.path }
        val parent = dir.parentFile
        return if (isRoot || parent == null || parent.listFiles() == null) null else parent
    }

    private fun open(dir: File?) {
        current = dir
        pathView.text = dir?.path ?: "Choose where to look"
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                if (dir == null) roots() else {
                    val files = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
                    val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                        .map { Entry(it, it.name, "", true) }
                    val matches = files.filter { f -> f.isFile && (exts.isEmpty() || f.extension.lowercase() in exts) }
                        .sortedByDescending { it.lastModified() }
                        .map { Entry(it, it.name, Fmt.date(it.lastModified()), false) }
                    val up = upTarget(dir).let { t ->
                        Entry(t, if (t == null) "Other places (internal storage, USB drives)" else "Up to ${t.name.ifBlank { t.path }}", "", true, up = true)
                    }
                    listOf(up) + matches + dirs // the files you're looking for first
                }
            }
            if (current != dir) return@launch
            adapter.submit(entries)
            val back = dir?.let { positions[it.path] } ?: 0
            val nothing = entries.none { !it.up }
            empty.visibility = if (nothing) View.VISIBLE else View.GONE
            empty.text = if (dir != null && dir.listFiles() == null)
                "Can't look inside this folder.\nGive Farloh TV file access (Settings → Device), or open the file from your file manager app."
            else "No folders or " + exts.joinToString(" / ") { ".$it" } + " files here."
            listRv.scrollToPosition(0)
            listRv.post {
                (listRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(back.coerceAtMost((entries.size - 1).coerceAtLeast(0)), 0)
                listRv.post { listRv.layoutManager?.findViewByPosition(back)?.requestFocus() ?: listRv.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun goUp() {
        val dir = current ?: run { finish(); return }
        open(upTarget(dir))
    }

}
