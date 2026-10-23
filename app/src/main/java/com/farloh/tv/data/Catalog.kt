package com.farloh.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.farloh.tv.net.Net
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class SectionData(val categories: List<Category>, val items: List<CatalogItem>, val updatedAt: Long) {
    val byCategory: Map<String, List<CatalogItem>> by lazy { items.groupBy { it.categoryId } }

    /** Lower-case names, built once (off the main thread) so searching big playlists stays instant. */
    val searchNames: Array<String> by lazy { Array(items.size) { items[it].name.lowercase() } }

    /** Items whose name contains every word of [query]. */
    fun search(query: String, hidden: Set<String>, limit: Int): List<CatalogItem> {
        val words = query.lowercase().split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val names = searchNames
        val out = ArrayList<CatalogItem>()
        for (i in names.indices) {
            val n = names[i]
            if (words.all { n.contains(it) } && items[i].categoryId !in hidden) {
                out.add(items[i])
                if (out.size >= limit) break
            }
        }
        return out
    }
}

/**
 * Playlists for Live TV, Movies and TV Shows. Each section is cached on disk and only re-downloaded
 * when the user taps Refresh for that section (or the first time it is opened).
 */
object Catalog {
    private val memory = ConcurrentHashMap<String, SectionData>()
    private val locks = Section.values().associateWith { Mutex() }

    /** [pid] = a specific playlist; null = the one in use. */
    private fun pid(ctx: Context, pid: String? = null) = pid ?: Store.get(ctx).activePlaylist?.id ?: "none"
    private fun memKey(ctx: Context, s: Section, pid: String?) = "${pid(ctx, pid)}/${s.key}"
    private fun dir(ctx: Context, pid: String? = null) = File(ctx.filesDir, "catalog/${pid(ctx, pid)}").apply { mkdirs() }
    private fun categoryFile(ctx: Context, s: Section, pid: String?) = File(dir(ctx, pid), "${s.key}_categories.json")
    private fun itemFile(ctx: Context, s: Section, pid: String?) = File(dir(ctx, pid), "${s.key}_items.json")

    fun lastUpdated(ctx: Context, s: Section, pid: String? = null): Long =
        itemFile(ctx, s, pid).let { if (it.exists()) it.lastModified() else 0L }

    /** Cached data if available, otherwise downloads it once. */
    suspend fun load(ctx: Context, s: Section, pid: String? = null): SectionData = locks.getValue(s).withLock {
        val key = memKey(ctx, s, pid)
        memory[key] ?: withContext(Dispatchers.IO) {
            if (!itemFile(ctx, s, pid).exists() || !categoryFile(ctx, s, pid).exists()) download(ctx, s, pid)
            parse(ctx, s, pid)
        }.also { remember(key, it) }
    }

    /** Manual refresh of one section. On failure the previous cache is kept. */
    suspend fun refresh(ctx: Context, s: Section, pid: String? = null): SectionData = locks.getValue(s).withLock {
        val key = memKey(ctx, s, pid)
        withContext(Dispatchers.IO) {
            download(ctx, s, pid)
            parse(ctx, s, pid)
        }.also { remember(key, it) }
    }

    /** Keep at most 6 lists in memory (big combined playlists on small devices). */
    private fun remember(key: String, data: SectionData) {
        memory[key] = data
        order.remove(key); order.add(key)
        while (order.size > 6) memory.remove(order.removeAt(0))
    }
    private val order = java.util.Collections.synchronizedList(ArrayList<String>())

    /** Forget in-memory lists (after switching playlist). Disk caches stay. */
    fun clearMemory() { memory.clear(); order.clear() }

    /** Deletes the cached lists of the active playlist. */
    fun clear(ctx: Context) {
        clearMemory()
        dir(ctx).deleteRecursively()
    }

    /** Deletes the cached lists of any playlist (used when a playlist is removed). */
    fun deleteFor(ctx: Context, playlistId: String) {
        clearMemory()
        File(ctx.filesDir, "catalog/$playlistId").deleteRecursively()
    }

    private fun download(ctx: Context, s: Section, pid: String?) {
        val account = Store.get(ctx).accountFor(pid) ?: throw IOException("Not signed in")
        val catNew = File(categoryFile(ctx, s, pid).path + ".new")
        val itemNew = File(itemFile(ctx, s, pid).path + ".new")
        Net.download(account.api(s.catAction), catNew)
        Net.download(account.api(s.listAction), itemNew)
        replace(catNew, categoryFile(ctx, s, pid))
        replace(itemNew, itemFile(ctx, s, pid))
    }

    private fun replace(from: File, to: File) {
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) throw IOException("Could not save playlist")
    }

    private fun parse(ctx: Context, s: Section, pidArg: String?): SectionData {
        val playlistId = pid(ctx, pidArg)
        val categories = ArrayList<Category>()
        readObjects(categoryFile(ctx, s, pidArg), setOf("category_id", "category_name")) { m ->
            val id = m["category_id"] ?: return@readObjects
            categories.add(Category(id, m["category_name"]?.trim().orEmpty().ifEmpty { "Other" }))
        }
        val items = ArrayList<CatalogItem>()
        when (s) {
            Section.LIVE -> readObjects(itemFile(ctx, s, pidArg), LIVE_KEYS) { m ->
                val id = m["stream_id"] ?: return@readObjects
                items.add(
                    LiveChannel(
                        id = id,
                        num = m["num"].orEmpty(),
                        name = m["name"]?.trim().orEmpty(),
                        image = m["stream_icon"]?.takeIf { it.isNotBlank() },
                        epgId = m["epg_channel_id"],
                        categoryId = m["category_id"].orEmpty(),
                        playlistId = playlistId,
                    )
                )
            }
            Section.MOVIES -> readObjects(itemFile(ctx, s, pidArg), MOVIE_KEYS) { m ->
                val id = m["stream_id"] ?: return@readObjects
                items.add(
                    Movie(
                        id = id,
                        name = m["name"]?.trim().orEmpty(),
                        image = m["stream_icon"]?.takeIf { it.isNotBlank() },
                        categoryId = m["category_id"].orEmpty(),
                        rating = m["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                        ext = m["container_extension"].orEmpty(),
                        added = m["added"]?.toLongOrNull() ?: 0L,
                        playlistId = playlistId,
                    )
                )
            }
            Section.SERIES -> readObjects(itemFile(ctx, s, pidArg), SERIES_KEYS) { m ->
                val id = m["series_id"] ?: return@readObjects
                items.add(
                    Show(
                        id = id,
                        name = m["name"]?.trim().orEmpty(),
                        image = m["cover"]?.takeIf { it.isNotBlank() },
                        categoryId = m["category_id"].orEmpty(),
                        rating = m["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                        year = (m["releaseDate"] ?: m["release_date"])?.take(4),
                        added = m["last_modified"]?.toLongOrNull() ?: 0L,
                        playlistId = playlistId,
                    )
                )
            }
        }
        Store.get(ctx).setItemCount(s, items.size, playlistId)
        return SectionData(categories, items, lastUpdated(ctx, s, pidArg))
    }

    private val LIVE_KEYS = setOf("num", "name", "stream_id", "stream_icon", "epg_channel_id", "category_id")
    private val MOVIE_KEYS = setOf("name", "stream_id", "stream_icon", "rating", "category_id", "container_extension", "added")
    private val SERIES_KEYS = setOf("series_id", "name", "cover", "rating", "category_id", "releaseDate", "release_date", "last_modified")

    /**
     * Streams a (possibly huge) JSON array of objects without loading it all into memory.
     * Only [keys] are kept; nested values are skipped.
     */
    private fun readObjects(file: File, keys: Set<String>, onObject: (Map<String, String?>) -> Unit) {
        if (!file.exists() || file.length() == 0L) return
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file), 64 * 1024), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(readObject(r, keys)) else r.skipValue()
                    }
                    r.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    // Some panels return {"0": {...}, "1": {...}} instead of an array.
                    r.beginObject()
                    while (r.hasNext()) {
                        r.nextName()
                        if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(readObject(r, keys)) else r.skipValue()
                    }
                    r.endObject()
                }
                else -> Unit
            }
        }
    }

    private fun readObject(r: JsonReader, keys: Set<String>): Map<String, String?> {
        val map = HashMap<String, String?>(keys.size * 2)
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (name in keys) map[name] = readScalar(r) else r.skipValue()
        }
        r.endObject()
        return map
    }

    private fun readScalar(r: JsonReader): String? = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); null }
    }
}
