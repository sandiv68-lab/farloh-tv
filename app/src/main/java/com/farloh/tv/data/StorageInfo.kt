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
        /** Saved TV guide for EPG search (per playlist). */
        val guide: Long = 0L,
        /** Leftovers from interrupted downloads and other temporary files. */
        val temp: Long = 0L,
    ) {
        /** Things that are safe to clear: they come back by themselves when needed. */
        val clearable: Long get() = pictures + updates + guide + temp + subtitles
    }

    /** EPG search data and subtitles are removed after this long without being used. */
    private const val UNUSED_DAYS = 30L
    private const val DAY = 24 * 3600_000L

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
            guide = guideFiles(ctx).sumOf { it.length() },
            temp = tempFiles(ctx).sumOf { size(it) },
        )
    }

    private fun guideFiles(ctx: Context): List<File> =
        catalogRoot(ctx).listFiles()?.mapNotNull { File(it, "guide_index.tsv").takeIf { f -> f.exists() } }.orEmpty()

    /** Half-finished downloads (".part", ".new", ".cat") and anything in the temporary folder except pictures. */
    private fun tempFiles(ctx: Context): List<File> {
        val out = ArrayList<File>()
        fun scan(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) scan(f)
                else if (f.name.endsWith(".part") || f.name.endsWith(".new") || f.name.endsWith(".cat")) out += f
            }
        }
        scan(ctx.filesDir)
        ctx.cacheDir.listFiles()?.filter { it.name != "images" && it.name != "updates" }?.let { out += it }
        return out
    }

    /**
     * Runs when the app starts (in the background): removes update packages (already done at start), temporary
     * leftovers, saved lists of playlists that no longer exist, and EPG search data / subtitles not used for 30 days.
     * Pictures look after themselves (oldest removed when the size limit is reached).
     */
    suspend fun autoClean(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            // Only leftovers that aren't being written right now.
            val old = System.currentTimeMillis() - DAY
            tempFiles(ctx).filter { it.lastModified() < old }.forEach { it.deleteRecursively() }
            val ids = Store.get(ctx).playlists().map { it.id }.toSet()
            catalogRoot(ctx).listFiles()?.filter { it.isDirectory && it.name !in ids && it.name != "none" }?.forEach { it.deleteRecursively() }
            val unused = System.currentTimeMillis() - UNUSED_DAYS * DAY
            for (f in guideFiles(ctx)) if (GuideIndex.lastUsed(f) < unused) GuideIndex.deleteFile(f)
            val keep = Downloads.list(ctx).map { "${it.key}.srt" }.toSet()
            subs(ctx).listFiles()?.filter { it.name !in keep && it.lastModified() < unused }?.forEach { it.delete() }
        }
    }

    /** Settings → Storage → Clear all cache. Playlists, favourites, history and downloads are kept. */
    suspend fun clearAllCache(ctx: Context) = withContext(Dispatchers.IO) {
        clearPictures(ctx)
        clearUpdates(ctx)
        clearSubtitles(ctx)
        guideFiles(ctx).forEach { GuideIndex.deleteFile(it) }
        tempFiles(ctx).forEach { it.deleteRecursively() }
        Xtream.clearCache()
    }

    suspend fun clearGuide(ctx: Context) = withContext(Dispatchers.IO) { guideFiles(ctx).forEach { GuideIndex.deleteFile(it) } }

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
