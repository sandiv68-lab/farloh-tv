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
    }

    private lateinit var account: Account
    private lateinit var seriesId: String
    private var showName = ""
    private var cover: String? = null

    private var info: SeriesInfo? = null
    private var seasons: List<Int> = emptyList()
    private var currentSeason = 0

    private val seasonAdapter = SimpleAdapter<Int>(R.layout.chip_season, 1.05f) { v, season, _ ->
        (v as TextView).text = if (season == 0) "Specials" else "Season $season"
        v.isSelected = season == currentSeason
    }

    private val episodeAdapter = SimpleAdapter<Episode>(R.layout.row_episode, 1.02f) { v, ep, _ -> bindEpisode(v, ep) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = requireAccount() ?: return
        seriesId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        showName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        cover = intent.getStringExtra(EXTRA_IMAGE)
        setContentView(R.layout.activity_series)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title).text = showName
        findViewById<ImageView>(R.id.poster).loadImage(cover)
        if (screenWidthDp < 600) {
            val poster = findViewById<View>(R.id.poster)
            poster.layoutParams = poster.layoutParams.apply { width = dp(80); height = dp(120) }
            findViewById<TextView>(R.id.title).textSize = 20f
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
            AlertDialog.Builder(this)
                .setTitle(episodeTitle(ep))
                .setItems(arrayOf("Play", "Play from start", "Play in MX Player / VLC")) { _, which ->
                    when (which) {
                        0 -> playEpisode(pos, external = false)
                        1 -> playEpisode(pos, external = false, resume = false)
                        2 -> playEpisode(pos, external = true)
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
                showInfo(i)
                seasons = i.episodes.keys.toList()
                if (seasons.isEmpty()) {
                    empty.text = "No episodes available."
                    empty.visibility = View.VISIBLE
                } else {
                    currentSeason = seasons.first()
                    seasonAdapter.submit(seasons)
                    episodeAdapter.submit(i.episodes[currentSeason].orEmpty())
                    findViewById<RecyclerView>(R.id.episodes).post {
                        findViewById<RecyclerView>(R.id.episodes).layoutManager?.findViewByPosition(0)?.requestFocus()
                    }
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
        val old = seasons.indexOf(currentSeason)
        currentSeason = season
        if (old >= 0) seasonAdapter.notifyItemChanged(old)
        seasonAdapter.notifyItemChanged(seasons.indexOf(season))
        episodeAdapter.submit(info?.episodes?.get(season).orEmpty())
        findViewById<RecyclerView>(R.id.episodes).scrollToPosition(0)
    }

    private fun episodeTitle(ep: Episode): String {
        val label = "S${ep.season} E${ep.num}"
        return if (ep.title.isBlank()) label else "$label · ${ep.title}"
    }

    private fun bindEpisode(v: View, ep: Episode) {
        v.findViewById<ImageView>(R.id.thumb).loadImage(ep.image ?: cover)
        v.findViewById<TextView>(R.id.title).text = episodeTitle(ep)
        v.findViewById<TextView>(R.id.meta).text = ep.duration
        v.findViewById<TextView>(R.id.plot).text = ep.plot
        val bar = v.findViewById<ProgressBar>(R.id.watched)
        val progress = store.progress("${Kind.EPISODE.name}_${ep.id}")
        if (progress > 0) {
            bar.progress = progress
            bar.visibility = View.VISIBLE
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun playEpisode(position: Int, external: Boolean?, resume: Boolean = true) {
        val eps = episodeAdapter.items
        val queue = eps.map {
            Playable(Kind.EPISODE, it.id, episodeTitle(it), account.episodeUrl(it.id, it.ext), it.image ?: cover, subtitle = showName)
        }
        Launcher.play(this, queue, position, external = external, resume = resume, categoryName = showName)
    }
}
