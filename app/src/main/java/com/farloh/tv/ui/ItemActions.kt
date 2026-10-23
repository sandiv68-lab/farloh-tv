package com.farloh.tv.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.farloh.tv.data.Account
import com.farloh.tv.data.CatalogItem
import com.farloh.tv.data.Downloads
import com.farloh.tv.data.LiveChannel
import com.farloh.tv.data.Movie
import com.farloh.tv.data.Section
import com.farloh.tv.data.Show
import com.farloh.tv.data.Store
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Launcher
import com.farloh.tv.player.Playable

/** Opens the detail page of a movie or TV show. */
fun openItemDetails(activity: Activity, item: CatalogItem) {
    when (item) {
        is Movie -> activity.startActivity(
            Intent(activity, MovieDetailActivity::class.java)
                .putExtra(MovieDetailActivity.EXTRA_ID, item.id)
                .putExtra(MovieDetailActivity.EXTRA_NAME, item.name)
                .putExtra(MovieDetailActivity.EXTRA_IMAGE, item.image)
                .putExtra(MovieDetailActivity.EXTRA_EXT, item.ext)
                .putExtra(MovieDetailActivity.EXTRA_PLAYLIST, item.playlistId)
        )
        is Show -> activity.startActivity(
            Intent(activity, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_ID, item.id)
                .putExtra(SeriesDetailActivity.EXTRA_NAME, item.name)
                .putExtra(SeriesDetailActivity.EXTRA_IMAGE, item.image)
                .putExtra(SeriesDetailActivity.EXTRA_PLAYLIST, item.playlistId)
        )
    }
}

/** The hold-OK / long-press menu for a channel, movie or TV show. */
object ItemOptions {

    /**
     * @param playLive for channels: plays it (true = external player) with the right channel list.
     * @param onChanged called after favourites change so the screen can redraw.
     */
    fun show(
        activity: Activity,
        section: Section,
        item: CatalogItem,
        account: Account,
        playLive: ((external: Boolean) -> Unit)? = null,
        onChanged: () -> Unit = {},
    ) {
        val store = Store.get(activity)
        val isFav = store.isFavorite(section, item.id, item.playlistId)
        val opts = ArrayList<Pair<String, () -> Unit>>()
        when (item) {
            is LiveChannel -> {
                opts += "Play" to { playLive?.invoke(false); Unit }
                opts += "Open in external player (MX / VLC)" to { playLive?.invoke(true); Unit }
            }
            is Movie -> {
                val p = Playable(Kind.MOVIE, item.id, item.name, account.movieUrl(item.id, item.ext), item.image,
                    searchTitle = item.name, ext = item.ext, playlistId = item.playlistId)
                opts += "Play" to { Launcher.play(activity, listOf(p), 0, external = false) }
                opts += "Open in external player (MX / VLC)" to { Launcher.play(activity, listOf(p), 0, external = true) }
                opts += "Details" to { openItemDetails(activity, item) }
                if (activity is BaseActivity && Downloads.find(activity, p.resumeKey) == null) opts += "Download" to {
                    DownloadPrompt.start(activity, listOf(p), item.name)
                }
            }
            is Show -> opts += "Open" to { openItemDetails(activity, item) }
        }
        opts += (if (isFav) "★  Remove from Favourites" else "☆  Add to Favourites") to {
            val added = store.toggleFavorite(item.id, section, item.playlistId)
            Toast.makeText(activity, if (added) "Added to Favourites" else "Removed from Favourites", Toast.LENGTH_SHORT).show()
            onChanged()
        }
        AlertDialog.Builder(activity)
            .setTitle(item.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }
}
