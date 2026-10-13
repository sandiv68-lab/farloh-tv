package com.farloh.tv.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.farloh.tv.net.Net
import com.farloh.tv.player.Kind
import com.farloh.tv.player.Playable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A movie or episode saved for offline viewing. */
data class DownloadEntry(
    val key: String,          // same as Playable.resumeKey, e.g. MOVIE_123
    val kind: Kind,
    val streamId: String,
    val title: String,
    val showTitle: String?,
    val image: String?,
    val ext: String,
    val playlistId: String,
    val dmId: Long,
    val added: Long,
    val tmdbId: String?,
    val season: Int?,
    val episode: Int?,
) {
    val fileName: String get() = "${kind.name.lowercase()}_${streamId}.${ext.ifBlank { "mp4" }}"
}

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED, MISSING }

class DownloadStatus(val state: DownloadState, val bytes: Long, val total: Long, val reason: Int) {
    val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
}

/** Offline downloads using Android's DownloadManager (works in the background, resumes after network drops). */
object Downloads {

    private fun listFile(ctx: Context) = File(ctx.filesDir, "downloads.json")

    fun folder(ctx: Context): File? =
        ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.resolve("farloh")?.apply { mkdirs() }

    fun file(ctx: Context, e: DownloadEntry): File? = folder(ctx)?.resolve(e.fileName)

    fun freeSpace(ctx: Context): Long = try {
        folder(ctx)?.let { StatFs(it.path).availableBytes } ?: 0L
    } catch (e: Exception) { 0L }

    @Synchronized
    fun list(ctx: Context): List<DownloadEntry> {
        val f = listFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                DownloadEntry(
                    key = o.getString("key"),
                    kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.MOVIE),
                    streamId = o.getString("id"),
                    title = o.optString("title"),
                    showTitle = o.optString("show").ifBlank { null },
                    image = o.optString("image").ifBlank { null },
                    ext = o.optString("ext"),
                    playlistId = o.optString("playlist"),
                    dmId = o.optLong("dm", -1),
                    added = o.optLong("added"),
                    tmdbId = o.optString("tmdb").ifBlank { null },
                    season = if (o.has("season")) o.optInt("season") else null,
                    episode = if (o.has("episode")) o.optInt("episode") else null,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<DownloadEntry>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject().put("key", e.key).put("kind", e.kind.name).put("id", e.streamId).put("title", e.title)
                .put("show", e.showTitle ?: "").put("image", e.image ?: "").put("ext", e.ext)
                .put("playlist", e.playlistId).put("dm", e.dmId).put("added", e.added).put("tmdb", e.tmdbId ?: "")
            e.season?.let { o.put("season", it) }
            e.episode?.let { o.put("episode", it) }
            arr.put(o)
        }
        listFile(ctx).writeText(arr.toString())
    }

    fun find(ctx: Context, key: String): DownloadEntry? = list(ctx).firstOrNull { it.key == key }

    /** Starts a download. Returns null on success or an error message. */
    fun start(ctx: Context, p: Playable): String? {
        if (p.kind == Kind.LIVE) return "Live channels can't be downloaded."
        if (find(ctx, p.resumeKey) != null) return "Already in Downloads."
        val store = Store.get(ctx)
        val playlist = store.activePlaylist ?: return "Not signed in."
        val ext = p.ext?.ifBlank { null } ?: p.url.substringAfterLast('.', "mp4").substringBefore('?')
        val entry = DownloadEntry(
            key = p.resumeKey, kind = p.kind, streamId = p.id, title = p.title, showTitle = p.subtitle,
            image = p.logo, ext = ext, playlistId = playlist.id, dmId = -1, added = System.currentTimeMillis(),
            tmdbId = p.tmdbId, season = p.season, episode = p.episode,
        )
        val folder = folder(ctx) ?: return "No storage available for downloads on this device."
        if (freeSpace(ctx) < 500L * 1024 * 1024) return "Not enough free space (less than 500 MB left)."
        val id = enqueue(ctx, entry, p.url, folder) ?: return "This device's download service is turned off or missing."
        save(ctx, list(ctx) + entry.copy(dmId = id))
        return null
    }

    private fun enqueue(ctx: Context, e: DownloadEntry, url: String, folder: File): Long? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        folder.resolve(e.fileName).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(listOfNotNull(e.showTitle, e.title).joinToString(" — "))
            .setDescription("Farloh TV")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, "farloh/${e.fileName}")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", Net.USER_AGENT)
        return try { dm.enqueue(request) } catch (ex: Exception) { null }
    }

    /** Restarts a failed download using the playlist's current login. */
    fun retry(ctx: Context, e: DownloadEntry): String? {
        val store = Store.get(ctx)
        val acc = store.playlists().firstOrNull { it.id == e.playlistId }?.account ?: store.account
            ?: return "The playlist for this download was removed."
        val url = if (e.kind == Kind.EPISODE) acc.episodeUrl(e.streamId, e.ext) else acc.movieUrl(e.streamId, e.ext)
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.let { dm -> runCatching { dm.remove(e.dmId) } }
        val folder = folder(ctx) ?: return "No storage available."
        val id = enqueue(ctx, e, url, folder) ?: return "This device's download service is turned off or missing."
        save(ctx, list(ctx).map { if (it.key == e.key) it.copy(dmId = id) else it })
        return null
    }

    fun delete(ctx: Context, e: DownloadEntry) {
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.let { dm -> runCatching { dm.remove(e.dmId) } }
        file(ctx, e)?.delete()
        File(ctx.filesDir, "subs/${e.key}.srt").delete()
        save(ctx, list(ctx).filter { it.key != e.key })
    }

    fun status(ctx: Context, entries: List<DownloadEntry>): Map<String, DownloadStatus> {
        val out = HashMap<String, DownloadStatus>()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        val byDm = entries.filter { it.dmId >= 0 }.associateBy { it.dmId }
        if (dm != null && byDm.isNotEmpty()) {
            try {
                dm.query(DownloadManager.Query().setFilterById(*byDm.keys.toLongArray()))?.use { c ->
                    val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                    val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val iBytes = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val iReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                    while (c.moveToNext()) {
                        val e = byDm[c.getLong(iId)] ?: continue
                        val state = when (c.getInt(iStatus)) {
                            DownloadManager.STATUS_PENDING -> DownloadState.QUEUED
                            DownloadManager.STATUS_RUNNING -> DownloadState.RUNNING
                            DownloadManager.STATUS_PAUSED -> DownloadState.PAUSED
                            DownloadManager.STATUS_SUCCESSFUL -> DownloadState.DONE
                            else -> DownloadState.FAILED
                        }
                        out[e.key] = DownloadStatus(state, c.getLong(iBytes), c.getLong(iTotal), c.getInt(iReason))
                    }
                }
            } catch (ex: Exception) {
                // fall through to file checks
            }
        }
        for (e in entries) {
            if (out.containsKey(e.key)) continue
            val f = file(ctx, e)
            out[e.key] = if (f != null && f.exists() && f.length() > 0) DownloadStatus(DownloadState.DONE, f.length(), f.length(), 0)
                         else DownloadStatus(DownloadState.MISSING, 0, 0, 0)
        }
        return out
    }

    /** Playable that plays the downloaded file (keeps the same resume position key). */
    fun playable(ctx: Context, e: DownloadEntry): Playable? {
        val f = file(ctx, e) ?: return null
        if (!f.exists()) return null
        return Playable(
            kind = e.kind, id = e.streamId, title = e.title, url = Uri.fromFile(f).toString(), logo = e.image,
            subtitle = e.showTitle, searchTitle = e.showTitle ?: e.title, tmdbId = e.tmdbId,
            season = e.season, episode = e.episode, ext = e.ext,
        )
    }
}
