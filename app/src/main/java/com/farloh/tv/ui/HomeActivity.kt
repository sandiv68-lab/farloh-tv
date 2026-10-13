package com.farloh.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.R
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.Section
import com.farloh.tv.net.Net
import kotlinx.coroutines.launch

class HomeActivity : BaseActivity() {

    private class Tile(val root: View) {
        val card: View = root.findViewById(R.id.card)
        val icon: ImageView = root.findViewById(R.id.icon)
        val title: TextView = root.findViewById(R.id.title)
        val count: TextView = root.findViewById(R.id.count)
        val updated: TextView = root.findViewById(R.id.updated)
        val refresh: Button = root.findViewById(R.id.refresh)
        val spinner: ProgressBar = root.findViewById(R.id.spinner)
    }

    private val tiles = HashMap<Section, Tile>()
    private val busy = HashSet<Section>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requireAccount() == null) return
        setContentView(R.layout.activity_home)

        tiles[Section.LIVE] = Tile(findViewById(R.id.tileLive))
        tiles[Section.MOVIES] = Tile(findViewById(R.id.tileMovies))
        tiles[Section.SERIES] = Tile(findViewById(R.id.tileSeries))

        val icons = mapOf(Section.LIVE to R.drawable.ic_live, Section.MOVIES to R.drawable.ic_movie, Section.SERIES to R.drawable.ic_series)
        for ((section, tile) in tiles) {
            tile.icon.setImageResource(icons.getValue(section))
            tile.title.text = section.title
            tile.card.setOnClickListener { open(section) }
            tile.card.setOnFocusChangeListener { v, f ->
                v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(140).start()
            }
            tile.refresh.text = "Refresh ${section.title}"
            tile.refresh.setOnClickListener { refresh(section) }
        }

        // Stack the tiles vertically on narrow phone screens.
        if (screenWidthDp < 600) {
            val container = findViewById<LinearLayout>(R.id.tiles)
            container.orientation = LinearLayout.VERTICAL
            for (tile in tiles.values) {
                tile.root.layoutParams = (tile.root.layoutParams as LinearLayout.LayoutParams).apply {
                    width = LinearLayout.LayoutParams.MATCH_PARENT; height = 0; weight = 1f
                }
                tile.icon.layoutParams = tile.icon.layoutParams.apply { width = dp(36); height = dp(36) }
                tile.title.textSize = 20f
            }
        }

        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        showAccount()
        tiles.getValue(Section.LIVE).card.requestFocus()

        if (!store.initialLoadDone) firstLoad()
    }

    override fun onResume() {
        super.onResume()
        if (tiles.isNotEmpty()) Section.values().forEach { updateTile(it) }
    }

    private fun showAccount() {
        val info = store.userInfo
        val exp = info?.optString("exp_date")?.toLongOrNull()
        val expiry = if (exp != null && exp > 0) "Expires ${Fmt.date(exp * 1000)}" else "No expiry date"
        val cons = info?.optString("max_connections")?.takeIf { it.isNotBlank() && it != "null" }
        findViewById<TextView>(R.id.account).text =
            listOfNotNull(store.account?.username, expiry, cons?.let { "$it connection(s)" }).joinToString("  •  ")
    }

    private fun updateTile(section: Section) {
        val tile = tiles[section] ?: return
        val n = store.itemCount(section)
        tile.count.text = when {
            n < 0 -> "Not loaded yet"
            section == Section.LIVE -> Fmt.count(n, "channel", "channels")
            section == Section.MOVIES -> Fmt.count(n, "movie", "movies")
            else -> Fmt.count(n, "show", "shows")
        }
        tile.updated.text = Fmt.ago(Catalog.lastUpdated(this, section))
        val isBusy = section in busy
        tile.spinner.visibility = if (isBusy) View.VISIBLE else View.GONE
        tile.refresh.isEnabled = !isBusy
        if (isBusy) tile.updated.text = "Refreshing…"
    }

    private fun open(section: Section) {
        startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SECTION, section.name))
    }

    private fun refresh(section: Section, quiet: Boolean = false) {
        if (section in busy) return
        busy.add(section)
        updateTile(section)
        lifecycleScope.launch {
            try {
                val data = Catalog.refresh(this@HomeActivity, section)
                if (!quiet) toast("${section.title} refreshed: ${data.items.size} items")
            } catch (e: Exception) {
                toast("${section.title}: ${Net.friendlyError(e)}", long = true)
            } finally {
                busy.remove(section)
                updateTile(section)
            }
        }
    }

    /** After signing in, fetch all three playlists once. After that, only manual refresh. */
    private fun firstLoad() {
        Section.values().forEach { busy.add(it); updateTile(it) }
        lifecycleScope.launch {
            var allOk = true
            for (section in Section.values()) {
                try {
                    Catalog.refresh(this@HomeActivity, section)
                } catch (e: Exception) {
                    allOk = false
                    toast("${section.title}: ${Net.friendlyError(e)}", long = true)
                } finally {
                    busy.remove(section)
                    updateTile(section)
                }
            }
            if (allOk) store.initialLoadDone = true
        }
    }

    private fun showSettings() {
        val format = if (store.liveFormat == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)"
        val player = if (store.externalByDefault) "External (MX Player / VLC)" else "Built-in player"
        val options = arrayOf(
            "Live TV stream format: $format",
            "Default player: $player",
            "Account info",
            "Clear saved playlists",
            "Log out",
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> chooseFormat()
                    1 -> choosePlayer()
                    2 -> showAccountInfo()
                    3 -> confirm("Clear saved playlists?", "Live TV, Movies and TV Shows will download again next time you open them.") {
                        Catalog.clear(this)
                        Section.values().forEach { store.setItemCount(it, -1); updateTile(it) }
                    }
                    4 -> confirm("Log out?", "Your saved login and playlists will be removed from this device.") { logout() }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseFormat() {
        val values = arrayOf("ts", "m3u8")
        AlertDialog.Builder(this)
            .setTitle("Live TV stream format")
            .setSingleChoiceItems(arrayOf("MPEG-TS (.ts) – recommended", "HLS (.m3u8) – try if channels stutter or won't start"),
                values.indexOf(store.liveFormat).coerceAtLeast(0)) { d, which ->
                store.liveFormat = values[which]
                d.dismiss()
            }
            .show()
    }

    private fun choosePlayer() {
        AlertDialog.Builder(this)
            .setTitle("Default player")
            .setSingleChoiceItems(arrayOf("Built-in player", "External (MX Player / VLC)"),
                if (store.externalByDefault) 1 else 0) { d, which ->
                store.externalByDefault = which == 1
                d.dismiss()
            }
            .show()
    }

    private fun showAccountInfo() {
        val acc = store.account ?: return
        val info = store.userInfo
        fun v(k: String) = info?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } ?: "–"
        val exp = info?.optString("exp_date")?.toLongOrNull()
        val text = buildString {
            appendLine("Server: ${acc.server}")
            appendLine("Username: ${acc.username}")
            appendLine("Status: ${v("status")}")
            appendLine("Expires: ${if (exp != null && exp > 0) Fmt.date(exp * 1000) else "No expiry date"}")
            appendLine("Connections: ${v("active_cons")} active / ${v("max_connections")} allowed")
            append("Trial: ${if (v("is_trial") == "1") "Yes" else "No"}")
        }
        AlertDialog.Builder(this).setTitle("Account").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logout() {
        Catalog.clear(this)
        store.clearAll()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
