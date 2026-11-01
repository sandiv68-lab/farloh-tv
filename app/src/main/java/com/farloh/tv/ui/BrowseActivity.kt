package com.farloh.tv.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.R
import com.farloh.tv.data.Account
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.CatalogItem
import com.farloh.tv.data.LiveChannel
import com.farloh.tv.data.Movie
import com.farloh.tv.data.Playlist
import com.farloh.tv.data.Programme
import com.farloh.tv.data.Section
import com.farloh.tv.data.SectionData
import com.farloh.tv.data.Show
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Launcher
import com.farloh.tv.player.Playable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Category list + channel list (with TV guide) or poster grid, for one section. */
class BrowseActivity : BaseActivity() {

    companion object {
        const val EXTRA_SECTION = "section"
        private const val ALL = "__all"
        private const val FAV = "__fav"
        private const val RECENT = "__recent"
    }

    /** A row in the category column. [header] rows are playlist names that open/close (combined view). */
    private data class CatRow(val id: String, val name: String, val count: Int, val header: Boolean = false, val playlistId: String = "")

    /** One playlist's list for this section (several in the combined view). */
    private class Part(val playlist: Playlist, val data: SectionData?, val error: String?)

    private lateinit var section: Section
    private lateinit var account: Account
    private var parts: List<Part> = emptyList()
    private val combined: Boolean get() = parts.size > 1
    private var byCategory: Map<String, List<CatalogItem>> = emptyMap()
    /** Playlists whose categories are open in the combined view. */
    private val expanded = HashSet<String>()

    private lateinit var updated: TextView
    private lateinit var categoryButton: Button
    private lateinit var resultCount: TextView
    private lateinit var categoriesRv: RecyclerView
    private lateinit var itemsRv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var epgPanel: View
    private lateinit var epgChannel: TextView
    private lateinit var epgEmpty: TextView
    private lateinit var epgRv: RecyclerView

    private val data: Any? get() = parts.firstOrNull { it.data != null }?.data
    private var catRows: List<CatRow> = emptyList()
    private var currentCat = ALL
    private var wide = false
    private var favs: Set<String> = emptySet()
    private var visible: List<CatalogItem> = emptyList()
    private var recent: List<CatalogItem> = emptyList()
    private var stateSignature = ""

    private var epgJob: Job? = null
    private var catJob: Job? = null

    private val catAdapter = SimpleAdapter<CatRow>(R.layout.row_category, 1.02f) { v, row, _ ->
        val name = v.findViewById<TextView>(R.id.name)
        if (row.header) {
            name.text = (if (row.playlistId in expanded) "▼  " else "▶  ") + row.name
            name.setTextColor(ContextCompat.getColor(this, R.color.accent2))
            name.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            name.text = if (combined && row.playlistId.isNotEmpty()) "    " + row.name else row.name
            name.setTextColor(ContextCompat.getColor(this, R.color.text))
            name.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        v.findViewById<TextView>(R.id.count).text = row.count.toString()
        v.isSelected = !row.header && row.id == currentCat
    }

    /** Favourite keys are "playlistId|itemId" so the same id in two playlists doesn't clash. */
    private fun favKey(item: CatalogItem) = "${item.playlistId}|${item.id}"

    private fun loadFavs(): Set<String> =
        parts.flatMap { part -> store.favorites(section, part.playlist.id).map { "${part.playlist.id}|$it" } }.toHashSet()

    private lateinit var itemAdapter: SimpleAdapter<CatalogItem>

    private val epgAdapter = SimpleAdapter<Programme>(R.layout.row_epg, interactive = false) { v, p, _ -> bindProgramme(v, p) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = runCatching { Section.valueOf(intent.getStringExtra(EXTRA_SECTION) ?: "") }.getOrDefault(Section.LIVE)
        account = requireAccount() ?: return
        setContentView(R.layout.activity_browse)
        wide = wideLayout
        updated = findViewById(R.id.updated)
        categoryButton = findViewById(R.id.categoryButton)
        resultCount = findViewById(R.id.resultCount)
        // Search now lives on the home screen (searches all sections at once).
        findViewById<View>(R.id.filterRow).visibility = if (wide) View.GONE else View.VISIBLE
        categoriesRv = findViewById(R.id.categories)
        itemsRv = findViewById(R.id.items)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        epgPanel = findViewById(R.id.epgPanel)
        epgChannel = findViewById(R.id.epgChannel)
        epgEmpty = findViewById(R.id.epgEmpty)
        epgRv = findViewById(R.id.epgList)

        val views = store.viewPlaylists()
        findViewById<TextView>(R.id.title).text = if (views.size > 1) "${section.title} · ${views.size} playlists" else section.title
        expanded.addAll(store.expandedPlaylists(section))
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.sortButton).apply {
            visibility = if (section == Section.LIVE) View.GONE else View.VISIBLE
            setOnClickListener { chooseSort() }
        }

        // Categories: side column on TVs/tablets, a picker button on phones.
        categoriesRv.visibility = if (wide) View.VISIBLE else View.GONE
        categoryButton.visibility = if (wide) View.GONE else View.VISIBLE
        categoryButton.setOnClickListener { pickCategory() }
        categoriesRv.layoutManager = LinearLayoutManager(this)
        categoriesRv.itemAnimator = null
        categoriesRv.adapter = catAdapter
        catAdapter.onClick = { row, _ -> if (row.header) toggleHeader(row.playlistId) else selectCategory(row.id) }
        catAdapter.onFocus = onFocus@{ row, _ ->
            if (!tvMode || row.header) return@onFocus
            catJob?.cancel()
            catJob = lifecycleScope.launch { delay(350); selectCategory(row.id) }
        }

        setupItems()

        epgPanel.visibility = if (section == Section.LIVE && wide) View.VISIBLE else View.GONE
        // Phones: a slightly narrower guide, so channel names get more room.
        if (!tvMode) epgPanel.layoutParams = epgPanel.layoutParams.apply { width = dp(195) }
        epgRv.layoutManager = LinearLayoutManager(this)
        epgRv.adapter = epgAdapter
        epgChannel.text = if (tvMode) "Select a channel" else "Tap a channel to see its guide, tap it again to play"

        load()
    }

    override fun onResume() {
        super.onResume()
        // Re-draw only if favourites, history or parental settings changed while away.
        if (data != null && signature() != stateSignature) render(keepScroll = true)
        updated.text = updatedText()
    }

    private fun updatedText(): String {
        val oldest = parts.map { Catalog.lastUpdated(this, section, it.playlist.id) }.filter { it > 0 }.minOrNull() ?: 0L
        return Fmt.ago(oldest) + if (combined) " (oldest)" else ""
    }

    private fun signature(): String = parts.joinToString("|") { part ->
        val pid = part.playlist.id
        listOf(
            store.favorites(section, pid).hashCode(),
            if (section == Section.LIVE) store.history(pid).hashCode() else 0,
            store.hiddenCategories(section, pid).hashCode(),
        ).joinToString(",")
    } + "|${store.recentLimit}|${store.historyLimit}"

    // ---------------- Sorting (Movies / TV Shows) ----------------

    /** Movies: the date the provider added it. Shows: the date the provider last updated it (e.g. a new episode). */
    private val sortModes get() = listOf(
        "server" to "Default order",
        "added" to if (section == Section.SERIES) "Recently updated by server" else "Recently added by server",
        "rating" to "Rating",
        "release" to "Release date (newest first)",
        "az" to "A – Z", "za" to "Z – A",
    )

    /** "All" isn't sorted (big playlists would hang); it stays in the provider's order. */
    private fun sortMode() = if (currentCat == ALL) "server" else store.sortFor(section, currentCat)

    private fun updateSortButton() {
        findViewById<Button>(R.id.sortButton).visibility =
            if (section == Section.LIVE || currentCat == ALL) View.GONE else View.VISIBLE
        val label = sortModes.firstOrNull { it.first == sortMode() }?.second ?: "Default order"
        findViewById<Button>(R.id.sortButton).text = if (sortMode() == "server") "Sort" else "Sort: $label"
    }

    /** One box: first "This category / All categories" at the top, then the sort order. */
    private fun chooseSort() {
        val d = resources.displayMetrics.density
        val modes = sortModes
        var all = false
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (4 * d).toInt())
        }
        val scopeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = listOf(false to "This category", true to "All categories").map { (value, label) ->
            (layoutInflater.inflate(R.layout.tab_chip, scopeRow, false) as TextView).apply {
                text = label
                isSelected = value == all
                scopeRow.addView(this)
            } to value
        }
        chips.forEach { (chip, value) ->
            chip.setOnClickListener { all = value; chips.forEach { (c, v) -> c.isSelected = v == all } }
        }
        box.addView(scopeRow)
        box.addView(TextView(this).apply {
            text = "Sort ${currentCatName()} or all categories by:"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@BrowseActivity, R.color.text2))
            setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
        })
        val current = sortMode()
        for ((mode, label) in modes) {
            box.addView(TextView(this).apply {
                text = if (mode == current) "●  $label" else "○  $label"
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@BrowseActivity, R.color.text))
                if (mode == current) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.focus_bg)
                isFocusable = true; isClickable = true
                minHeight = (44 * d).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                setOnClickListener {
                    dialog.dismiss()
                    store.setSort(section, if (all) null else currentCat, mode)
                    applyFilter(keepScroll = false)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
            })
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sort")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener { chips.first().first.requestFocus() }
        dialog.show()
    }

    private val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }

    private fun sorted(list: List<CatalogItem>): List<CatalogItem> = when (sortMode()) {
        "added" -> list.sortedByDescending { addedOf(it) }
        "rating" -> list.sortedByDescending { ratingOf(it) }
        "release" -> list.sortedByDescending { releaseOf(it) }
        "az" -> list.sortedWith { a, b -> collator.compare(a.name, b.name) }
        "za" -> list.sortedWith { a, b -> collator.compare(b.name, a.name) }
        else -> list
    }

    private fun ratingOf(item: CatalogItem): Double {
        val r = when (item) { is Movie -> item.rating; is Show -> item.rating; else -> null }?.toDoubleOrNull() ?: return -1.0
        return if (r > 10) r / 10 else r
    }

    private val datePattern = Regex("(19[0-9]{2}|20[0-9]{2})(?:[-/.]([01]?[0-9]))?(?:[-/.]([0-3]?[0-9]))?")
    private val yearInName = Regex("\\b(19[0-9]{2}|20[0-9]{2})\\b")

    /**
     * Release date as a number (yyyyMMdd) for sorting: the provider's release date when the list has one,
     * otherwise the year in the name (e.g. "Dune (2021)"); unknown dates go last.
     */
    private fun releaseOf(item: CatalogItem): Int {
        val date = when (item) { is Movie -> item.released; is Show -> item.year; else -> null }
        datePattern.find(date.orEmpty())?.let { m ->
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toIntOrNull()?.coerceIn(0, 12) ?: 0
            val day = m.groupValues[3].toIntOrNull()?.coerceIn(0, 31) ?: 0
            return y * 10000 + mo * 100 + day
        }
        return (yearInName.findAll(item.name).lastOrNull()?.value?.toIntOrNull() ?: 0) * 10000
    }

    private fun addedOf(item: CatalogItem): Long = when (item) {
        is Movie -> item.added
        is Show -> item.added
        else -> 0L
    }

    private fun setupItems() {
        itemsRv.itemAnimator = null
        if (section == Section.LIVE) {
            itemsRv.layoutManager = LinearLayoutManager(this)
            itemAdapter = SimpleAdapter<CatalogItem>(R.layout.row_channel, 1.02f) { v, item, _ -> bindChannel(v, item as LiveChannel) }
            itemAdapter.onClick = { item, pos ->
                // Phones (with the guide beside the list): the first tap shows the channel's guide,
                // a tap on the same channel while its guide shows plays it.
                if (!tvMode && wide && touchedChannel != keyOf(item)) {
                    val old = touchedChannel
                    touchedChannel = keyOf(item)
                    itemAdapter.items.indexOfFirst { keyOf(it) == old }.takeIf { it >= 0 }?.let { itemAdapter.notifyItemChanged(it) }
                    itemAdapter.notifyItemChanged(pos)
                    epgJob?.cancel()
                    showGuide(item as LiveChannel)
                } else playChannel(pos, external = null)
            }
            // Hold OK / long-press = Favourite, as for movies and TV shows (Menu key = more options).
            itemAdapter.onLongClick = { item, _ -> quickFavorite(this, section, item); render(keepScroll = true) }
            itemAdapter.onFocus = { item, _ ->
                if (wide) {
                    epgJob?.cancel()
                    epgJob = lifecycleScope.launch { delay(400); showGuide(item as LiveChannel) }
                }
            }
            // TV: the selected channel's name scrolls when it's too long (and stops when the remote moves on).
            if (tvMode) itemsRv.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
                listOf(old to false, new to true).forEach { (view, on) ->
                    val row = view?.takeIf { it.parent === itemsRv } ?: return@forEach
                    row.findViewById<TextView>(R.id.name)?.apply {
                        ellipsize = if (on) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
                        isSelected = on
                    }
                }
            }
        } else {
            val available = screenWidthDp - (if (wide) 270 else 0) - 24
            val span = (available / 150).coerceIn(2, 10)
            itemsRv.layoutManager = GridLayoutManager(this, span)
            itemAdapter = SimpleAdapter<CatalogItem>(R.layout.item_poster, 1.07f) { v, item, _ -> bindPoster(v, item) }
            itemAdapter.onClick = { item, _ -> openDetails(item) }
            // Hold OK / long-press = Favourite (Menu key = more options).
            itemAdapter.onLongClick = { item, _ -> quickFavorite(this, section, item); render(keepScroll = true) }
        }
        itemsRv.adapter = itemAdapter
    }

    // ---------------- Loading & refresh ----------------

    private fun load() {
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        lifecycleScope.launch {
            parts = store.viewPlaylists().map { pl ->
                try {
                    Part(pl, Catalog.load(this@BrowseActivity, section, pl.id), null)
                } catch (e: Exception) {
                    Part(pl, null, Net.friendlyError(e))
                }
            }
            loading.visibility = View.GONE
            val failed = parts.filter { it.error != null }
            if (data == null) {
                empty.text = "${failed.firstOrNull()?.error ?: "Nothing loaded."}\n\nPress Refresh to try again."
                empty.visibility = View.VISIBLE
                return@launch
            }
            if (failed.isNotEmpty()) toast(failed.joinToString("\n") { "${it.playlist.name}: ${it.error}" }, long = true)
            render(keepScroll = false)
            focusFirstItem()
        }
    }

    private fun toggleHeader(pid: String) {
        if (!expanded.add(pid)) expanded.remove(pid)
        store.setExpandedPlaylists(section, expanded)
        render(keepScroll = true)
    }

    private fun render(keepScroll: Boolean) {
        if (data == null) return
        stateSignature = signature()
        updated.text = updatedText()
        favs = loadFavs()
        val vis = ArrayList<CatalogItem>()
        val cats = HashMap<String, List<CatalogItem>>()
        val recentLive = ArrayList<Pair<Long, CatalogItem>>()
        for (part in parts) {
            val d = part.data ?: continue
            val pid = part.playlist.id
            val hid = store.hiddenCategories(section, pid)
            val partVisible = if (hid.isEmpty()) d.items else d.items.filter { it.categoryId !in hid }
            vis.addAll(partVisible)
            for ((cid, list) in d.byCategory) cats["$pid|$cid"] = list
            if (section == Section.LIVE && store.historyLimit > 0) {
                val byId = partVisible.associateBy { it.id }
                for (id in store.history(pid)) byId[id]?.let { recentLive.add(store.historyTime(id, pid) to it) }
            }
        }
        visible = vis
        byCategory = cats
        recent = if (section == Section.LIVE) {
            recentLive.sortedByDescending { it.first }.map { it.second }.take(store.historyLimit)
        } else {
            if (store.recentLimit > 0) visible.filter { addedOf(it) > 0 }.sortedByDescending { addedOf(it) }.take(store.recentLimit)
            else emptyList()
        }
        val rows = ArrayList<CatRow>()
        rows.add(CatRow(ALL, "All", visible.size))
        rows.add(CatRow(FAV, "★  Favourites", visible.count { favKey(it) in favs }))
        if (section == Section.LIVE) {
            if (store.historyLimit > 0) rows.add(CatRow(RECENT, "Recently watched", recent.size))
        } else if (store.recentLimit > 0) {
            rows.add(CatRow(RECENT, "Recently added", recent.size))
        }
        for (part in parts) {
            val d = part.data
            val pid = part.playlist.id
            val hid = store.hiddenCategories(section, pid)
            if (combined) {
                val n = d?.items?.count { it.categoryId !in hid } ?: 0
                rows.add(CatRow("hdr|$pid", part.playlist.name + if (d == null) "  (not loaded)" else "", n, header = true, playlistId = pid))
                if (pid !in expanded) continue
            }
            if (d == null) continue
            for (c in store.ordered(section, pid, d.categories)) {
                if (c.id in hid) continue
                val n = d.byCategory[c.id]?.size ?: 0
                if (n > 0) rows.add(CatRow("$pid|${c.id}", c.name, n, playlistId = pid))
            }
        }
        catRows = rows
        if (rows.none { it.id == currentCat } && !byCategory.containsKey(currentCat)) currentCat = ALL
        catAdapter.submit(rows)
        applyFilter(keepScroll)
    }

    private fun applyFilter(keepScroll: Boolean = false) {
        if (data == null) return
        val list: List<CatalogItem> = when {
            currentCat == ALL -> visible
            currentCat == FAV -> visible.filter { favKey(it) in favs }
            currentCat == RECENT -> recent
            else -> byCategory[currentCat].orEmpty()
        }
        itemAdapter.submit(if (section == Section.LIVE) list else sorted(list))
        if (!keepScroll) itemsRv.scrollToPosition(0)
        val catName = currentCatName()
        updateSortButton()
        categoryButton.text = catName
        resultCount.text = when (section) {
            Section.LIVE -> Fmt.count(list.size, "channel", "channels")
            Section.MOVIES -> Fmt.count(list.size, "movie", "movies")
            Section.SERIES -> Fmt.count(list.size, "show", "shows")
        }
        if (list.isEmpty()) {
            empty.text = when {
                currentCat == FAV -> "No favourites yet.\nHold OK (or long-press) on an item to add it."
                currentCat == RECENT && section == Section.LIVE -> "Channels you watch will appear here."
                currentCat == RECENT -> "Nothing recently added."
                visible.isEmpty() -> "The server returned an empty list.\nPress Refresh to try again."
                else -> "Nothing in $catName."
            }
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun currentCatName(): String {
        val row = catRows.firstOrNull { it.id == currentCat } ?: return "All"
        val pl = if (combined && row.playlistId.isNotEmpty()) parts.firstOrNull { it.playlist.id == row.playlistId }?.playlist?.name else null
        return if (pl != null) "${row.name} ($pl)" else row.name
    }

    private fun selectCategory(id: String) {
        if (id == currentCat) return
        val oldIndex = catRows.indexOfFirst { it.id == currentCat }
        currentCat = id
        val newIndex = catRows.indexOfFirst { it.id == id }
        if (oldIndex >= 0) catAdapter.notifyItemChanged(oldIndex)
        if (newIndex >= 0) catAdapter.notifyItemChanged(newIndex)
        applyFilter()
    }

    private fun pickCategory() {
        if (catRows.isEmpty()) return
        val names = catRows.map {
            if (it.header) (if (it.playlistId in expanded) "▼ " else "▶ ") + it.name.uppercase() + "  (${it.count})"
            else (if (combined && it.playlistId.isNotEmpty()) "    " else "") + "${it.name}  (${it.count})"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Categories")
            .setSingleChoiceItems(names, catRows.indexOfFirst { it.id == currentCat }) { d, which ->
                val row = catRows[which]
                d.dismiss()
                if (row.header) { toggleHeader(row.playlistId); pickCategory() } else selectCategory(row.id)
            }
            .show()
    }

    /** TV / wide layout: start on the first category ("All"). Phones: the first item. */
    private fun focusFirstItem() {
        if (wide) { focusWhenReady(categoriesRv, 0); return }
        itemsRv.post { itemsRv.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    // ---------------- Live TV ----------------

    /** Phones: the channel tapped once (its guide shows; tap again to play). */
    private var touchedChannel: String? = null

    private fun keyOf(item: CatalogItem) = "${item.playlistId}|${item.id}"

    private fun bindChannel(v: View, ch: LiveChannel) {
        v.findViewById<TextView>(R.id.num).text = ch.num
        // TV: the channel the remote is on (its guide shows beside the list); phones: the one tapped once.
        val chosen = if (tvMode) v.isFocused else touchedChannel == keyOf(ch)
        v.findViewById<TextView>(R.id.name).apply {
            text = ch.name
            // The chosen channel's name scrolls when it's too long to fit.
            ellipsize = if (chosen) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
            marqueeRepeatLimit = -1
            isSelected = chosen
        }
        if (!tvMode) v.isSelected = chosen
        v.findViewById<ImageView>(R.id.logo).loadImage(ch.image)
        v.findViewById<View>(R.id.fav).visibility = if (favKey(ch) in favs) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.playlistTag).apply {
            visibility = if (combined) View.VISIBLE else View.GONE
            if (combined) text = playlistName(ch.playlistId)
        }
    }

    private fun playlistName(pid: String) = parts.firstOrNull { it.playlist.id == pid }?.playlist?.name.orEmpty()

    private fun accountOf(item: CatalogItem): Account = store.accountFor(item.playlistId) ?: account

    private fun livePlayable(ch: LiveChannel) =
        Playable(Kind.LIVE, ch.id, ch.name, accountOf(ch).liveUrl(ch.id, store.liveFormat), ch.image, ch.num,
            playlistId = ch.playlistId)

    private fun playChannel(position: Int, external: Boolean?) {
        val channels = itemAdapter.items.filterIsInstance<LiveChannel>()
        if (channels.isEmpty()) return
        val label = currentCatName()
        Launcher.play(this, channels.map { livePlayable(it) }, position, external = external, categoryName = label)
    }

    private fun channelOptions(ch: LiveChannel, position: Int) {
        val isFav = favKey(ch) in favs
        val options = arrayOf(
            if (isFav) "Remove from Favourites" else "Add to Favourites",
            "TV Guide",
            "Open in external",
        )
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { store.toggleFavorite(ch.id, section, ch.playlistId); render(keepScroll = true) }
                    1 -> if (wide) showGuide(ch) else guideDialog(ch)
                    2 -> playChannel(position, external = true)
                }
            }
            .show()
    }

    private fun showGuide(ch: LiveChannel) {
        epgChannel.text = listOf(ch.num, ch.name).filter { it.isNotBlank() }.joinToString("  ")
        epgAdapter.submit(emptyList())
        epgEmpty.text = "Loading guide…"
        epgEmpty.visibility = View.VISIBLE
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val list = fetchGuide(ch)
            epgAdapter.submit(list)
            if (list.isEmpty()) {
                epgEmpty.text = "No guide information for this channel."
            } else {
                epgEmpty.visibility = View.GONE
                val nowIndex = list.indexOfFirst { it.isNow() }
                if (nowIndex > 0) (epgRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(nowIndex, 0)
            }
        }
    }

    private suspend fun fetchGuide(ch: LiveChannel): List<Programme> {
        val acc = accountOf(ch)
        val full = runCatching { Xtream.epg(acc, ch.id, full = true) }.getOrDefault(emptyList())
        if (full.isNotEmpty()) return full
        return runCatching { Xtream.epg(acc, ch.id, full = false) }.getOrDefault(emptyList())
    }

    private fun guideDialog(ch: LiveChannel) {
        lifecycleScope.launch {
            val list = fetchGuide(ch)
            val text = if (list.isEmpty()) "No guide information for this channel." else list.joinToString("\n\n") { p ->
                val head = "${Fmt.time(this@BrowseActivity, p.start)} – ${Fmt.time(this@BrowseActivity, p.end)}" +
                    if (p.isNow()) "  •  NOW" else ""
                "$head\n${p.title}"
            }
            AlertDialog.Builder(this@BrowseActivity).setTitle(ch.name).setMessage(text)
                .setPositiveButton("Close", null).show()
        }
    }

    private fun bindProgramme(v: View, p: Programme) {
        val now = p.isNow()
        val time = v.findViewById<TextView>(R.id.time)
        time.text = "${Fmt.time(this, p.start)} – ${Fmt.time(this, p.end)}" + if (now) "  •  NOW" else ""
        time.setTextColor(ContextCompat.getColor(this, if (now) R.color.accent else R.color.text2))
        v.findViewById<TextView>(R.id.title).text = p.title.ifBlank { "Untitled" }
        val bar = v.findViewById<ProgressBar>(R.id.progress)
        bar.visibility = if (now) View.VISIBLE else View.GONE
        if (now) bar.progress = p.progress()
        val desc = v.findViewById<TextView>(R.id.desc)
        desc.text = p.description
        desc.visibility = if (now && p.description.isNotBlank()) View.VISIBLE else View.GONE
    }

    // ---------------- Movies & TV Shows ----------------

    private fun bindPoster(v: View, item: CatalogItem) {
        v.findViewById<ImageView>(R.id.poster).loadImage(item.image)
        v.findViewById<TextView>(R.id.name).text = item.name
        val badge = v.findViewById<TextView>(R.id.rating)
        val rating = when (item) {
            is Movie -> item.rating
            is Show -> item.rating
            else -> null
        }?.toDoubleOrNull()?.takeIf { it > 0 }
        val bits = listOfNotNull(
            rating?.let { "★ " + String.format(java.util.Locale.US, "%.1f", if (it > 10) it / 10 else it) },
            if (combined) playlistName(item.playlistId) else null,
        )
        badge.text = bits.joinToString("  ·  ")
        badge.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.fav).visibility = if (favKey(item) in favs) View.VISIBLE else View.GONE
    }

    private fun openDetails(item: CatalogItem) = openItemDetails(this, item)

    private fun posterOptions(item: CatalogItem) {
        ItemOptions.show(this, section, item, accountOf(item)) { render(keepScroll = true) }
    }

    // ---------------- Remote: MENU key opens options for the focused item ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            if (focused != null) {
                val holder = itemsRv.findContainingViewHolder(focused)
                val pos = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                if (pos != RecyclerView.NO_POSITION && pos < itemAdapter.items.size) {
                    val item = itemAdapter.items[pos]
                    if (item is LiveChannel) channelOptions(item, pos) else posterOptions(item)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
