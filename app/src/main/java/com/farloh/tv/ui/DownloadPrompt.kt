package com.farloh.tv.ui

import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.data.Downloads
import com.farloh.tv.net.Net
import com.farloh.tv.player.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Checks how big a download is and asks before starting it. */
object DownloadPrompt {

    fun start(activity: BaseActivity, items: List<Playable>, title: String, onStarted: () -> Unit = {}) {
        val todo = items.filter { Downloads.find(activity, it.resumeKey) == null }
        if (todo.isEmpty()) { activity.toast("Already in Downloads"); return }

        val checking = AlertDialog.Builder(activity)
            .setTitle("Checking size…")
            .setMessage(if (todo.size == 1) "Asking the server how big this file is." else "Checking ${todo.size} episodes.")
            .setNegativeButton("Cancel", null)
            .create()
        val job = activity.lifecycleScope.launch {
            val sizes: List<Long?> = withContext(Dispatchers.IO) {
                // A few at a time so the provider isn't flooded with connections.
                todo.chunked(3).flatMap { chunk ->
                    coroutineScope { chunk.map { p -> async { Net.contentLength(p.url) } }.awaitAll() }
                }
            }
            checking.dismiss()
            confirm(activity, todo, sizes, title, onStarted)
        }
        checking.setOnDismissListener { if (job.isActive) job.cancel() }
        checking.show()
    }

    private fun confirm(activity: BaseActivity, todo: List<Playable>, sizes: List<Long?>, title: String, onStarted: () -> Unit) {
        fun fmt(b: Long) = Formatter.formatShortFileSize(activity, b)
        val known = sizes.filterNotNull()
        val unknown = sizes.size - known.size
        val total = known.sum()
        val free = Downloads.freeSpace(activity)
        val enough = total < free

        val message = buildString {
            if (todo.size == 1) {
                append(if (known.isNotEmpty()) "File size: ${fmt(total)}" else "File size: unknown (the server didn't say)")
            } else {
                append("${todo.size} episodes")
                if (known.isNotEmpty()) append(" • about ${fmt(total)}")
                if (unknown > 0) append(if (known.isEmpty()) " • size unknown" else " (+$unknown with unknown size)")
            }
            append("\n\nFree space on this device: ${fmt(free)}")
            if (!enough) append("\n\nNot enough free space. Delete some downloads or choose fewer episodes.")
            else if (known.isNotEmpty() && free - total < 1024L * 1024 * 1024) append("\n\nThis will leave less than 1 GB free.")
        }

        val b = AlertDialog.Builder(activity).setTitle("Download “$title”?").setMessage(message)
        if (enough) {
            b.setPositiveButton("Download") { _, _ ->
                var started = 0
                var lastError: String? = null
                for (p in todo) {
                    val err = Downloads.start(activity, p)
                    if (err == null) started++ else lastError = err
                }
                activity.toast(
                    when {
                        started == 1 -> "Downloading. See progress in Downloads."
                        started > 1 -> "Downloading $started episodes. See progress in Downloads."
                        else -> lastError ?: "Couldn't start the download."
                    }, long = true
                )
                onStarted()
            }
            b.setNegativeButton("Cancel", null)
        } else {
            b.setPositiveButton("OK", null)
        }
        b.show()
    }
}
