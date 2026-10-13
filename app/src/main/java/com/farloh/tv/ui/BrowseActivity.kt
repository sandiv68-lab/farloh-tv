package com.farloh.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
    }

    private data class CatRow(val id: String, val name: String, val count: Int)

    private lateinit var section: Section
    private lateinit var account: Account

    private lateinit var updated: TextView
    private lateinit var refreshButton: Button
    private lateinit var refreshing: ProgressBar
    private lateinit var categoryButton: Button
    private lateinit var search: EditText
    private lateinit var resultCount: TextView
    private lateinit var categoriesRv: RecyclerView
    private lateinit var itemsRv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var epgPanel: View
    private lateinit var epgChannel: TextView
    private lateinit var epgEmpty: TextView
    private lateinit var epgRv: RecyclerView

    private var data: SectionData? = null
    private var catRows: List<CatRow> = emptyList()
    private var currentCat = ALL
    private var query = ""
    private var wide = false
    private var favs: Set<String> = emptySet()

    private var epgJob: Job? = null
    private var catJob: Job? = null
    private var searchJob: Job? = null

    private val catAdapter = SimpleAdapter<CatRow>(R.layout.row_category, 1.02f) { v, row, _ ->
        v.findViewById<TextView>(R.id.name).text = row.name
        v.findViewById<TextView>(R.id.count).text = row.count.toString()
        v.isSelected = row.id == currentCat
    }

    private lateinit var itemAdapter: SimpleAdapter<CatalogItem>

    private val epgAdapter = SimpleAdapter<Programme>(R.layout.row_epg, interactive = false) { v, p, _ -> bindProgramme(v, p) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = runCatching { Section.valueOf(intent.getStringExtra(EXTRA_SECTION) ?: "") }.getOrDefault(Section.LIVE)
        account = requireAccount() ?: return
        setContentView(R.layout.activity_browse)
        wide = screenWidthDp >= 720
        favs = store.favorites()

        updated = findViewById(R.id.updated)
        refreshButton = findViewById(R.id.refresh)
        refreshing = findViewById(R.id.refreshing)
        categoryButton = findViewById(R.id.categoryButton)
        search = findViewById(R.id.search)
        resultCount = findViewById(R.id.resultCount)
        categoriesRv = findViewById(R.id.categories)
        itemsRv = findViewById(R.id.items)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        epgPanel = findViewById(R.id.epgPanel)
        epgChannel = findViewById(R.id.epgChannel)
        epgEmpty = findViewById(R.id.epgEmpty)
        epgRv = findViewById(R.id.epgList)

        findViewById<TextView>(R.id.title).text = section.title
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        refreshButton.text = "Refresh"
        refreshButton.setOnClickListener { refresh() }

        // Categories: side column on TVs/tablets, a picker button on phones.
        categoriesRv.visibility = if (wide) View.VISIBLE else View.GONE
        categoryButton.visibility = if (wide) View.GONE else View.VISIBLE
        categoryButton.setOnClickListener { pickCategory() }
        categoriesRv.layoutManager = LinearLayoutManager(this)
        categoriesRv.itemAnimator = null
        categoriesRv.adapter = catAdapter
        catAdapter.onClick = { row, _ -> selectCategory(row.id) }
        catAdapter.onFocus = { row, _ ->
            catJob?.cancel()
            catJob = lifecycleScope.launch { delay(350); selectCategory(row.id) }
        }
        if (!wide) resultCount.visibility = View.GONE

        setupItems()

        epgPanel.visibility = if (section == Section.LIVE && wide) View.VISIBLE else View.GONE
        epgRv.layoutManager = LinearLayoutManager(this)
        epgRv.adapter = epgAdapter
        epgChannel.text = "Select a channel"

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    query = s?.toString().orEmpty().trim()
                    applyFilter()
                }
            }
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        if (section == Section.LIVE && data != null) {
            val newFavs = store.favorites()
            if (newFavs != favs) {
                favs = newFavs
                render(keepScroll = true)
            }
        }
        updated.text = Fmt.ago(Catalog.lastUpdated(this, section))
    }

    private fun setupItems() {
        itemsRv.itemAnimator = null
        if (section == Section.LIVE) {
            itemsRv.layoutManager = LinearLayoutManager(this)
            itemAdapter = SimpleAdapter<CatalogItem>(R.layout.row_channel, 1.02f) { v, item, _ -> bindChannel(v, item as LiveChannel) }
            itemAdapter.onClick = { _, pos -> playChannel(pos, external = null) }
            itemAdapter.onLongClick = { item, pos -> channelOptions(item as LiveChannel, pos) }
            itemAdapter.onFocus = { item, _ ->
                if (wide) {
                    epgJob?.cancel()
                    epgJob = lifecycleScope.launch { delay(400); showGuide(item as LiveChannel) }
                }
            }
        } else {
            val available = screenWidthDp - (if (wide) 270 else 0) - 24
            val span = (available / 150).coerceIn(2, 10)
            itemsRv.layoutManager = GridLayoutManager(this, span)
            itemAdapter = SimpleAdapter<CatalogItem>(R.layout.item_poster, 1.07f) { v, item, _ -> bindPoster(v, item) }
            itemAdapter.onClick = { item, _ -> openDetails(item) }
            itemAdapter.onLongClick = { item, _ -> posterOptions(item) }
        }
        itemsRv.adapter = itemAdapter
    }

    // ---------------- Loading & refresh ----------------

    private fun load() {
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                data = Catalog.load(this@BrowseActivity, section)
                render(keepScroll = false)
                focusFirstItem()
            } catch (e: Exception) {
                empty.text = "${Net.friendlyError(e)}\n\nPress Refresh to try again."
                empty.visibility = View.VISIBLE
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun refresh() {
        refreshing.visibility = View.VISIBLE
        refreshButton.isEnabled = false
        updated.text = "Refreshing ${section.title}…"
        lifecycleScope.launch {
            try {
                val d = Catalog.refresh(this@BrowseActivity, section)
                data = d
                render(keepScroll = false)
                toast("${section.title} refreshed: ${d.items.size} items")
            } catch (e: Exception) {
                toast(Net.friendlyError(e), long = true)
            } finally {
                refreshing.visibility = View.GONE
                refreshButton.isEnabled = true
                updated.text = Fmt.ago(Catalog.lastUpdated(this@BrowseActivity, section))
            }
        }
    }

    private fun render(keepScroll: Boolean) {
        val d = data ?: return
        updated.text = Fmt.ago(d.updatedAt)
        val counts = d.byCategory
        val rows = ArrayList<CatRow>()
        rows.add(CatRow(ALL, "All", d.items.size))
        if (section == Section.LIVE) rows.add(CatRow(FAV, "★  Favourites", d.items.count { it.id in favs }))
        for (c in d.categories) {
            val n = counts[c.id]?.size ?: 0
            if (n > 0) rows.add(CatRow(c.id, c.name, n))
        }
        catRows = rows
        if (rows.none { it.id == currentCat }) currentCat = ALL
        catAdapter.submit(rows)
        applyFilter(keepScroll)
    }

    private fun applyFilter(keepScroll: Boolean = false) {
        val d = data ?: return
        val list: List<CatalogItem> = when {
            query.isNotEmpty() -> d.items.filter { it.name.contains(query, ignoreCase = true) }
            currentCat == ALL -> d.items
            currentCat == FAV -> d.items.filter { it.id in favs }
            else -> d.byCategory[currentCat].orEmpty()
        }
        itemAdapter.submit(list)
        if (!keepScroll) itemsRv.scrollToPosition(0)
        val catName = currentCatName()
        categoryButton.text = catName
        resultCount.text = when (section) {
            Section.LIVE -> Fmt.count(list.size, "channel", "channels")
            Section.MOVIES -> Fmt.count(list.size, "movie", "movies")
            Section.SERIES -> Fmt.count(list.size, "show", "shows")
        }
        if (list.isEmpty()) {
            empty.text = when {
                query.isNotEmpty() -> "Nothing matches \"$query\"."
                currentCat == FAV -> "No favourites yet.\nHold OK (or long-press) on a channel to add it."
                d.items.isEmpty() -> "The server returned an empty list.\nPress Refresh to try again."
                else -> "Nothing in $catName."
            }
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun currentCatName() = catRows.firstOrNull { it.id == currentCat }?.name ?: "All"

    private fun selectCategory(id: String) {
        if (id == currentCat && query.isEmpty()) return
        val oldIndex = catRows.indexOfFirst { it.id == currentCat }
        currentCat = id
        val newIndex = catRows.indexOfFirst { it.id == id }
        if (oldIndex >= 0) catAdapter.notifyItemChanged(oldIndex)
        if (newIndex >= 0) catAdapter.notifyItemChanged(newIndex)
        if (query.isNotEmpty()) {
            query = ""
            searchJob?.cancel()
            search.setText("")
            searchJob?.cancel()
        }
        applyFilter()
    }

    private fun pickCategory() {
        if (catRows.isEmpty()) return
        val names = catRows.map { "${it.name}  (${it.count})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Categories")
            .setSingleChoiceItems(names, catRows.indexOfFirst { it.id == currentCat }) { d, which ->
                selectCategory(catRows[which].id)
                d.dismiss()
            }
            .show()
    }

    private fun focusFirstItem() {
        itemsRv.post {
            val first = itemsRv.layoutManager?.findViewByPosition(0)
            if (first != null) first.requestFocus()
            else if (wide) categoriesRv.getChildAt(0)?.requestFocus()
        }
    }

    // ---------------- Live TV ----------------

    private fun bindChannel(v: View, ch: LiveChannel) {
        v.findViewById<TextView>(R.id.num).text = ch.num
        v.findViewById<TextView>(R.id.name).text = ch.name
        v.findViewById<ImageView>(R.id.logo).loadImage(ch.image)
        v.findViewById<View>(R.id.fav).visibility = if (ch.id in favs) View.VISIBLE else View.GONE
    }

    private fun livePlayable(ch: LiveChannel) =
        Playable(Kind.LIVE, ch.id, ch.name, account.liveUrl(ch.id, store.liveFormat), ch.image, ch.num)

    private fun playChannel(position: Int, external: Boolean?) {
        val channels = itemAdapter.items.filterIsInstance<LiveChannel>()
        if (channels.isEmpty()) return
        val label = if (query.isNotEmpty()) "Search: $query" else currentCatName()
        Launcher.play(this, channels.map { livePlayable(it) }, position, external = external, categoryName = label)
    }

    private fun channelOptions(ch: LiveChannel, position: Int) {
        val isFav = ch.id in favs
        val options = arrayOf(
            "Play",
            "Play in MX Player / VLC",
            if (isFav) "Remove from Favourites" else "Add to Favourites",
            "TV Guide",
        )
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playChannel(position, external = false)
                    1 -> playChannel(position, external = true)
                    2 -> {
                        val added = store.toggleFavorite(ch.id)
                        favs = store.favorites()
                        toast(if (added) "Added to Favourites" else "Removed from Favourites")
                        render(keepScroll = true)
                    }
                    3 -> if (wide) showGuide(ch) else guideDialog(ch)
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
        val full = runCatching { Xtream.epg(account, ch.id, full = true) }.getOrDefault(emptyList())
        if (full.isNotEmpty()) return full
        return runCatching { Xtream.epg(account, ch.id, full = false) }.getOrDefault(emptyList())
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
        if (rating != null) {
            badge.text = "★ " + String.format(java.util.Locale.US, "%.1f", if (rating > 10) rating / 10 else rating)
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun openDetails(item: CatalogItem) {
        when (item) {
            is Movie -> startActivity(
                Intent(this, MovieDetailActivity::class.java)
                    .putExtra(MovieDetailActivity.EXTRA_ID, item.id)
                    .putExtra(MovieDetailActivity.EXTRA_NAME, item.name)
                    .putExtra(MovieDetailActivity.EXTRA_IMAGE, item.image)
                    .putExtra(MovieDetailActivity.EXTRA_EXT, item.ext)
            )
            is Show -> startActivity(
                Intent(this, SeriesDetailActivity::class.java)
                    .putExtra(SeriesDetailActivity.EXTRA_ID, item.id)
                    .putExtra(SeriesDetailActivity.EXTRA_NAME, item.name)
                    .putExtra(SeriesDetailActivity.EXTRA_IMAGE, item.image)
            )
        }
    }

    private fun posterOptions(item: CatalogItem) {
        if (item !is Movie) { openDetails(item); return }
        val p = Playable(Kind.MOVIE, item.id, item.name, account.movieUrl(item.id, item.ext), item.image)
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(arrayOf("Play", "Play in MX Player / VLC", "Details")) { _, which ->
                when (which) {
                    0 -> Launcher.play(this, listOf(p), 0, external = false)
                    1 -> Launcher.play(this, listOf(p), 0, external = true)
                    2 -> openDetails(item)
                }
            }
            .show()
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
