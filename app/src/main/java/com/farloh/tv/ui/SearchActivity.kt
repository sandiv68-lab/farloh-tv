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
 * One search for the whole app: type, then choose Live TV, Movies, TV Shows or All.
 * Searching runs in the background on a pre-built index, so big playlists don't freeze the screen.
 */
class SearchActivity : BaseActivity() {

    companion object {
        private const val LIMIT = 1000
    }

    private lateinit var account: Account
    private lateinit var query: EditText
    private lateinit var tabs: View
    private lateinit var summary: TextView
    private lateinit var resultsRv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var tabViews: Map<Section, TextView>

    private var results: Map<Section, List<CatalogItem>> = emptyMap()
    private var shown: Section = Section.LIVE
    private var lastQuery = ""
    private var job: Job? = null
    private var favs: Map<Section, Set<String>> = emptyMap()

    private val channelAdapter = SimpleAdapter<CatalogItem>(R.layout.row_channel, 1.02f) { v, item, _ ->
        val ch = item as LiveChannel
        v.findViewById<TextView>(R.id.num).text = ch.num
        v.findViewById<TextView>(R.id.name).text = ch.name
        v.findViewById<ImageView>(R.id.logo).loadImage(ch.image)
        v.findViewById<View>(R.id.fav).visibility = if (ch.id in favs[Section.LIVE].orEmpty()) View.VISIBLE else View.GONE
    }

    private val posterAdapter = SimpleAdapter<CatalogItem>(R.layout.item_poster, 1.07f) { v, item, _ ->
        v.findViewById<ImageView>(R.id.poster).loadImage(item.image)
        v.findViewById<TextView>(R.id.name).text = item.name
        v.findViewById<View>(R.id.rating).visibility = View.GONE
        val sec = if (item is Show) Section.SERIES else Section.MOVIES
        v.findViewById<View>(R.id.fav).visibility = if (item.id in favs[sec].orEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = requireAccount() ?: return
        setContentView(R.layout.activity_search)

        query = findViewById(R.id.query)
        tabs = findViewById(R.id.tabs)
        summary = findViewById(R.id.summary)
        resultsRv = findViewById(R.id.results)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        tabViews = mapOf(
            Section.LIVE to findViewById(R.id.tabLive),
            Section.MOVIES to findViewById(R.id.tabMovies),
            Section.SERIES to findViewById(R.id.tabSeries),
        )
        resultsRv.itemAnimator = null

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.inLive).setOnClickListener { search(listOf(Section.LIVE)) }
        findViewById<Button>(R.id.inMovies).setOnClickListener { search(listOf(Section.MOVIES)) }
        findViewById<Button>(R.id.inSeries).setOnClickListener { search(listOf(Section.SERIES)) }
        findViewById<Button>(R.id.inAll).setOnClickListener { search(Section.values().toList()) }
        for ((sec, tab) in tabViews) tab.setOnClickListener { showSection(sec) }

        // Keyboard "Search" key = search everything.
        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                findViewById<View>(R.id.inAll).requestFocus()
                search(Section.values().toList())
                true
            } else false
        }

        channelAdapter.onClick = { _, pos -> playChannel(pos, external = false) }
        channelAdapter.onLongClick = { item, pos -> options(item, pos) }
        posterAdapter.onClick = { item, _ -> openItemDetails(this, item) }
        posterAdapter.onLongClick = { item, pos -> options(item, pos) }

        query.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        favs = Section.values().associateWith { store.favorites(it) }
        resultsRv.adapter?.notifyDataSetChanged()
    }

    private fun search(sections: List<Section>) {
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
        summary.text = "Searching ${sections.joinToString(", ") { it.title }}…"
        job = lifecycleScope.launch {
            val found = LinkedHashMap<Section, List<CatalogItem>>()
            val errors = ArrayList<String>()
            for (sec in sections) {
                try {
                    val data = Catalog.load(this@SearchActivity, sec) // from saved lists; downloads only if never loaded
                    val hidden = store.hiddenCategories(sec)
                    found[sec] = withContext(Dispatchers.Default) { data.search(text, hidden, LIMIT) }
                } catch (e: Exception) {
                    errors += "${sec.title}: ${Net.friendlyError(e)}"
                }
            }
            loading.visibility = View.GONE
            results = found
            if (errors.isNotEmpty()) toast(errors.joinToString("\n"), long = true)

            if (sections.size > 1) {
                tabs.visibility = View.VISIBLE
                for ((sec, tab) in tabViews) {
                    tab.text = "${sec.title} (${found[sec]?.size ?: 0}${if ((found[sec]?.size ?: 0) >= LIMIT) "+" else ""})"
                }
                val first = Section.values().firstOrNull { (found[it]?.size ?: 0) > 0 } ?: Section.LIVE
                showSection(first)
            } else {
                tabs.visibility = View.GONE
                showSection(sections.first())
            }
        }
    }

    private fun showSection(sec: Section) {
        shown = sec
        for ((s, tab) in tabViews) tab.isSelected = s == sec
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
        val word = when (sec) { Section.LIVE -> "channel"; Section.MOVIES -> "movie"; Section.SERIES -> "show" }
        summary.text = if (list.size >= LIMIT) "First $LIMIT ${word}s matching \"$lastQuery\" in ${sec.title} – type more to narrow it down"
                       else "${Fmt.count(list.size, word, word + "s")} matching \"$lastQuery\" in ${sec.title}"
        if (list.isEmpty()) {
            empty.text = "Nothing in ${sec.title} matches \"$lastQuery\"."
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            if (tvMode) resultsRv.post { resultsRv.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }

    private fun livePlayable(ch: LiveChannel) =
        Playable(Kind.LIVE, ch.id, ch.name, account.liveUrl(ch.id, store.liveFormat), ch.image, ch.num)

    private fun playChannel(position: Int, external: Boolean) {
        val channels = results[Section.LIVE].orEmpty().filterIsInstance<LiveChannel>()
        if (channels.isEmpty()) return
        Launcher.play(this, channels.map { livePlayable(it) }, position, external = if (external) true else null,
            categoryName = "Search: $lastQuery")
    }

    private fun options(item: CatalogItem, pos: Int) {
        val sec = when (item) { is LiveChannel -> Section.LIVE; is Show -> Section.SERIES; else -> Section.MOVIES }
        ItemOptions.show(this, sec, item, account, playLive = { ext -> playChannel(pos, ext) }) {
            favs = Section.values().associateWith { store.favorites(it) }
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
            val list = results[shown].orEmpty()
            if (pos != RecyclerView.NO_POSITION && pos < list.size) { options(list[pos], pos); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
