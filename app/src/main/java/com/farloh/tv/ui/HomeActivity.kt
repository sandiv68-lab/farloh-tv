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
import com.farloh.tv.data.Playlist
import com.farloh.tv.data.Section
import com.farloh.tv.data.Xtream
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
    private lateinit var playlistButton: Button

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
                v.animate().scaleX(if (f) 1.07f else 1f).scaleY(if (f) 1.07f else 1f).setDuration(140).start()
            }
            tile.refresh.contentDescription = "Refresh ${section.title}"
            tile.refresh.setOnClickListener { refresh(section) }
        }

        sizeTiles()

        playlistButton = findViewById(R.id.playlist)
        playlistButton.setOnClickListener { showPlaylists() }
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.downloads).setOnClickListener { openDownloads() }
        findViewById<View>(R.id.search).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        findViewById<View>(R.id.bell).setOnClickListener { startActivity(Intent(this, NewsActivity::class.java)) }
        findViewById<View>(R.id.bell).visibility = if (com.farloh.tv.data.Feed.configured) View.VISIBLE else View.GONE
        tiles.getValue(Section.LIVE).card.requestFocus()

        if (!store.initialLoadDone) firstLoad()
    }

    override fun onResume() {
        super.onResume()
        if (tiles.isEmpty()) return
        if (store.account == null) { requireAccount(); return }
        showHeader()
        Section.values().forEach { updateTile(it) }
        updateBell()
        checkNews(force = false)
        handler.removeCallbacks(newsTick)
        handler.postDelayed(newsTick, 3 * 3600_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(newsTick)
    }

    // ---------------- News bell & updates ----------------

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val newsTick = object : Runnable {
        override fun run() {
            checkNews(force = true)
            handler.postDelayed(this, 3 * 3600_000L)
        }
    }

    private fun updateBell() {
        val n = com.farloh.tv.data.Feed.unreadCount(this)
        findViewById<TextView>(R.id.bellBadge).apply {
            text = if (n > 9) "9+" else n.toString()
            visibility = if (n > 0) View.VISIBLE else View.GONE
        }
    }

    private fun checkNews(force: Boolean) {
        if (!com.farloh.tv.data.Feed.configured) return
        lifecycleScope.launch {
            runCatching { com.farloh.tv.data.Feed.refresh(this@HomeActivity, force) }
            if (isFinishing) return@launch
            updateBell()
            val update = com.farloh.tv.data.Feed.availableUpdate(this@HomeActivity) ?: return@launch
            if (com.farloh.tv.data.Feed.shouldPrompt(this@HomeActivity, update.version)) {
                com.farloh.tv.data.Feed.setPrompted(this@HomeActivity, update.version)
                Updater.offer(this@HomeActivity, update)
            }
        }
    }

    /**
     * Fixed-size tiles that fit the screen with room around them (so the focus outline is visible),
     * capped so they don't fill a big TV. They follow Settings → Screen → App size.
     */
    private fun sizeTiles() {
        val w = resources.configuration.screenWidthDp
        val h = resources.configuration.screenHeightDp
        val byWidth = (w - 40 - 3 * 36) / 3          // three tiles + margins across
        val byHeight = ((h - 190) / 0.72f).toInt()    // header + refresh buttons below
        val tileW = minOf(230, byWidth, byHeight).coerceAtLeast(130)
        val tileH = (tileW * 0.72f).toInt()
        for (tile in tiles.values) {
            tile.root.layoutParams = tile.root.layoutParams.apply { width = dp(tileW) }
            tile.card.layoutParams = tile.card.layoutParams.apply { height = dp(tileH) }
            if (tileW < 190) {
                tile.icon.layoutParams = tile.icon.layoutParams.apply { width = dp(36); height = dp(36) }
                tile.title.textSize = 17f
            }
        }
    }

    private fun showHeader() {
        playlistButton.text = store.activePlaylist?.name ?: "Playlist"
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

    private fun openDownloads() = startActivity(Intent(this, DownloadsActivity::class.java))

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

    /** After adding a playlist, fetch its three lists once. After that, only manual refresh. */
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

    // ---------------- Playlists ----------------

    private fun showPlaylists() {
        val all = store.playlists()
        val activeId = store.activePlaylist?.id
        val labels = all.map { (if (it.id == activeId) "✓  " else "     ") + it.name + "   (${it.account.username})" } +
            listOf("+  Add playlist", "⚙  Manage playlists…")
        AlertDialog.Builder(this)
            .setTitle("Playlists")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < all.size -> if (all[which].id != activeId) switchTo(all[which])
                    which == all.size -> startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ADD, true))
                    else -> startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN, "playlist"))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun switchTo(p: Playlist) {
        store.setActive(p.id)
        toast("Switched to ${p.name}")
        restartHome()
    }

    private fun restartHome() {
        Catalog.clearMemory()
        Xtream.clearCache()
        val target = if (store.account == null) LoginActivity::class.java else HomeActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
