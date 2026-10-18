package com.farloh.tv.data

import android.content.Context
import android.os.StatFs
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.farloh.tv.FarlohApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What Farloh TV keeps on the device, and how to clear each part (Settings → Storage). */
object StorageInfo {

    class Sizes(
        val playlistActive: Long,
        val playlistAll: Long,
        val pictures: Long,
        val subtitles: Long,
        val updates: Long,
        val downloads: Long,
        val free: Long,
    ) {
        /** Things that are safe to clear: they come back by themselves when needed. */
        val clearable: Long get() = pictures + updates
    }

    private fun catalogRoot(ctx: Context) = File(ctx.filesDir, "catalog")
    private fun pictures(ctx: Context) = File(ctx.cacheDir, "images")
    private fun subs(ctx: Context) = File(ctx.filesDir, "subs")
    fun updates(ctx: Context) = File(ctx.cacheDir, "updates")

    suspend fun measure(ctx: Context): Sizes = withContext(Dispatchers.IO) {
        val active = Store.get(ctx).activePlaylist?.id
        Sizes(
            playlistActive = active?.let { size(File(catalogRoot(ctx), it)) } ?: 0L,
            playlistAll = size(catalogRoot(ctx)),
            pictures = size(pictures(ctx)),
            subtitles = size(subs(ctx)),
            updates = size(updates(ctx)),
            downloads = Downloads.folder(ctx)?.let { size(it) } ?: 0L,
            free = try { StatFs(ctx.filesDir.path).availableBytes } catch (e: Exception) { 0L },
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearPictures(ctx: Context) = withContext(Dispatchers.IO) {
        val loader = ctx.imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        pictures(ctx).listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun clearSubtitles(ctx: Context) = withContext(Dispatchers.IO) {
        // Keep subtitles that belong to downloaded movies/episodes so they still work offline.
        val keep = Downloads.list(ctx).map { "${it.key}.srt" }.toSet()
        subs(ctx).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    suspend fun clearUpdates(ctx: Context) = withContext(Dispatchers.IO) { updates(ctx).deleteRecursively() }

    suspend fun clearAllPlaylists(ctx: Context) = withContext(Dispatchers.IO) {
        Catalog.clearMemory()
        catalogRoot(ctx).deleteRecursively()
        Section.values().forEach { Store.get(ctx).setItemCount(it, -1) }
    }

    /** Applies a new picture cache size straight away (no restart needed). */
    fun applyPictureCacheSize(ctx: Context) {
        val app = ctx.applicationContext as FarlohApp
        Coil.setImageLoader(app.newImageLoader())
    }

    private fun size(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { size(it) } ?: 0L
    }
}
