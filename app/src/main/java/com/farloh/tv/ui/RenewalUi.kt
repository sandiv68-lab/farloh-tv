package com.farloh.tv.ui

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.Renewal
import com.farloh.tv.data.Section
import com.farloh.tv.data.Xtream

/** Tells how a renewal (new server / username) or a Carry over went, once the new lists have loaded. */
object RenewalUi {

    private var showing = false

    fun showReports(a: BaseActivity) {
        if (showing) return
        val reports = Renewal.takeReports(a)
        if (reports.isEmpty()) return
        showNext(a, ArrayDeque(reports))
    }

    private fun showNext(a: BaseActivity, queue: ArrayDeque<Renewal.Report>) {
        val r = queue.removeFirstOrNull() ?: run { showing = false; return }
        if (a.isFinishing) { showing = false; return }
        val name = a.store.playlist(r.pid)?.name.orEmpty()
        val carry = r.mode == Renewal.MODE_CARRY
        val anyByName = r.sections.values.any { it.third }
        // A renewal that kept its numbers needs no message.
        if (!carry && !anyByName && r.lostEpisodes == 0) { showNext(a, queue); return }
        showing = true
        val lines = Section.values().joinToString("\n") { sec ->
            val (matched, total, byName) = r.sections[sec] ?: Triple(0, 0, false)
            "${sec.title}: " + when {
                total == 0 -> "nothing to move"
                !byName && !carry -> "unchanged ✓"
                else -> "$matched of $total" + if (byName) " (matched by name)" else ""
            }
        }
        val lost = (if (r.lostEpisodes > 0) "\n\nThe progress of ${r.lostEpisodes} episode(s) couldn't be moved." else "") +
            if (r.hiddenByName) "\n\nHidden groups (parental control) were matched by name" +
                (if (r.hiddenUnmatched > 0) " – ${r.hiddenUnmatched} couldn't be found" else "") +
                ". Please check Settings → Parental control → Hide & arrange groups." else ""
        val from = r.fromPid?.let { a.store.playlist(it) }?.takeIf { it.id == r.fromPid }
        val b = AlertDialog.Builder(a)
        if (carry) {
            b.setTitle("Carried over to $name")
            b.setMessage("From ${r.fromName ?: "the old playlist"}:\n\n$lines$lost" +
                if (from != null) "\n\nRemove the old playlist '${from.name}'?" else "")
            if (from != null) {
                b.setPositiveButton("Remove") { _, _ -> remove(a, from.id, from.name) }
                b.setNegativeButton("Keep", null)
            } else b.setPositiveButton("OK", null)
        } else {
            b.setTitle("$name: new server")
            b.setMessage("The new server numbers its channels and titles differently, so your favourites, history and " +
                "progress were matched by name:\n\n$lines$lost\n\nTV show episodes follow when you next open each show.")
            b.setPositiveButton("OK", null)
        }
        b.setOnDismissListener { showNext(a, queue) }
        b.show()
    }

    private fun remove(a: BaseActivity, pid: String, name: String) {
        val wasActive = a.store.activePlaylist?.id == pid
        Catalog.deleteFor(a, pid)
        val next = a.store.removePlaylist(pid)
        a.toast("$name removed")
        if (wasActive || next == null) {
            Catalog.clearMemory()
            Xtream.clearCache()
            val target = if (a.store.account == null) LoginActivity::class.java else HomeActivity::class.java
            a.startActivity(Intent(a, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            a.finish()
        } else if (a is SettingsActivity) a.recreate()
    }
}
