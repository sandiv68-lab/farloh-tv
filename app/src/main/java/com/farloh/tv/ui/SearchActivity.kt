package com.farloh.tv.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.R
import com.farloh.tv.data.Account
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.CatalogItem
import com.farloh.tv.data.GuideIndex
import com.farloh.tv.data.LiveChannel
import com.farloh.tv.data.Section
import com.farloh.tv.data.Show
import com.farloh.tv.net.Net
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Launcher
import com.farloh.tv.player.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One search for the whole app. Type and press Search; results are split by the filters
 * Live TV / Movies / TV Shows / TV Guide (programme names, from the downloaded full guide).
 * Runs in the background on pre-built indexes, so big playlists don't freeze the screen.
 */
class SearchActivity : BaseActivity() {

    companion object {
        private const val LIMIT = 1000
        private const val GUIDE = "guide"
    }

    /** A TV Guide result: a programme on a channel in this playlist. */
    private class GuideHit(val programme: GuideIndex.Programme, val channel: LiveChannel)

    private lateinit var account: Account
    private lateinit var query: EditText
    private lateinit var summary: TextView
    private lateinit var resultsRv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var guideAction: Button
    private lateinit var guideProgress: ProgressBar
    private lateinit var tabViews: Map<String, TextView>

    private var results: Map<Section, List<CatalogItem>> = emptyMap()
    private var guideHits: List<GuideHit> = emptyList()
    private var shown: String = Section.LIVE.name
    private var lastQuery = ""
    private var job: Job? = null
    private var guideJob: Job? = null
    /** Favourite keys: "playlistId|section|itemId" (several playlists in the combined view). */
    private var favs: Set<String> = emptySet()
    private val views by lazy { store.viewPlaylists() }
    private val combined get() = views.size > 1

    private fun favKey(sec: Section, item: CatalogItem) = "${item.playlistId}|${sec.name}|${item.id}"
    private fun loadFavs(): Set<String> = views.flatMap { pl ->
        Section.values().flatMap { sec -> store.favorites(sec, pl.id).map { "${pl.id}|${sec.name}|$it" } }
    }.toHashSet()
    private fun playlistName(pid: String) = views.firstOrNull { it.id == pid }?.name.orEmpty()
    private fun accountOf(item: CatalogItem) = store.accountFor(item.playlistId) ?: account

    private val channelAdapter = SimpleAdapter<CatalogItem>(R.layout.row_channel, 1.02f) { v, item, _ ->
        val ch = item as LiveChannel
        v.findViewById<TextView>(R.id.num).text = ch.num
        v.findViewById<TextView>(R.id.name).text = ch.name
        v.findViewById<TextView>(R.id.sub).apply {
            visibility = if (combined) View.VISIBLE else View.GONE
            text = playlistName(ch.playlistId)
        }
        v.findViewById<ImageView>(R.id.logo).loadImage(ch.image)
        v.findViewById<View>(R.id.fav).visibility = if (favKey(Section.LIVE, ch) in favs) View.VISIBLE else View.GONE
    }

    private val posterAdapter = SimpleAdapter<CatalogItem>(R.layout.item_poster, 1.07f) { v, item, _ ->
        v.findViewById<ImageView>(R.id.poster).loadImage(item.image)
        v.findViewById<TextView>(R.id.name).text = item.name
        v.findViewById<TextView>(R.id.rating).apply {
            visibility = if (combined) View.VISIBLE else View.GONE
            text = playlistName(item.playlistId)
        }
        val sec = if (item is Show) Section.SERIES else Section.MOVIES
        v.findViewById<View>(R.id.fav).visibility = if (favKey(sec, item) in favs) View.VISIBLE else View.GONE
    }

    private val guideAdapter = SimpleAdapter<GuideHit>(R.layout.row_channel, 1.02f) { v, hit, _ ->
        val p = hit.programme
        val now = p.isNow()
        v.findViewById<TextView>(R.id.num).apply {
            text = if (now) "NOW" else Fmt.time(this@SearchActivity, p.start)
            setTextColor(ContextCompat.getColor(this@SearchActivity, if (now) R.color.accent else R.color.text2))
        }
        v.findViewById<TextView>(R.id.name).text = p.title
        v.findViewById<TextView>(R.id.sub).apply {
            visibility = View.VISIBLE
            text = (if (combined) "${playlistName(hit.channel.playlistId)}  •  " else "") + "${hit.channel.name}  •  ${Fmt.time(this@SearchActivity, p.start)} – ${Fmt.time(this@SearchActivity, p.end)}" +
                if (!android.text.format.DateUtils.isToday(p.start)) "  (tomorrow)" else ""
        }
        v.findViewById<ImageView>(R.id.logo).loadImage(hit.channel.image)
        v.findViewById<View>(R.id.fav).visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = requireAccount() ?: return
        setContentView(R.layout.activity_search)

        query = findViewById(R.id.query)
        summary = findViewById(R.id.summary)
        resultsRv = findViewById(R.id.results)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        guideAction = findViewById(R.id.guideAction)
        guideProgress = findViewById(R.id.guideProgress)
        tabViews = mapOf(
            Section.LIVE.name to findViewById(R.id.tabLive),
            Section.MOVIES.name to findViewById(R.id.tabMovies),
            Section.SERIES.name to findViewById(R.id.tabSeries),
            GUIDE to findViewById(R.id.tabGuide),
        )
        resultsRv.itemAnimator = null

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.go).setOnClickListener { search() }
        for ((key, tab) in tabViews) tab.setOnClickListener { show(key) }
        guideAction.setOnClickListener { downloadGuide() }

        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                findViewById<View>(R.id.go).requestFocus()
                search()
                true
            } else false
        }

        channelAdapter.onClick = { _, pos -> playChannel(pos, external = false) }
        channelAdapter.onLongClick = { item, pos -> options(item, pos) }
        posterAdapter.onClick = { item, _ -> openItemDetails(this, item) }
        posterAdapter.onLongClick = { item, pos -> options(item, pos) }
        guideAdapter.onClick = { hit, _ -> openGuideHit(hit) }
        guideAdapter.onLongClick = { hit, _ ->
            ItemOptions.show(this, Section.LIVE, hit.channel, accountOf(hit.channel), playLive = { ext -> playGuideChannel(hit, ext) })
        }

        updateTabs()
        query.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        favs = loadFavs()
        resultsRv.adapter?.notifyDataSetChanged()
    }

    // ---------------- Searching ----------------

    private fun search() {
        val text = query.text.toString().trim()
        if (text.length < 2) {
            toast("Type at least 2 letters first")
            query.requestFocus()
            return
        }
        hideKeyboard()
        lastQuery = text
        job?.cancel()
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        summary.text = "Searching…"
        job = lifecycleScope.launch {
            val found = LinkedHashMap<Section, List<CatalogItem>>()
            val errors = ArrayList<String>()
            for (sec in Section.values()) {
                val hits = ArrayList<CatalogItem>()
                for (pl in views) {
                    try {
                        val data = Catalog.load(this@SearchActivity, sec, pl.id) // saved lists; downloads only if never loaded
                        val hidden = store.hiddenCategories(sec, pl.id)
                        hits += withContext(Dispatchers.Default) { data.search(text, hidden, LIMIT) }
                    } catch (e: Exception) {
                        errors += (if (combined) "${pl.name} – " else "") + "${sec.title}: ${Net.friendlyError(e)}"
                    }
                }
                found[sec] = hits.take(LIMIT)
            }
            results = found
            guideHits = searchGuide(text)
            loading.visibility = View.GONE
            if (errors.isNotEmpty()) toast(errors.joinToString("\n"), long = true)
            updateTabs()
            // Stay on the current filter if it has results, otherwise jump to the first one that does.
            val order = listOf(Section.LIVE.name, Section.MOVIES.name, Section.SERIES.name, GUIDE)
            val target = if (count(shown) > 0) shown else order.firstOrNull { count(it) > 0 } ?: shown
            show(target)
        }
    }

    private fun guideExists() = views.any { GuideIndex.exists(this, it.id) }

    private suspend fun searchGuide(text: String): List<GuideHit> {
        val all = ArrayList<GuideHit>()
        for (pl in views) {
            if (!GuideIndex.exists(this, pl.id)) continue
            val live = runCatching { Catalog.load(this, Section.LIVE, pl.id) }.getOrNull() ?: continue
            val hidden = store.hiddenCategories(Section.LIVE, pl.id)
            all += withContext(Dispatchers.Default) {
                val byEpg = HashMap<String, LiveChannel>()
                for (item in live.items) {
                    val ch = item as? LiveChannel ?: continue
                    val id = ch.epgId?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
                    if (ch.categoryId in hidden) continue
                    byEpg.putIfAbsent(id, ch)
                }
                GuideIndex.search(this@SearchActivity, text, LIMIT, pl.id)
                    .mapNotNull { p -> byEpg[p.channelId.lowercase()]?.let { GuideHit(p, it) } }
            }
        }
        val now = System.currentTimeMillis()
        return all.sortedWith(compareBy<GuideHit>({ !it.programme.isNow(now) }, { it.programme.start })).take(LIMIT)
    }

    private fun count(key: String): Int = if (key == GUIDE) guideHits.size
        else results[Section.valueOf(key)]?.size ?: 0

    private fun updateTabs() {
        val searched = lastQuery.isNotEmpty()
        for ((key, tab) in tabViews) {
            val label = if (key == GUIDE) "TV Guide" else Section.valueOf(key).title
            val n = count(key)
            tab.text = if (!searched) label else "$label ($n${if (n >= LIMIT) "+" else ""})"
            tab.isSelected = key == shown
        }
        updateGuideAction()
    }

    private fun updateGuideAction() {
        val onGuide = shown == GUIDE
        val running = guideJob?.isActive == true
        guideAction.visibility = if (onGuide && !running) View.VISIBLE else View.GONE
        guideProgress.visibility = if (running) View.VISIBLE else View.GONE
        val updated = views.map { GuideIndex.lastUpdated(this, it.id) }.filter { it > 0 }.minOrNull() ?: 0L
        guideAction.text = if (updated == 0L) "Download TV guide" else "Refresh TV guide (${Fmt.ago(updated).removePrefix("Updated ")})"
    }

    private fun show(key: String) {
        shown = key
        updateTabs()
        if (key == GUIDE) {
            resultsRv.layoutManager = LinearLayoutManager(this)
            resultsRv.adapter = guideAdapter
            guideAdapter.submit(guideHits)
            when {
                !guideExists() -> {
                    summary.text = "Search programme names across all channels"
                    setEmpty("To search the TV guide, download it once with \"Download TV guide\" (top right).\n" +
                        "It can take a minute on big playlists. Refresh it whenever you like.")
                    return
                }
                lastQuery.isEmpty() -> { summary.text = ""; setEmpty("Type a programme name and press Search."); return }
            }
            summary.text = if (guideHits.size >= LIMIT) "First $LIMIT programmes matching \"$lastQuery\""
                           else "${Fmt.count(guideHits.size, "programme", "programmes")} matching \"$lastQuery\" (on now and next 36 hours)"
            if (guideHits.isEmpty()) setEmpty("No upcoming programmes match \"$lastQuery\".") else showList()
            return
        }
        val sec = Section.valueOf(key)
        val list = results[sec].orEmpty()
        if (sec == Section.LIVE) {
            resultsRv.layoutManager = LinearLayoutManager(this)
            resultsRv.adapter = channelAdapter
            channelAdapter.submit(list)
        } else {
            val span = ((screenWidthDp - 24) / 150).coerceIn(2, 10)
            resultsRv.layoutManager = GridLayoutManager(this, span)
            resultsRv.adapter = posterAdapter
            posterAdapter.submit(list)
        }
        if (lastQuery.isEmpty()) { summary.text = ""; setEmpty("Type what you're looking for and press Search."); return }
        val word = when (sec) { Section.LIVE -> "channel"; Section.MOVIES -> "movie"; Section.SERIES -> "show" }
        summary.text = if (list.size >= LIMIT) "First $LIMIT ${word}s matching \"$lastQuery\" in ${sec.title} – type more to narrow it down"
                       else "${Fmt.count(list.size, word, word + "s")} matching \"$lastQuery\" in ${sec.title}"
        if (list.isEmpty()) setEmpty("Nothing in ${sec.title} matches \"$lastQuery\".") else showList()
    }

    private fun setEmpty(text: String) {
        empty.text = text
        empty.visibility = View.VISIBLE
    }

    private fun showList() {
        empty.visibility = View.GONE
        if (tvMode) resultsRv.post { resultsRv.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    // ---------------- TV guide download ----------------

    private fun downloadGuide() {
        if (guideJob?.isActive == true) return
        summary.text = "Downloading TV guide…"
        guideJob = lifecycleScope.launch {
            updateGuideAction()
            try {
                var n = 0
                val errors = ArrayList<String>()
                for (pl in views) {
                    val who = if (combined) " (${pl.name})" else ""
                    try {
                        n += GuideIndex.download(this@SearchActivity, pl.id) { mb ->
                            runOnUiThread { summary.text = "Downloading TV guide$who… $mb MB" }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors += (if (combined) "${pl.name}: " else "") + Net.friendlyError(e)
                    }
                }
                if (errors.isNotEmpty()) toast("Couldn't download the TV guide: " + errors.joinToString("\n"), long = true)
                if (errors.size < views.size) toast("TV guide ready: ${Fmt.count(n, "programme", "programmes")}")
                if (lastQuery.isNotEmpty()) guideHits = searchGuide(lastQuery)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't download the TV guide: ${Net.friendlyError(e)}", long = true)
            } finally {
                guideJob = null
                updateTabs()
                if (shown == GUIDE) show(GUIDE)
            }
        }
    }

    // ---------------- Actions ----------------

    private fun livePlayable(ch: LiveChannel) =
        Playable(Kind.LIVE, ch.id, ch.name, accountOf(ch).liveUrl(ch.id, store.liveFormat), ch.image, ch.num,
            playlistId = ch.playlistId)

    private fun playChannel(position: Int, external: Boolean) {
        val channels = results[Section.LIVE].orEmpty().filterIsInstance<LiveChannel>()
        if (channels.isEmpty()) return
        Launcher.play(this, channels.map { livePlayable(it) }, position, external = if (external) true else null,
            categoryName = "Search: $lastQuery")
    }

    private fun playGuideChannel(hit: GuideHit, external: Boolean) {
        val channels = guideHits.map { it.channel }.distinctBy { "${it.playlistId}|${it.id}" }
        val pos = channels.indexOfFirst { it === hit.channel || (it.id == hit.channel.id && it.playlistId == hit.channel.playlistId) }.coerceAtLeast(0)
        Launcher.play(this, channels.map { livePlayable(it) }, pos, external = if (external) true else null,
            categoryName = "TV Guide: $lastQuery")
    }

    private fun openGuideHit(hit: GuideHit) {
        val p = hit.programme
        if (p.isNow() || p.start - System.currentTimeMillis() < 5 * 60_000L) {
            playGuideChannel(hit, external = false)
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(p.title)
                .setMessage("Starts at ${Fmt.time(this, p.start)}" +
                    (if (!android.text.format.DateUtils.isToday(p.start)) " tomorrow" else "") + " on ${hit.channel.name}.")
                .setPositiveButton("Watch channel now") { _, _ -> playGuideChannel(hit, external = false) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun options(item: CatalogItem, pos: Int) {
        val sec = when (item) { is LiveChannel -> Section.LIVE; is Show -> Section.SERIES; else -> Section.MOVIES }
        ItemOptions.show(this, sec, item, accountOf(item), playLive = { ext -> playChannel(pos, ext) }) {
            favs = loadFavs()
            resultsRv.adapter?.notifyDataSetChanged()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(query.windowToken, 0)
    }

    /** MENU key on a remote opens the options of the focused result. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            val holder = currentFocus?.let { resultsRv.findContainingViewHolder(it) }
            val pos = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (pos != RecyclerView.NO_POSITION) {
                if (shown == GUIDE) {
                    guideHits.getOrNull(pos)?.let { hit ->
                        ItemOptions.show(this, Section.LIVE, hit.channel, accountOf(hit.channel), playLive = { ext -> playGuideChannel(hit, ext) })
                    }
                } else {
                    results[Section.valueOf(shown)]?.getOrNull(pos)?.let { options(it, pos) }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
