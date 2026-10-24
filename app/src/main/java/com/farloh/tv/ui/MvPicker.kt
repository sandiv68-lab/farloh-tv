package com.farloh.tv.ui

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.farloh.tv.data.Catalog
import com.farloh.tv.data.CatalogItem
import com.farloh.tv.data.Episode
import com.farloh.tv.data.LiveChannel
import com.farloh.tv.data.Movie
import com.farloh.tv.data.Playlist
import com.farloh.tv.data.Section
import com.farloh.tv.data.SectionData
import com.farloh.tv.data.Show
import com.farloh.tv.data.Xtream
import com.farloh.tv.net.Net
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multiview: choose what plays in a window, step by step:
 * playlist (combined view) → Live TV / Movies / TV Shows → group → channel / movie / show → season → episode.
 * "Back" goes one step back. Hidden groups (parental control) stay hidden.
 */
class MvPicker(
    private val activity: BaseActivity,
    /** The list the choice came from (for channel up/down and next episode), the chosen position, and a name for it. */
    private val onPicked: (items: List<Playable>, index: Int, listName: String) -> Unit,
) {
    private val store get() = activity.store

    fun start() {
        val playlists = store.viewPlaylists()
        when {
            playlists.isEmpty() -> activity.toast("No playlist")
            playlists.size == 1 -> chooseSection(playlists[0], back = null)
            else -> list("Which playlist?", playlists.map { it.name }, back = null) { i -> chooseSection(playlists[i]) { start() } }
        }
    }

    // ---------------- Steps ----------------

    private fun chooseSection(pl: Playlist, back: (() -> Unit)?) {
        val sections = Section.values().toList()
        val title = if (store.viewPlaylists().size > 1) "${pl.name}: what to watch?" else "What to watch?"
        list(title, sections.map { it.title }, back) { i -> load(pl, sections[i]) { chooseSection(pl, back) } }
    }

    private fun load(pl: Playlist, sec: Section, back: () -> Unit) {
        activity.toast("Loading ${sec.title}…")
        activity.lifecycleScope.launch {
            val data = try {
                Catalog.load(activity, sec, pl.id)
            } catch (e: Exception) {
                activity.toast(Net.friendlyError(e), long = true); back(); return@launch
            }
            chooseGroup(pl, sec, data, back)
        }
    }

    private fun chooseGroup(pl: Playlist, sec: Section, data: SectionData, back: () -> Unit) {
        val hidden = store.hiddenCategories(sec, pl.id)
        val visible = if (hidden.isEmpty()) data.items else data.items.filter { it.categoryId !in hidden }
        val rows = ArrayList<Pair<String, () -> Unit>>()
        val again = { chooseGroup(pl, sec, data, back) }

        rows += "Search by name…" to {
            Dialogs.input(activity, "Search ${sec.title}") { q ->
                val found = data.search(q, hidden, 500)
                if (found.isEmpty()) { activity.toast("Nothing matches \"$q\""); again() }
                else chooseItem(pl, sec, "\"$q\"", found, again)
            }
        }
        val favIds = store.favorites(sec, pl.id)
        val favs = visible.filter { it.id in favIds }
        if (favs.isNotEmpty()) rows += "★  Favourites  (${favs.size})" to { chooseItem(pl, sec, "Favourites", favs, again) }
        if (sec == Section.LIVE) {
            val byId = visible.associateBy { it.id }
            val recent = store.history(pl.id).mapNotNull { byId[it] }
            if (recent.isNotEmpty()) rows += "Recently watched  (${recent.size})" to { chooseItem(pl, sec, "Recently watched", recent, again) }
        }
        rows += "All  (${visible.size})" to { chooseItem(pl, sec, "All ${sec.title}", visible, again) }
        for (c in data.categories) {
            if (c.id in hidden) continue
            val list = data.byCategory[c.id].orEmpty()
            if (list.isNotEmpty()) rows += "${c.name}  (${list.size})" to { chooseItem(pl, sec, c.name, list, again) }
        }
        list(sec.title, rows.map { it.first }, back) { i -> rows[i].second() }
    }

    private fun chooseItem(pl: Playlist, sec: Section, groupName: String, items: List<CatalogItem>, back: () -> Unit) {
        val labels = items.map { if (it is LiveChannel && it.num.isNotBlank()) "${it.num}   ${it.name}" else it.name }
        list(groupName, labels, back) { i ->
            when (val item = items[i]) {
                is Show -> episodes(pl, item) { chooseItem(pl, sec, groupName, items, back) }
                else -> {
                    // Channels and movies: the whole group is the list (channel up/down in the window).
                    val list = items.mapNotNull { playable(pl, it) }
                    val index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                    onPicked(list, index, groupName)
                }
            }
        }
    }

    private fun episodes(pl: Playlist, show: Show, back: () -> Unit) {
        val account = store.accountFor(pl.id) ?: return
        activity.toast("Loading ${show.name}…")
        activity.lifecycleScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { Xtream.seriesInfo(account, show.id) }
            } catch (e: Exception) {
                activity.toast(Net.friendlyError(e), long = true); back(); return@launch
            }
            val seasons = info.episodes.keys.sorted()
            if (seasons.isEmpty()) { activity.toast("No episodes found"); back(); return@launch }
            fun chooseEpisode(season: Int, backToSeasons: () -> Unit) {
                val eps = info.episodes[season].orEmpty()
                list("${show.name} – ${if (season == 0) "Specials" else "Season $season"}", eps.map { episodeName(it) }, backToSeasons) { i ->
                    val list = eps.map { episode(pl, show, it, info.cover) }
                    onPicked(list, i, show.name)
                }
            }
            if (seasons.size == 1) chooseEpisode(seasons[0], back)
            else {
                lateinit var chooseSeason: () -> Unit
                chooseSeason = {
                    list(show.name, seasons.map { if (it == 0) "Specials" else "Season $it" }, back) { i -> chooseEpisode(seasons[i], chooseSeason) }
                }
                chooseSeason()
            }
        }
    }

    // ---------------- Playables ----------------

    private fun playable(pl: Playlist, item: CatalogItem): Playable? {
        val account = store.accountFor(pl.id) ?: return null
        return when (item) {
            is LiveChannel -> Playable(Kind.LIVE, item.id, item.name, account.liveUrl(item.id, store.liveFormat), item.image, item.num,
                playlistId = pl.id)
            is Movie -> Playable(Kind.MOVIE, item.id, item.name, account.movieUrl(item.id, item.ext), item.image,
                searchTitle = item.name, ext = item.ext, playlistId = pl.id)
            else -> null
        }
    }

    private fun episodeName(ep: Episode): String {
        val label = "S${ep.season} E${ep.num}"
        return if (ep.title.isBlank()) label else "$label · ${ep.title}"
    }

    private fun episode(pl: Playlist, show: Show, ep: Episode, cover: String?): Playable {
        val account = store.accountFor(pl.id)!!
        return Playable(Kind.EPISODE, ep.id, episodeName(ep), account.episodeUrl(ep.id, ep.ext), ep.image ?: cover ?: show.image,
            subtitle = show.name, searchTitle = show.name, season = ep.season, episode = ep.num, ext = ep.ext,
            playlistId = pl.id, seriesId = show.id)
    }

    // ---------------- Dialog ----------------

    private fun list(title: String, labels: List<String>, back: (() -> Unit)?, onChoose: (Int) -> Unit) {
        if (labels.isEmpty()) { activity.toast("Nothing here"); back?.invoke(); return }
        val b = AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> onChoose(which) }
        if (back != null) b.setNegativeButton("Back") { _, _ -> back() } else b.setNegativeButton("Cancel", null)
        b.show()
    }
}
