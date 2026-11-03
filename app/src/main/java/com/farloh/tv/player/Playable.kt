package com.farloh.tv.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.farloh.tv.data.Section
import com.farloh.tv.data.Store
import com.farloh.tv.data.WatchEntry
import com.farloh.tv.ui.PlayerActivity

enum class Kind { LIVE, MOVIE, EPISODE }

data class Playable(
    val kind: Kind,
    val id: String,
    val title: String,
    val url: String,
    val logo: String? = null,
    val num: String? = null,
    val subtitle: String? = null,
    // Used to find online subtitles and to rebuild download links.
    val searchTitle: String? = null,
    val tmdbId: String? = null,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val ext: String? = null,
    /** Playlist it belongs to (login, favourites, resume position); null = the one in use. */
    val playlistId: String? = null,
    /** Episodes: the TV show they belong to (for the ★ button in the player). */
    val seriesId: String? = null,
    /** Episodes: the TV show's poster (for Recently watched). */
    val cover: String? = null,
) {
    val resumeKey: String get() = "${kind.name}_$id"
}

/** Movies & TV Shows → Recently watched: notes what's being played (not live channels or recordings). */
object WatchLog {
    fun note(ctx: Context, p: Playable) {
        if (p.id.startsWith("rec_")) return
        val store = Store.get(ctx)
        val now = System.currentTimeMillis()
        when (p.kind) {
            Kind.MOVIE -> store.noteWatched(Section.MOVIES, WatchEntry(p.id, p.title, p.logo, now), p.playlistId)
            Kind.EPISODE -> {
                val show = p.seriesId ?: return // e.g. played from Downloads: the show isn't known
                store.noteWatched(Section.SERIES, WatchEntry(show, p.subtitle ?: p.searchTitle.orEmpty(), p.cover, now, episode = p.id),
                    p.playlistId, fallbackImage = p.logo)
            }
            Kind.LIVE -> Unit
        }
    }
}

/** What the player should play next. Kept in memory instead of an Intent so long channel lists are cheap. */
object PlayQueue {
    var items: List<Playable> = emptyList()
    var index: Int = 0
    var resume: Boolean = true
    var categoryName: String = ""
}

object Launcher {
    fun play(
        activity: Activity,
        items: List<Playable>,
        index: Int,
        external: Boolean? = null,
        resume: Boolean = true,
        categoryName: String = "",
    ) {
        if (items.isEmpty()) return
        val i = index.coerceIn(0, items.size - 1)
        if (external ?: Store.get(activity).externalByDefault) {
            WatchLog.note(activity, items[i])
            External.open(activity, items[i])
            return
        }
        PlayQueue.items = items
        PlayQueue.index = i
        PlayQueue.resume = resume
        PlayQueue.categoryName = categoryName
        activity.startActivity(Intent(activity, PlayerActivity::class.java))
    }
}

/** Hands a stream to MX Player (Pro or free), VLC or mpv, falling back to the system chooser. */
object External {
    private val PREFERRED = listOf(
        "com.mxtech.videoplayer.pro",
        "com.mxtech.videoplayer.ad",
        "org.videolan.vlc",
        "is.xyz.mpv",
    )

    fun open(context: Context, p: Playable) {
        val local = p.url.startsWith("file://")
        val uri = if (local) {
            try {
                androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".files", java.io.File(Uri.parse(p.url).path ?: ""))
            } catch (e: Exception) {
                Toast.makeText(context, "Can't share this file with another player.", Toast.LENGTH_LONG).show()
                return
            }
        } else Uri.parse(p.url)
        for (pkg in PREFERRED) {
            try {
                context.startActivity(intent(uri, p).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                // not installed, try next
            } catch (e: SecurityException) {
                // ignore
            }
        }
        try {
            context.startActivity(Intent.createChooser(intent(uri, p), "Open with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No external video player found. Install MX Player or VLC.", Toast.LENGTH_LONG).show()
        }
    }

    private fun intent(uri: Uri, p: Playable) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/*")
        .putExtra("title", p.title)
        .putExtra("secure_uri", true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
