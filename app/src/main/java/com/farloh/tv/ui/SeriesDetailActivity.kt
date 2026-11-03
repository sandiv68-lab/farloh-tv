package com.farloh.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farloh.tv.R
import com.farloh.tv.data.Account
import com.farloh.tv.data.DownloadState
import com.farloh.tv.data.Downloads
import com.farloh.tv.data.Episode
import com.farloh.tv.data.SeriesInfo
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Launcher
import com.farloh.tv.player.Playable
import kotlinx.coroutines.launch

class SeriesDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_PLAYLIST = "playlist"
    }

    private lateinit var account: Account
    /** Which playlist this show belongs to (matters in the combined view). */
    private var pid: String? = null
    private lateinit var seriesId: String
    private var showName = ""
    private var cover: String? = null

    private var info: SeriesInfo? = null
    private var seasons: List<Int> = emptyList()
    private var currentSeason = 0

    private val seasonAdapter = SimpleAdapter<Int>(R.layout.chip_season, 1.05f) { v, season, _ ->
        (v as TextView).text = seasonLabel(season)
        v.isSelected = season == currentSeason
    }

    private val episodeAdapter = SimpleAdapter<Episode>(R.layout.row_episode, 1.02f) { v, ep, _ -> bindEpisode(v, ep) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireAccount() ?: return
        pid = intent.getStringExtra(EXTRA_PLAYLIST)?.ifEmpty { null }
        account = store.accountFor(pid) ?: return
        seriesId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        showName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        cover = intent.getStringExtra(EXTRA_IMAGE)
        setContentView(R.layout.activity_series)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.fav).setOnClickListener {
            store.toggleFavorite(seriesId, com.farloh.tv.data.Section.SERIES, pid)
            updateFav()
        }
        updateFav()
        findViewById<View>(R.id.more).setOnClickListener { moreOptions() }
        findViewById<TextView>(R.id.title).text = showName
        findViewById<ImageView>(R.id.poster).loadImage(cover)
        // Short screens: smaller poster so the info column needs less scrolling.
        if (resources.configuration.screenHeightDp < 420) {
            val poster = findViewById<View>(R.id.poster)
            poster.layoutParams = poster.layoutParams.apply { width = dp(80); height = dp(120) }
        }

        val seasonsRv = findViewById<RecyclerView>(R.id.seasons)
        seasonsRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seasonsRv.itemAnimator = null
        seasonsRv.adapter = seasonAdapter
        seasonAdapter.onClick = { s, _ -> selectSeason(s) }
        seasonAdapter.onFocus = { s, _ -> selectSeason(s) }

        val episodesRv = findViewById<RecyclerView>(R.id.episodes)
        episodesRv.layoutManager = LinearLayoutManager(this)
        episodesRv.itemAnimator = null
        episodesRv.adapter = episodeAdapter
        episodeAdapter.onClick = { _, pos -> playEpisode(pos, external = null) }
        episodeAdapter.onLongClick = { ep, pos ->
            val key = episodeKey(ep)
            val downloaded = Downloads.find(this, key) != null
            val watched = store.isWatched(key, pid)
            AlertDialog.Builder(this)
                .setTitle(episodeTitle(ep))
                .setItems(arrayOf(
                    "Play", "Play from start", "Open in external",
                    if (watched) "Mark as unwatched" else "Mark as watched",
                    if (downloaded) "Open Downloads" else "Download episode",
                    "Download all of ${seasonLabel(currentSeason)}",
                )) { _, which ->
                    when (which) {
                        0 -> playEpisode(pos, external = false)
                        1 -> playEpisode(pos, external = false, resume = false)
                        2 -> playEpisode(pos, external = true)
                        3 -> { store.setWatched(listOf(key), !watched, pid); episodeAdapter.notifyItemChanged(pos) }
                        4 -> if (downloaded) startActivity(android.content.Intent(this, DownloadsActivity::class.java))
                             else downloadEpisodes(listOf(ep))
                        5 -> downloadEpisodes(episodeAdapter.items)
                    }
                }
                .show()
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        episodeAdapter.notifyDataSetChanged() // refresh watched bars
    }

    private fun load() {
        lifecycleScope.launch {
            val empty = findViewById<TextView>(R.id.empty)
            try {
                val i = Xtream.seriesInfo(account, seriesId)
                info = i
                // Note which show / season / number each episode is (renewals), and take over progress
                // waiting from a renewal or Carry over.
                runCatching {
                    store.tagEpisodes(pid, seriesId, i.episodes.values.flatten())
                    com.farloh.tv.data.Renewal.applyEpisodes(this@SeriesDetailActivity, pid, seriesId, i.episodes)
                }
                showInfo(i)
                seasons = i.episodes.keys.toList()
                if (seasons.isEmpty()) {
                    empty.text = "No episodes available."
                    empty.visibility = View.VISIBLE
                } else {
                    // Start on the episode to watch next (or the one left unfinished), on its season.
                    val start = startEpisode(i)
                    currentSeason = start?.season?.takeIf { it in seasons } ?: seasons.first()
                    seasonAdapter.submit(seasons)
                    val eps = i.episodes[currentSeason].orEmpty()
                    episodeAdapter.submit(eps)
                    val at = eps.indexOfFirst { it.id == start?.id }.coerceAtLeast(0)
                    val seasonsRv = findViewById<RecyclerView>(R.id.seasons)
                    seasonsRv.scrollToPosition(seasons.indexOf(currentSeason).coerceAtLeast(0))
                    val rv = findViewById<RecyclerView>(R.id.episodes)
                    (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at, 0)
                    rv.post { rv.layoutManager?.findViewByPosition(at)?.requestFocus() }
                }
            } catch (e: Exception) {
                empty.text = Net.friendlyError(e)
                empty.visibility = View.VISIBLE
            } finally {
                findViewById<View>(R.id.loading).visibility = View.GONE
            }
        }
    }

    private fun showInfo(i: SeriesInfo) {
        if (showName.isBlank() && i.title.isNotBlank()) {
            showName = i.title
            findViewById<TextView>(R.id.title).text = showName
        }
        if (cover.isNullOrBlank() && i.cover != null) findViewById<ImageView>(R.id.poster).loadImage(i.cover)
        i.backdrop?.let { findViewById<ImageView>(R.id.backdrop).loadImage(it) }
        val rating = i.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }
        val seasonCount = i.episodes.size
        findViewById<TextView>(R.id.meta).text = listOf(
            i.year, rating, i.genre, if (seasonCount > 0) "$seasonCount season(s)" else null,
        ).filter { !it.isNullOrBlank() }.joinToString("   •   ")
        findViewById<TextView>(R.id.plot).text = i.plot
    }

    private fun selectSeason(season: Int) {
        if (season == currentSeason) return
        currentSeason = season
        // Update the highlight in place (redrawing the chips while the remote moves along them could stop the row scrolling).
        val rv = findViewById<RecyclerView>(R.id.seasons)
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val pos = rv.getChildAdapterPosition(child)
            if (pos in seasons.indices) child.isSelected = seasons[pos] == season
        }
        episodeAdapter.submit(info?.episodes?.get(season).orEmpty())
        findViewById<RecyclerView>(R.id.episodes).scrollToPosition(0)
    }

    /**
     * TV remote: Left/Right along the seasons row always reaches the next season, scrolling the row as needed
     * (Android's own focus search could stop at the last fully visible season, e.g. season 7).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        ) {
            val rv = findViewById<RecyclerView>(R.id.seasons)
            val focused = currentFocus
            if (focused != null && focused.parent === rv) {
                val pos = rv.getChildAdapterPosition(focused)
                val target = pos + if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (pos != RecyclerView.NO_POSITION && target in seasons.indices) {
                    val next = rv.layoutManager?.findViewByPosition(target)
                    if (next != null && isFullyShown(rv, next)) {
                        next.requestFocus()
                    } else {
                        rv.scrollToPosition(target)
                        rv.post { (rv.layoutManager?.findViewByPosition(target) ?: return@post).requestFocus() }
                    }
                    return true
                }
                if (pos != RecyclerView.NO_POSITION && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return true // end of the row
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isFullyShown(rv: RecyclerView, v: View): Boolean =
        v.left >= rv.paddingLeft && v.right <= rv.width - rv.paddingRight

    private fun updateFav() {
        val fav = store.isFavorite(com.farloh.tv.data.Section.SERIES, seriesId, pid)
        findViewById<ImageView>(R.id.fav).apply {
            setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
            setColorFilter(androidx.core.content.ContextCompat.getColor(this@SeriesDetailActivity, if (fav) R.color.accent else R.color.text))
        }
    }

    private fun episodeKey(ep: Episode) = "${Kind.EPISODE.name}_${ep.id}"

    /**
     * The episode to start on: the one watched last if it isn't finished, otherwise the one after it
     * (the last one stays if there's nothing after it). Specials only count when you watched one last.
     * Null = never watched (start at the top).
     */
    private fun startEpisode(i: SeriesInfo): Episode? {
        val order = i.episodes.keys.filter { it != 0 }.sorted() + i.episodes.keys.filter { it == 0 }
        val all = order.flatMap { s -> i.episodes[s].orEmpty().sortedBy { it.num } }
        if (all.isEmpty()) return null
        val lastId = store.watchHistory(com.farloh.tv.data.Section.SERIES, pid).firstOrNull { it.id == seriesId }?.episode
        val last = all.firstOrNull { it.id == lastId }
            // Not in Recently watched (e.g. history off): the last episode with any progress.
            ?: all.lastOrNull { store.progress(episodeKey(it), pid) > 0 }
            ?: return null
        if (!store.isWatched(episodeKey(last), pid)) return last
        val pool = if (last.season == 0) all else all.filter { it.season != 0 }
        val idx = pool.indexOf(last)
        return pool.getOrNull(idx + 1) ?: last
    }

    /** ⋮ More: mark the season watched / unwatched, remove the show from Recently watched. */
    private fun moreOptions() {
        val section = com.farloh.tv.data.Section.SERIES
        val season = seasonLabel(currentSeason)
        val keys = info?.episodes?.get(currentSeason).orEmpty().map { episodeKey(it) }
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (keys.isNotEmpty()) {
            opts += "Mark $season as watched" to {
                store.setWatched(keys, true, pid); episodeAdapter.notifyDataSetChanged(); toast("$season marked as watched")
            }
            opts += "Mark $season as unwatched" to {
                store.setWatched(keys, false, pid); episodeAdapter.notifyDataSetChanged(); toast("$season marked as unwatched")
            }
        }
        if (store.inHistory(section, seriesId, pid)) opts += "Remove from Recently watched" to {
            store.removeWatched(section, seriesId, pid); toast("Removed from Recently watched")
        }
        if (opts.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(showName)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun seasonLabel(season: Int) = if (season == 0) "Specials" else "Season $season"

    private fun remotePlayable(ep: Episode) = Playable(
        Kind.EPISODE, ep.id, episodeTitle(ep), account.episodeUrl(ep.id, ep.ext), ep.image ?: cover,
        subtitle = showName, searchTitle = showName, tmdbId = info?.tmdbId, year = info?.year?.ifBlank { null },
        season = ep.season, episode = ep.num, ext = ep.ext, playlistId = pid, seriesId = seriesId, cover = showCover(),
    )

    /** The show's poster: the one from the list, else the one from the show's details. */
    private fun showCover() = cover?.takeIf { it.isNotBlank() } ?: info?.cover

    private fun downloadEpisodes(eps: List<Episode>) {
        val title = if (eps.size == 1) "$showName ${episodeTitle(eps[0])}" else "$showName – ${seasonLabel(currentSeason)}"
        DownloadPrompt.start(this, eps.map { remotePlayable(it) }, title) { episodeAdapter.notifyDataSetChanged() }
    }

    private fun episodeTitle(ep: Episode): String {
        val label = "S${ep.season} E${ep.num}"
        return if (ep.title.isBlank()) label else "$label · ${ep.title}"
    }

    private fun bindEpisode(v: View, ep: Episode) {
        v.findViewById<ImageView>(R.id.thumb).loadImage(ep.image ?: cover)
        v.findViewById<TextView>(R.id.title).text = episodeTitle(ep)
        val dl = Downloads.find(this, "${Kind.EPISODE.name}_${ep.id}")
        v.findViewById<TextView>(R.id.meta).text = listOfNotNull(
            ep.duration.ifBlank { null },
            dl?.let { "⬇ in Downloads" },
        ).joinToString("  •  ")
        v.findViewById<TextView>(R.id.plot).text = ep.plot
        val bar = v.findViewById<ProgressBar>(R.id.watched)
        val progress = store.progress(episodeKey(ep), pid)
        if (progress > 0) {
            bar.progress = progress
            bar.visibility = View.VISIBLE
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun playEpisode(position: Int, external: Boolean?, resume: Boolean = true) {
        val eps = episodeAdapter.items
        val local = Downloads.list(this).associateBy { it.key }
        val states = Downloads.status(this, local.values.toList())
        val queue = eps.map { ep ->
            val key = "${Kind.EPISODE.name}_${ep.id}"
            val entry = local[key]
            val offline = if (entry != null && states[key]?.state == DownloadState.DONE) Downloads.playable(this, entry) else null
            offline?.copy(tmdbId = info?.tmdbId, seriesId = seriesId, cover = showCover(), subtitle = offline.subtitle ?: showName) ?: remotePlayable(ep)
        }
        Launcher.play(this, queue, position, external = external, resume = resume, categoryName = showName)
    }
}
