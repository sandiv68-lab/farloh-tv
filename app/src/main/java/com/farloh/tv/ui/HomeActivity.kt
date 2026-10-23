package com.farloh.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
    private val vpn = VpnFlow(this)
    private val permissions = AppPermissions(this)
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
        if (savedInstanceState == null) permissions.askOnce()
        if (store.vpnAuto && store.vpnProfile != null) com.farloh.tv.net.OpenVpn.autoConnect(this) { vpn.connect(quiet = true) }

        playlistButton = findViewById(R.id.playlist)
        playlistButton.setOnClickListener { showPlaylists() }
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.downloads).setOnClickListener { openDownloads() }
        findViewById<View>(R.id.search).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        findViewById<View>(R.id.bell).setOnClickListener { startActivity(Intent(this, NewsActivity::class.java)) }
        findViewById<View>(R.id.bell).visibility = if (com.farloh.tv.data.Feed.configured) View.VISIBLE else View.GONE
        tiles.getValue(Section.LIVE).card.requestFocus()

        if (store.viewPlaylists().any { !store.isLoaded(it.id) }) firstLoad()
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
            com.farloh.tv.data.Notify.markShown(this@HomeActivity) // the app shows these itself; no notification needed
            val update = com.farloh.tv.data.Feed.availableUpdate(this@HomeActivity) ?: return@launch
            if (com.farloh.tv.data.Feed.shouldPrompt(this@HomeActivity, update.version)) {
                com.farloh.tv.data.Feed.setPrompted(this@HomeActivity, update.version)
                Updater.offer(this@HomeActivity, update)
            }
        }
    }

    /**
     * Sizes the three tiles to the space actually left on screen (after the notch, navigation bar, header and
     * the app/text size settings), capped so they don't fill a big TV and leave room for the focus outline.
     * Measured after layout because on Android 15 the screen size reported to apps includes the system bars.
     */
    private fun sizeTiles() {
        val area = findViewById<View>(R.id.tiles).parent as View
        area.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) area.post { fitTiles(area) }
        }
        area.post { fitTiles(area) }
    }

    private var lastTileW = -1

    private fun fitTiles(area: View) {
        val d = resources.displayMetrics.density
        val wDp = (area.width / d).toInt()
        val hDp = (area.height / d).toInt()
        if (wDp <= 0 || hDp <= 0) return
        val margin = if (wDp < 560) 8 else 18                    // each side of each tile
        val byWidth = (wDp - 3 * 2 * margin - 8) / 3
        val refreshRow = (14 + 38 * resources.configuration.fontScale).toInt() + 8 // gap + button + focus room
        val byHeight = ((hDp - refreshRow - 8) / 0.72f).toInt()
        val tileW = minOf(230, byWidth, byHeight).coerceAtLeast(80)
        if (tileW == lastTileW) return
        lastTileW = tileW
        val tileH = (tileW * 0.72f).toInt()
        val small = tileW < 190
        val tiny = tileW < 140
        for (tile in tiles.values) {
            tile.root.layoutParams = (tile.root.layoutParams as ViewGroup.MarginLayoutParams).apply {
                width = dp(tileW); marginStart = dp(margin); marginEnd = dp(margin)
            }
            tile.card.layoutParams = tile.card.layoutParams.apply { height = dp(tileH) }
            tile.card.setPadding(dp(if (tiny) 6 else 14), dp(if (tiny) 4 else 14), dp(if (tiny) 6 else 14), dp(if (tiny) 4 else 14))
            val icon = if (tiny) 26 else if (small) 36 else 48
            tile.icon.layoutParams = tile.icon.layoutParams.apply { width = dp(icon); height = dp(icon) }
            tile.title.textSize = if (tiny) 14f else if (small) 17f else 21f
            tile.count.textSize = if (tiny) 11f else 13f
            tile.updated.textSize = if (tiny) 10f else 12f
        }
        area.requestLayout()
    }

    private fun showHeader() {
        val views = store.viewPlaylists()
        playlistButton.text = if (views.size > 1) "Combined: " + views.joinToString(" + ") { it.name } else store.activePlaylist?.name ?: "Playlist"
        val info = store.userInfo
        val exp = info?.optString("exp_date")?.toLongOrNull()
        val expiry = if (exp != null && exp > 0) "Expires ${Fmt.date(exp * 1000)}" else "No expiry date"
        val cons = info?.optString("max_connections")?.takeIf { it.isNotBlank() && it != "null" }
        findViewById<TextView>(R.id.account).text =
            listOfNotNull(store.account?.username, expiry, cons?.let { "$it connection(s)" }).joinToString("  •  ")
    }

    private fun updateTile(section: Section) {
        val tile = tiles[section] ?: return
        val counts = store.viewPlaylists().map { store.itemCount(section, it.id) }.filter { it >= 0 }
        val n = if (counts.isEmpty()) -1 else counts.sum()
        tile.count.text = when {
            n < 0 -> "Not loaded yet"
            section == Section.LIVE -> Fmt.count(n, "channel", "channels")
            section == Section.MOVIES -> Fmt.count(n, "movie", "movies")
            else -> Fmt.count(n, "show", "shows")
        }
        tile.updated.text = Fmt.ago(store.viewPlaylists().map { Catalog.lastUpdated(this, section, it.id) }.filter { it > 0 }.minOrNull() ?: 0L)
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
            val views = store.viewPlaylists()
            var total = 0
            val errors = ArrayList<String>()
            for (pl in views) {
                try {
                    total += Catalog.refresh(this@HomeActivity, section, pl.id).items.size
                } catch (e: Exception) {
                    errors += (if (views.size > 1) "${pl.name}: " else "") + Net.friendlyError(e)
                }
            }
            busy.remove(section)
            updateTile(section)
            if (errors.isNotEmpty()) toast("${section.title}: " + errors.joinToString("\n"), long = true)
            else if (!quiet) toast("${section.title} refreshed: $total items")
        }
    }

    /** After adding a playlist, fetch its three lists once. After that, only manual refresh. */
    private fun firstLoad() {
        Section.values().forEach { busy.add(it); updateTile(it) }
        lifecycleScope.launch {
            val pending = store.viewPlaylists().filter { !store.isLoaded(it.id) }
            val ok = pending.associate { it.id to true }.toMutableMap()
            for (section in Section.values()) {
                for (pl in pending) {
                    try {
                        Catalog.refresh(this@HomeActivity, section, pl.id)
                    } catch (e: Exception) {
                        ok[pl.id] = false
                        toast((if (pending.size > 1) "${pl.name} – " else "") + "${section.title}: ${Net.friendlyError(e)}", long = true)
                    }
                }
                busy.remove(section)
                updateTile(section)
            }
            ok.filterValues { it }.keys.forEach { store.setLoaded(it) }
        }
    }

    // ---------------- Playlists ----------------

    private fun showPlaylists() {
        val all = store.playlists()
        val combined = store.isCombined
        val activeId = store.activePlaylist?.id
        val labels = all.map { (if (!combined && it.id == activeId) "✓  " else "     ") + it.name + "   (${it.account.username})" }.toMutableList()
        val combineAt = labels.size
        if (all.size >= 2) labels += (if (combined) "✓  " else "") + "◆  Combine playlists…"
        val addAt = labels.size
        labels += "+  Add playlist"
        labels += "⚙  Manage playlists…"
        AlertDialog.Builder(this)
            .setTitle("Playlists")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < all.size -> if (combined || all[which].id != activeId) switchTo(all[which])
                    all.size >= 2 && which == combineAt -> chooseCombined()
                    which == addAt -> startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ADD, true))
                    else -> startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN, "playlist"))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Pick 2 or more playlists to show together. Each keeps its own categories. */
    private fun chooseCombined() {
        val all = store.playlists()
        val current = store.combinedIds.ifEmpty { all.map { it.id }.toSet() }
        val checked = BooleanArray(all.size) { all[it].id in current }
        AlertDialog.Builder(this)
            .setTitle("Combine playlists")
            .setMultiChoiceItems(all.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Combine") { _, _ ->
                val ids = all.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.size < 2) { toast("Choose at least 2 playlists", long = true); return@setPositiveButton }
                store.combinedIds = ids.toSet()
                store.combinedOn = true
                if (store.activePlaylist?.id !in ids) store.setActive(ids.first())
                toast("Showing ${ids.size} playlists together")
                restartHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchTo(p: Playlist) {
        store.combinedOn = false
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
