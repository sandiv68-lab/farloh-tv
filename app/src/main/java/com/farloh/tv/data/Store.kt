package com.farloh.tv.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One saved server login. [sid] = its sync id: the same on every device and kept when the login is edited
 * (renewals), so its favourites, history and progress follow it. Restored from backups as it was.
 */
data class Playlist(val id: String, val name: String, val account: Account, val sid: String = "")

/** A movie, or a TV show (with [episode] = the episode watched last), in Recently watched. */
data class WatchEntry(val id: String, val name: String, val image: String?, val time: Long, val episode: String? = null)

/**
 * App settings and saved playlists. Logins are encrypted with a key held in the Android Keystore (Android 6+).
 * Favourites, history, hidden groups, resume positions and cached lists are kept separately per playlist.
 */
class Store private constructor(context: Context) {

    private val app: Context = context.applicationContext
    private val prefs = context.getSharedPreferences("farloh_prefs", Context.MODE_PRIVATE)
    @Volatile private var cachedPlaylists: List<Playlist>? = null

    init {
        migrateSingleAccount()
        removeNetworkSettings()
    }

    /** After construction (the settings below the init block exist by then). */
    private fun startup() {
        ensureSyncIds()
        if (!Records.flag(app, "filled_v1")) { fillRecords(); Records.setFlag(app, "filled_v1") }
    }

    // =================== Playlists ===================

    fun playlists(): List<Playlist> {
        cachedPlaylists?.let { return it }
        val raw = prefs.getString(KEY_PLAYLISTS, null)
        val list = ArrayList<Playlist>()
        val json = raw?.let { Crypto.decrypt(it) }
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Playlist(o.getString("id"), o.getString("n"), Account(o.getString("s"), o.getString("u"), o.getString("p")),
                        o.optString("sid")))
                }
            } catch (e: Exception) {
                Log.w("FarlohStore", "Could not read saved playlists", e)
            }
        }
        cachedPlaylists = list
        return list
    }

    private fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().put("id", p.id).put("n", p.name)
                .put("s", p.account.server).put("u", p.account.username).put("p", p.account.password).put("sid", p.sid))
        }
        prefs.edit().putString(KEY_PLAYLISTS, Crypto.encrypt(arr.toString())).apply()
        cachedPlaylists = list.toList()
    }

    val activePlaylist: Playlist?
        get() {
            val all = playlists()
            val id = prefs.getString(KEY_ACTIVE, null)
            return all.firstOrNull { it.id == id } ?: all.firstOrNull()
        }

    /** Login of the playlist currently in use. */
    val account: Account? get() = activePlaylist?.account

    fun setActive(id: String) = prefs.edit().putString(KEY_ACTIVE, id).apply()

    fun addPlaylist(name: String, account: Account): Playlist {
        val p = Playlist("p" + System.currentTimeMillis().toString(36), name, account, newSyncId())
        savePlaylists(playlists() + p)
        setActive(p.id)
        recordPlaylist(p, null)
        return p
    }

    fun updatePlaylist(id: String, name: String? = null, account: Account? = null) {
        val old = playlists().firstOrNull { it.id == id }
        savePlaylists(playlists().map {
            if (it.id == id) it.copy(name = name ?: it.name, account = account ?: it.account) else it
        })
        playlists().firstOrNull { it.id == id }?.let { recordPlaylist(it, old) }
    }

    // ---- Sync ids ----

    private fun newSyncId(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** Playlists saved before sync ids existed get one. */
    private fun ensureSyncIds() {
        val all = playlists()
        if (all.none { it.sid.isEmpty() }) return
        savePlaylists(all.map { if (it.sid.isEmpty()) it.copy(sid = newSyncId()) else it })
    }

    /** Sync id of a playlist (the one in use when [pid] is null). */
    fun syncId(pid: String? = null): String? = playlist(pid)?.sid?.takeIf { it.isNotEmpty() }

    /**
     * Recognises a playlist on other devices without sending the login: a fingerprint of the server's
     * address and the username (not the password, and not reversible).
     */
    fun fingerprint(a: Account): String {
        val host = runCatching { android.net.Uri.parse(a.server).let { (it.host ?: "") + (if (it.port > 0) ":${it.port}" else "") } }
            .getOrNull().orEmpty().lowercase().ifEmpty { a.server.lowercase() }
        val d = MessageDigest.getInstance("SHA-256").digest("farloh-pl:$host|${a.username}".toByteArray())
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    /** The playlist's name and every login it has had (renewals), as fingerprints. */
    private fun recordPlaylist(p: Playlist, old: Playlist?) {
        if (p.sid.isEmpty()) return
        val prev = (Records.get(app, p.sid, "pl", p.sid)?.value as? JSONObject)?.optJSONArray("fp")
        val fps = LinkedHashSet<String>()
        prev?.let { a -> for (i in 0 until a.length()) fps += a.optString(i) }
        old?.let { fps += fingerprint(it.account) }
        fps += fingerprint(p.account)
        if (old != null && old.name == p.name && fingerprint(old.account) == fingerprint(p.account) && prev != null) return
        Records.put(app, p.sid, "pl", p.sid, JSONObject().put("n", p.name).put("fp", JSONArray(fps.toList())))
    }

    /** Removes a playlist and everything saved for it. Returns the playlist now active (or null if none left). */
    fun removePlaylist(id: String): Playlist? {
        val gone = playlists().firstOrNull { it.id == id }
        val rest = playlists().filter { it.id != id }
        savePlaylists(rest)
        // Only from this device: its records elsewhere (other devices, the cloud later) are left alone.
        gone?.sid?.takeIf { it.isNotEmpty() }?.let { Records.dropScope(app, it) }
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$id:") }.forEach { e.remove(it) }
        e.apply()
        if (prefs.getString(KEY_ACTIVE, null) == id) rest.firstOrNull()?.let { setActive(it.id) }
        combinedIds = combinedIds - id
        return activePlaylist
    }

    /** Forget that a playlist's lists were loaded (after its server or login changed). */
    fun resetLoaded(playlistId: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it == "$playlistId:initial_load_done" || it.startsWith("$playlistId:count_") }.forEach { e.remove(it) }
        e.apply()
    }

    private fun scopeId(): String = activePlaylist?.id ?: "none"
    /** Per-playlist key; [pid] = that playlist, or the one in use when null. */
    private fun k(name: String, pid: String? = null) = "${pid?.takeIf { it.isNotEmpty() } ?: scopeId()}:$name"

    fun playlist(pid: String?): Playlist? = if (pid.isNullOrEmpty()) activePlaylist else playlists().firstOrNull { it.id == pid }

    /** Login for a playlist (the one in use when [pid] is null/unknown). */
    fun accountFor(pid: String?): Account? = playlist(pid)?.account ?: account

    // ---- Combined view: several playlists shown together ----
    var combinedIds: Set<String>
        get() = HashSet(prefs.getStringSet("combined_ids", emptySet()) ?: emptySet())
        set(value) { prefs.edit().putStringSet("combined_ids", HashSet(value)).apply(); recordCombined() }
    var combinedOn: Boolean
        get() = prefs.getBoolean("combined_on", false)
        set(value) { prefs.edit().putBoolean("combined_on", value).apply(); recordCombined() }

    /** Combined lists, saved by sync id (playlist ids differ on each device). */
    private fun combinedValue(): JSONObject = JSONObject().put("on", combinedOn)
        .put("ids", JSONArray(combinedIds.mapNotNull { syncId(it) }))
    private fun recordCombined() = Records.put(app, Records.SHARED, "set", "combined", combinedValue())

    /** Playlists shown together, in saved order (empty when the combined view is off or has fewer than 2). */
    fun combinedPlaylists(): List<Playlist> {
        if (!combinedOn) return emptyList()
        val ids = combinedIds
        val list = playlists().filter { it.id in ids }
        return if (list.size >= 2) list else emptyList()
    }

    val isCombined: Boolean get() = combinedPlaylists().isNotEmpty()

    /** Playlist headers left open in the combined view, per section. */
    fun expandedPlaylists(section: Section): Set<String> =
        HashSet(prefs.getStringSet("combined_open_${section.name}", emptySet()) ?: emptySet())
    fun setExpandedPlaylists(section: Section, ids: Set<String>) =
        prefs.edit().putStringSet("combined_open_${section.name}", HashSet(ids)).apply()

    /** The playlists the screens should show: the combined set, or just the one in use. */
    fun viewPlaylists(): List<Playlist> = combinedPlaylists().ifEmpty { listOfNotNull(activePlaylist) }

    /** Version 1.0 stored a single login; turn it into the first playlist. */
    /** Versions 1.5.0–1.7.1 had Settings → Network (DNS, proxy, VPN); it was removed. Clear what it saved. */
    private fun removeNetworkSettings() {
        val old = prefs.all.keys.filter {
            it.startsWith("dns_") || it.startsWith("proxy_") || it.startsWith("vpn_")
        }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }

    private fun migrateSingleAccount() {
        val old = prefs.getString("account", null) ?: return
        val json = Crypto.decrypt(old)
        val e = prefs.edit().remove("account")
        if (json != null && prefs.getString(KEY_PLAYLISTS, null) == null) {
            try {
                val o = JSONObject(json)
                val acc = Account(o.getString("s"), o.getString("u"), o.getString("p"))
                val id = "p1"
                val arr = JSONArray().put(JSONObject().put("id", id).put("n", defaultName(acc))
                    .put("s", acc.server).put("u", acc.username).put("p", acc.password))
                e.putString(KEY_PLAYLISTS, Crypto.encrypt(arr.toString())).putString(KEY_ACTIVE, id)
                prefs.getString("user_info", null)?.let { e.putString("$id:user_info", it) }
                prefs.getStringSet("favorites", null)?.let { e.putStringSet("$id:favorites", HashSet(it)) }
                if (prefs.getBoolean("initial_load_done", false)) e.putBoolean("$id:initial_load_done", true)
            } catch (ex: Exception) {
                Log.w("FarlohStore", "Migration failed", ex)
            }
        }
        e.remove("user_info").remove("favorites").remove("initial_load_done").apply()
    }

    // =================== Per-playlist data ===================

    /** Subscription details from the server (status, expiry, connections). No credentials. */
    var userInfo: JSONObject?
        get() = prefs.getString(k("user_info"), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(value) = prefs.edit().putString(k("user_info"), value?.toString()).apply()

    var initialLoadDone: Boolean
        get() = prefs.getBoolean(k("initial_load_done"), false)
        set(value) = prefs.edit().putBoolean(k("initial_load_done"), value).apply()

    fun isLoaded(pid: String?) = prefs.getBoolean(k("initial_load_done", pid), false)
    fun setLoaded(pid: String?) = prefs.edit().putBoolean(k("initial_load_done", pid), true).apply()

    fun itemCount(section: Section, pid: String? = null): Int = prefs.getInt(k("count_${section.key}", pid), -1)
    fun setItemCount(section: Section, count: Int, pid: String? = null) =
        prefs.edit().putInt(k("count_${section.key}", pid), count).apply()

    /** Favourites are kept separately for Live TV, Movies and TV Shows (their ids can overlap). */
    private fun favKey(section: Section, pid: String?) = when (section) {
        Section.LIVE -> k("favorites", pid)
        Section.MOVIES -> k("favorites_vod", pid)
        Section.SERIES -> k("favorites_series", pid)
    }

    fun favorites(section: Section = Section.LIVE, pid: String? = null): MutableSet<String> =
        HashSet(prefs.getStringSet(favKey(section, pid), emptySet()) ?: emptySet())

    fun isFavorite(section: Section, id: String, pid: String? = null) = favorites(section, pid).contains(id)

    /** Returns true if the item is now a favourite. */
    fun toggleFavorite(id: String, section: Section = Section.LIVE, pid: String? = null): Boolean {
        val set = favorites(section, pid)
        val added = if (set.contains(id)) { set.remove(id); false } else { set.add(id); true }
        prefs.edit().putStringSet(favKey(section, pid), set).apply()
        syncId(pid)?.let { sid -> if (added) Records.put(app, sid, "fav.${section.key}", id, 1) else Records.delete(app, sid, "fav.${section.key}", id) }
        return added
    }

    /** Live TV channels watched, most recent first. */
    fun history(pid: String? = null): List<String> {
        val raw = prefs.getString(k("history", pid), null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun addToHistory(id: String, pid: String? = null) {
        if (historyLimit <= 0) return
        val list = (listOf(id) + history(pid).filter { it != id }).take(MAX_HISTORY)
        val now = System.currentTimeMillis()
        prefs.edit().putString(k("history", pid), JSONArray(list).toString()).putLong(k("history_time_$id", pid), now).apply()
        syncId(pid)?.let { Records.put(app, it, "hist", id, JSONObject().put("t", now)) }
    }

    /** When a channel was last watched (for ordering combined history). */
    fun historyTime(id: String, pid: String? = null): Long = prefs.getLong(k("history_time_$id", pid), 0L)

    /** Clears Recently watched in Live TV, Movies and TV Shows. Resume points and watched bars are kept. */
    fun clearHistory() {
        val e = prefs.edit()
        for (p in viewPlaylists()) {
            if (p.sid.isNotEmpty()) {
                Records.putAll(app, p.sid, history(p.id).map { Triple("hist", it, null) } +
                    watchHistory(Section.MOVIES, p.id).map { Triple("watch.vod", it.id, null) } +
                    watchHistory(Section.SERIES, p.id).map { Triple("watch.series", it.id, null) }, delete = true)
            }
            e.remove(k("history", p.id)).remove(k("watched_vod", p.id)).remove(k("watched_series", p.id))
            prefs.all.keys.filter { it.startsWith("${p.id}:history_time_") }.forEach { e.remove(it) }
        }
        e.apply()
    }

    // ---- Recently watched: Movies & TV Shows ----

    private fun watchKey(section: Section, pid: String?) = k(if (section == Section.SERIES) "watched_series" else "watched_vod", pid)

    /** Movies or TV shows watched, most recent first. */
    fun watchHistory(section: Section, pid: String? = null): List<WatchEntry> = runCatching {
        val a = JSONArray(prefs.getString(watchKey(section, pid), "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            WatchEntry(o.getString("id"), o.optString("n"), o.optString("i").ifEmpty { null }, o.optLong("t"),
                o.optString("e").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private fun saveWatchHistory(section: Section, pid: String?, list: List<WatchEntry>) {
        val a = JSONArray()
        for (w in list.take(MAX_HISTORY)) a.put(JSONObject().put("id", w.id).put("n", w.name).put("t", w.time).apply {
            w.image?.let { put("i", it) }
            w.episode?.let { put("e", it) }
        })
        prefs.edit().putString(watchKey(section, pid), a.toString()).apply()
    }

    /**
     * Puts a movie or show at the top of its Recently watched. A missing poster keeps the one saved before,
     * else uses [fallbackImage] (e.g. an episode's picture when the show's poster isn't known).
     */
    fun noteWatched(section: Section, entry: WatchEntry, pid: String? = null, fallbackImage: String? = null) {
        if (historyLimit <= 0) return
        val old = watchHistory(section, pid)
        val prev = old.firstOrNull { it.id == entry.id }
        val e = entry.copy(name = entry.name.ifBlank { prev?.name.orEmpty() }, image = entry.image ?: prev?.image ?: fallbackImage)
        saveWatchHistory(section, pid, listOf(e) + old.filter { it.id != entry.id })
        syncId(pid)?.let { Records.put(app, it, "watch.${section.key}", e.id, watchValue(e)) }
    }

    private fun watchValue(w: WatchEntry) = JSONObject().put("n", w.name).put("t", w.time).apply {
        w.image?.let { put("i", it) }
        w.episode?.let { put("e", it) }
    }

    fun removeWatched(section: Section, id: String, pid: String? = null) {
        val sid = syncId(pid)
        if (section == Section.LIVE) {
            prefs.edit().putString(k("history", pid), JSONArray(history(pid).filter { it != id }).toString())
                .remove(k("history_time_$id", pid)).apply()
            sid?.let { Records.delete(app, it, "hist", id) }
            return
        }
        saveWatchHistory(section, pid, watchHistory(section, pid).filter { it.id != id })
        sid?.let { Records.delete(app, it, "watch.${section.key}", id) }
    }

    fun inHistory(section: Section, id: String, pid: String? = null): Boolean =
        if (section == Section.LIVE) id in history(pid) else watchHistory(section, pid).any { it.id == id }

    // ---- For renewals and Carry over (moving a playlist's data to new numbers) ----

    fun setFavorites(section: Section, ids: Set<String>, pid: String?) {
        val old = favorites(section, pid)
        prefs.edit().putStringSet(favKey(section, pid), HashSet(ids)).apply()
        syncId(pid)?.let { sid ->
            Records.putAll(app, sid, (ids - old).map { Triple("fav.${section.key}", it, 1) })
            Records.putAll(app, sid, (old - ids).map { Triple("fav.${section.key}", it, null) }, delete = true)
        }
    }

    /** Live TV history as (channel, time), newest first. */
    fun historyWithTimes(pid: String?): List<Pair<String, Long>> = history(pid).map { it to historyTime(it, pid) }

    fun setHistory(list: List<Pair<String, Long>>, pid: String?) {
        val old = history(pid)
        val sorted = list.sortedByDescending { it.second }.distinctBy { it.first }.take(MAX_HISTORY)
        val e = prefs.edit().putString(k("history", pid), JSONArray(sorted.map { it.first }).toString())
        old.forEach { e.remove(k("history_time_$it", pid)) }
        sorted.forEach { (id, t) -> e.putLong(k("history_time_$id", pid), t) }
        e.apply()
        syncId(pid)?.let { sid ->
            Records.putAll(app, sid, sorted.map { (id, t) -> Triple("hist", id, JSONObject().put("t", t)) })
            Records.putAll(app, sid, (old - sorted.map { it.first }.toSet()).map { Triple("hist", it, null) }, delete = true)
        }
    }

    fun setWatchHistory(section: Section, list: List<WatchEntry>, pid: String?) {
        val old = watchHistory(section, pid).map { it.id }.toSet()
        val sorted = list.sortedByDescending { it.time }.distinctBy { it.id }
        saveWatchHistory(section, pid, sorted)
        syncId(pid)?.let { sid ->
            Records.putAll(app, sid, sorted.take(MAX_HISTORY).map { Triple("watch.${section.key}", it.id, watchValue(it)) })
            Records.putAll(app, sid, (old - sorted.take(MAX_HISTORY).map { it.id }.toSet()).map { Triple("watch.${section.key}", it, null) }, delete = true)
        }
    }

    /** Resume points of a playlist: key (e.g. MOVIE_123) → (position, duration). */
    fun positions(pid: String?): Map<String, Pair<Long, Long>> {
        val prefix = k("pos_", pid)
        val out = HashMap<String, Pair<Long, Long>>()
        for ((key, v) in prefs.all) {
            if (!key.startsWith(prefix) || v !is Long) continue
            val item = key.removePrefix(prefix)
            out[item] = v to prefs.getLong(k("dur_$item", pid), 0)
        }
        return out
    }

    /** Show / season / episode number saved with an episode's resume point, if known. */
    fun episodeTag(key: String, pid: String?): Triple<String, Int, Int>? {
        val v = syncId(pid)?.let { Records.get(app, it, "pos", key)?.value as? JSONObject } ?: return null
        val show = v.optString("sh").ifEmpty { return null }
        if (!v.has("s") || !v.has("e")) return null
        return Triple(show, v.optInt("s"), v.optInt("e"))
    }

    fun removePositions(keys: Collection<String>, pid: String?) {
        if (keys.isEmpty()) return
        val e = prefs.edit()
        keys.forEach { e.remove(k("pos_$it", pid)).remove(k("dur_$it", pid)) }
        e.apply()
        syncId(pid)?.let { sid -> Records.putAll(app, sid, keys.map { Triple("pos", it, null) }, delete = true) }
    }

    /** Sort choices for a playlist's own categories: category → mode. */
    fun sortsFor(section: Section, pid: String): Map<String, String> {
        val prefix = "sort_${section.key}_$pid|"
        return prefs.all.filter { it.key.startsWith(prefix) && it.value is String }
            .map { it.key.removePrefix(prefix) to (it.value as String) }.toMap()
    }

    fun removeSort(section: Section, category: String) {
        prefs.edit().remove("sort_${section.key}_$category").apply()
        sortRecord(section, category)?.let { (scope, id) -> Records.delete(app, scope, "sort", id) }
    }

    /** Category ids hidden by parental control for a section. */
    fun hiddenCategories(section: Section, pid: String? = null): Set<String> =
        HashSet(prefs.getStringSet(k("hidden_${section.key}", pid), emptySet()) ?: emptySet())

    fun setHiddenCategories(section: Section, ids: Set<String>, pid: String? = null) {
        prefs.edit().putStringSet(k("hidden_${section.key}", pid), HashSet(ids)).apply()
        syncId(pid)?.let { Records.put(app, it, "hidden.${section.key}", "all", JSONArray(ids.sorted())) }
    }

    /** Your own order of the categories (Settings → Parental control → Hide & arrange groups); empty = server order. */
    fun categoryOrder(section: Section, pid: String? = null): List<String> = runCatching {
        val a = JSONArray(prefs.getString(k("order_${section.key}", pid), "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun setCategoryOrder(section: Section, ids: List<String>, pid: String? = null) {
        prefs.edit().putString(k("order_${section.key}", pid), JSONArray(ids).toString()).apply()
        syncId(pid)?.let { Records.put(app, it, "order.${section.key}", "all", JSONArray(ids)) }
    }

    /** [cats] in your order; categories you haven't placed (e.g. new ones) follow in the server's order. */
    fun ordered(section: Section, pid: String?, cats: List<Category>): List<Category> {
        val order = categoryOrder(section, pid)
        if (order.isEmpty()) return cats
        val rank = order.withIndex().associate { it.value to it.index }
        return cats.withIndex().sortedBy { (i, c) -> rank[c.id]?.toLong() ?: (order.size.toLong() + i) }.map { it.value }
    }

    /** [show], [season], [episode]: for episodes, so progress can follow a show to a renewed server. */
    fun savePosition(key: String, positionMs: Long, durationMs: Long, pid: String? = null,
                     show: String? = null, season: Int? = null, episode: Int? = null) {
        prefs.edit().putLong(k("pos_$key", pid), positionMs).putLong(k("dur_$key", pid), durationMs).apply()
        syncId(pid)?.let { sid ->
            val old = Records.get(app, sid, "pos", key)?.value as? JSONObject
            val v = JSONObject().put("p", positionMs).put("d", durationMs)
            (show ?: old?.optString("sh")?.ifEmpty { null })?.let { v.put("sh", it) }
            (season ?: old?.optInt("s", -1)?.takeIf { it >= 0 })?.let { v.put("s", it) }
            (episode ?: old?.optInt("e", -1)?.takeIf { it >= 0 })?.let { v.put("e", it) }
            Records.put(app, sid, "pos", key, v)
        }
    }

    /** The show screen tells which show / season / number its episodes are (for renewals). Not a change. */
    fun tagEpisodes(pid: String?, show: String, episodes: List<Episode>) {
        val sid = syncId(pid) ?: return
        for (ep in episodes) {
            val key = "EPISODE_${ep.id}"
            if (!prefs.contains(k("pos_$key", pid))) continue
            Records.amend(app, sid, "pos", key) { v ->
                if (v.has("sh")) false else { v.put("sh", show).put("s", ep.season).put("e", ep.num); true }
            }
        }
    }

    /** Saved position, or 0 if never started / watched. */
    fun resumePosition(key: String, pid: String? = null): Long {
        val pos = prefs.getLong(k("pos_$key", pid), 0)
        val dur = prefs.getLong(k("dur_$key", pid), 0)
        if (pos < 30_000 || (dur > 0 && pos >= dur * WATCHED_AT)) return 0
        return pos
    }

    /** 0..1000 watched progress (1000 = watched, i.e. 90% or more), -1 if never played. */
    fun progress(key: String, pid: String? = null): Int {
        val dur = prefs.getLong(k("dur_$key", pid), 0)
        if (dur <= 0) return -1
        val pos = prefs.getLong(k("pos_$key", pid), 0)
        if (pos >= dur * WATCHED_AT) return 1000
        return ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
    }

    fun isWatched(key: String, pid: String? = null) = progress(key, pid) >= 1000

    /** Mark as watched (full bar) or unwatched (no bar, starts from the beginning). */
    fun setWatched(keys: List<String>, watched: Boolean, pid: String? = null) {
        val e = prefs.edit()
        val changes = ArrayList<Triple<String, String, Any?>>()
        val sid = syncId(pid)
        for (key in keys) {
            if (watched) {
                val dur = prefs.getLong(k("dur_$key", pid), 0).takeIf { it > 0 } ?: 1L
                e.putLong(k("pos_$key", pid), dur).putLong(k("dur_$key", pid), dur)
                val old = sid?.let { Records.get(app, it, "pos", key)?.value as? JSONObject }
                changes += Triple("pos", key, JSONObject(old?.toString() ?: "{}").put("p", dur).put("d", dur))
            } else {
                e.remove(k("pos_$key", pid)).remove(k("dur_$key", pid))
            }
        }
        e.apply()
        if (sid != null) {
            if (watched) Records.putAll(app, sid, changes)
            else Records.putAll(app, sid, keys.filter { Records.get(app, sid, "pos", it) != null }.map { Triple("pos", it, null) }, delete = true)
        }
    }

    // =================== App-wide settings ===================

    /** "ts" (MPEG-TS) or "m3u8" (HLS) for live channels. */
    var liveFormat: String by StringPref("live_format", "ts")
    var externalByDefault: Boolean by BoolPref("external_default", false)

    /** "auto" (only tracks the stream marks as default), "on" (preferred language) or "off". */
    var subtitleMode: String by StringPref("subtitle_mode", "auto")
    /** ISO-639 language code, or "" for the device language. */
    var subtitleLanguage: String by StringPref("subtitle_language", "")
    var pipEnabled: Boolean by BoolPref("pip_enabled", true)
    /** 0 hides the "Recently added" group. */
    var recentLimit: Int by IntPref("recent_limit", 50)
    /** 0 turns channel history off. */
    var historyLimit: Int by IntPref("history_limit", 20)

    /** "hw" hardware only, "hwplus" hardware + software fallback (default), "sw" software first. */
    var decoderMode: String by StringPref("decoder_mode", "hwplus")
    /** "small", "normal", "large", "xl". */
    var bufferSize: String by StringPref("buffer_size", "normal")

    /** "auto", "mobile" or "tv". */
    var deviceMode: String by StringPref("device_mode", "auto")

    /** Minutes a channel's TV guide is remembered before asking the server again. */
    var epgMinutes: Int by IntPref("epg_minutes", 10)

    /** Maximum space for cached pictures (channel logos, posters), in MB. */
    var imageCacheMb: Int by IntPref("image_cache_mb", 150)

    // ---- Player ----
    /** Audio sync: + = sound later, − = sound earlier, in ms. Kept for all videos (e.g. a soundbar's delay). */
    var audioDelayMs: Int by IntPref("audio_delay_ms", 0)
    // ---- Recordings ----
    /** Minutes recorded before the start / after the end of the programme (in case it runs off schedule). */
    var recPadBeforeMin: Int by IntPref("rec_pad_before", 2)
    var recPadAfterMin: Int by IntPref("rec_pad_after", 5)
    /** Folder recordings are saved to; "" = device storage. */
    var recFolder: String by StringPref("rec_folder", "")

    /** Sound output chosen in the player (TV boxes): "type|name" of the device, "" = automatic. */
    var audioOutput: String by StringPref("audio_output", "")
    /** Multiview: the connection-limit warning was turned off on this device. */
    var multiviewWarningOff: Boolean by BoolPref("multiview_warning_off", false)
    /** Last Multiview layout used (0–3; default 2 × 2 grid). */
    var multiviewLayout: Int
        get() = prefs.getInt("multiview_layout", 3).coerceIn(0, 3)
        set(value) = prefs.edit().putInt("multiview_layout", value).apply()

    // ---- Sorting (Movies / TV Shows) ----
    /** "server", "added", "rating", "release", "az" or "za" – for one category, else the section's default. */
    fun sortFor(section: Section, category: String): String {
        val m = prefs.getString("sort_${section.key}_$category", null) ?: prefs.getString("sort_${section.key}", "server") ?: "server"
        return if (m == "year") "release" else m // test build 54 had "Year"
    }

    fun setSort(section: Section, category: String?, mode: String) {
        val e = prefs.edit()
        if (category == null) {
            // All categories: the new default, and per-category choices are cleared.
            e.putString("sort_${section.key}", mode)
            val cleared = prefs.all.keys.filter { it.startsWith("sort_${section.key}_") }
            cleared.forEach { e.remove(it) }
            e.apply()
            Records.put(app, Records.SHARED, "sort", "${section.key}|*", mode)
            for (key in cleared) sortRecord(section, key.removePrefix("sort_${section.key}_"))?.let { (scope, id) -> Records.delete(app, scope, "sort", id) }
        } else {
            e.putString("sort_${section.key}_$category", mode)
            e.apply()
            sortRecord(section, category)?.let { (scope, id) -> Records.put(app, scope, "sort", id, mode) }
        }
    }

    // ---- Search history (all playlists) ----
    /** How many recent searches are kept: 5 (default), 10 or 20. */
    var searchHistoryLimit: Int by IntPref("search_history_limit", 5)

    fun searchHistory(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("search_history", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList()).take(searchHistoryLimit)

    private fun saveSearchHistory(list: List<String>) {
        prefs.edit().putString("search_history", JSONArray(list.take(searchHistoryLimit)).toString()).apply()
        Records.put(app, Records.SHARED, "search", "all", JSONArray(list.take(searchHistoryLimit)))
    }

    /** Newest first; the same search isn't kept twice. */
    fun addSearch(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        saveSearchHistory(listOf(t) + searchHistory().filter { !it.equals(t, ignoreCase = true) })
    }

    fun removeSearch(text: String) = saveSearchHistory(searchHistory().filter { it != text })
    fun clearSearchHistory() {
        prefs.edit().remove("search_history").apply()
        Records.put(app, Records.SHARED, "search", "all", JSONArray())
    }

    /** The first-start "Allow notifications / files" question has been shown on this device. */
    var permissionsAsked: Boolean by BoolPref("permissions_asked", false)


    /** "landscape" (default) or "auto" (follow the phone's rotation). */
    var orientation: String by StringPref("orientation", "landscape")
    /** App size in percent of the device's standard size, ignoring the phone's own display-size setting. */
    var uiZoom: Int by IntPref("ui_zoom", 100)
    /** Text size in percent; 0 = follow the phone's font-size setting. */
    var textZoom: Int by IntPref("text_zoom", 100)

    /** Player: Back button shrinks the video into picture-in-picture instead of closing it. */
    var pipOnBack: Boolean by BoolPref("pip_on_back", true)
    /** Player screen size: "fit", "stretch", "crop", "16:9" or "4:3". Until chosen: 16:9 on TV, Fit on phones. */
    var videoResize: String
        get() = prefs.getString("video_resize", null) ?: if (tvDefaults) "16:9" else "fit"
        set(value) = prefs.edit().putString("video_resize", value).apply()
    /** Set by the screens: the app is in TV mode (for TV-only defaults). Not saved. */
    @Volatile var tvDefaults = false
    /** Player: swipe for brightness / volume / seek, pinch to zoom, double-tap to skip. */
    var playerGestures: Boolean by BoolPref("player_gestures", true)

    // ---- Online subtitles (OpenSubtitles.com) ----
    /** "default" (api.opensubtitles.com), "vip" (vip-api.opensubtitles.com) or "custom". */
    var subsServer: String by StringPref("subs_server", "default")
    var subsCustomUrl: String by StringPref("subs_custom_url", "")
    /** Stored encrypted (older versions saved it as plain text; that still reads fine). */
    var subsApiKey: String
        get() {
            val raw = prefs.getString("subs_api_key", null) ?: return ""
            return if (raw.startsWith("g1:") || raw.startsWith("p1:")) Crypto.decrypt(raw) ?: "" else raw
        }
        set(value) = prefs.edit().apply {
            if (value.isEmpty()) remove("subs_api_key") else putString("subs_api_key", Crypto.encrypt(value))
        }.apply()
    var subsUsername: String by StringPref("subs_username", "")
    var subsPassword: String
        get() = prefs.getString("subs_password", null)?.let { Crypto.decrypt(it) } ?: ""
        set(value) = prefs.edit().apply {
            if (value.isEmpty()) remove("subs_password") else putString("subs_password", Crypto.encrypt(value))
        }.apply()
    /** Comma-separated language codes for online search, e.g. "en,ms". Empty = subtitle language / device. */
    var subsLanguages: String by StringPref("subs_languages", "")

    // ---- Parental PIN (optional; stored as a salted hash) ----
    val hasPin: Boolean get() = prefs.getString("parental_pin", null) != null
    fun setPin(pin: String?) {
        prefs.edit().apply { if (pin.isNullOrEmpty()) remove("parental_pin") else putString("parental_pin", hash(pin)) }.apply()
        Records.put(app, Records.SHARED, "set", "parental_pin", prefs.getString("parental_pin", null) ?: "")
    }
    fun checkPin(pin: String): Boolean = prefs.getString("parental_pin", null) == hash(pin)
    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("farloh-parental:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Wipes everything (all playlists and settings). */
    // =================== Backup & restore ===================

    /**
     * Everything needed to rebuild this app on another device: all settings, playlists (logins in plain
     * text, because the device's own encryption key can't move to another device), favourites, history,
     * hidden groups and resume positions. Saved lists are left out; they download again.
     */
    fun exportForBackup(): JSONObject {
        val values = JSONObject()
        for ((k, v) in prefs.all) {
            if (k in SECRET_KEYS || k == "account") continue
            if (k.endsWith(":initial_load_done") || k.contains(":count_")) continue
            val o = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            values.put(k, o)
        }
        val secrets = JSONObject()
        for (k in SECRET_KEYS) {
            val raw = prefs.getString(k, null) ?: continue
            val plain = if (raw.startsWith("g1:") || raw.startsWith("p1:")) Crypto.decrypt(raw) else raw
            if (plain != null) secrets.put(k, plain)
        }
        return JSONObject().put("prefs", values).put("secrets", secrets)
    }

    /** Replaces all settings with a backup. Logins are re-encrypted with this device's key. */
    fun importFromBackup(data: JSONObject) {
        val values = data.optJSONObject("prefs") ?: throw IllegalArgumentException("Backup has no settings")
        val secrets = data.optJSONObject("secrets") ?: JSONObject()
        // Settings that belong to this device stay as they are (a phone backup shouldn't resize a TV). Screen
        // settings are never taken from the backup; player settings only when this device hasn't set them.
        val keep = (DEVICE_KEYS + DEVICE_FILL_KEYS).mapNotNull { k -> prefs.all[k]?.let { k to it } }
        val kept = keep.map { it.first }.toSet()
        val e = prefs.edit().clear()
        for ((k, v) in keep) when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        for (k in values.keys()) {
            if (k in SECRET_KEYS || k in DEVICE_KEYS || k in kept) continue
            val o = values.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "s" -> e.putString(k, o.optString("v"))
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "set" -> {
                    val arr = o.optJSONArray("v") ?: JSONArray()
                    e.putStringSet(k, (0 until arr.length()).map { arr.optString(it) }.toHashSet())
                }
            }
        }
        for (k in SECRET_KEYS) {
            val plain = secrets.optString(k, "")
            if (plain.isNotEmpty()) e.putString(k, Crypto.encrypt(plain))
        }
        e.commit()
        cachedPlaylists = null
        // The records now follow the restored settings.
        Records.clearAll(app)
        ensureSyncIds()
        fillRecords()
        Records.setFlag(app, "filled_v1")
    }

    // =================== Records (groundwork for sync) ===================

    /** Shared settings change on this device: noted for sync (this-device-only settings aren't). */
    private fun recordSetting(key: String, value: Any) {
        if (key in SHARED_KEYS) Records.put(app, Records.SHARED, "set", key, value)
    }

    /** A sort choice's record: per playlist for its own categories ("pid|category"), shared otherwise. */
    private fun sortRecord(section: Section, category: String): Pair<String, String>? {
        val bar = category.indexOf('|')
        if (bar > 0) {
            val sid = syncId(category.substring(0, bar)) ?: return null
            return sid to "${section.key}|${category.substring(bar + 1)}"
        }
        return Records.SHARED to "${section.key}|$category"
    }

    /** Records for everything saved so far that has none (first start of this version, after a restore). */
    private fun fillRecords() {
        val all = prefs.all
        for (pl in playlists()) {
            if (pl.sid.isEmpty()) continue
            val pid = pl.id
            val list = ArrayList<Triple<String, String, Any?>>()
            for (sec in Section.values()) {
                favorites(sec, pid).forEach { list += Triple("fav.${sec.key}", it, 1) }
                if (sec != Section.LIVE) watchHistory(sec, pid).forEach { list += Triple("watch.${sec.key}", it.id, watchValue(it)) }
                prefs.getStringSet(k("hidden_${sec.key}", pid), null)?.let { list += Triple("hidden.${sec.key}", "all", JSONArray(it.sorted())) }
                if (prefs.contains(k("order_${sec.key}", pid))) list += Triple("order.${sec.key}", "all", JSONArray(categoryOrder(sec, pid)))
            }
            history(pid).forEach { list += Triple("hist", it, JSONObject().put("t", historyTime(it, pid))) }
            val posPrefix = "$pid:pos_"
            for ((key, v) in all) {
                if (!key.startsWith(posPrefix) || v !is Long) continue
                val item = key.removePrefix(posPrefix)
                list += Triple("pos", item, JSONObject().put("p", v).put("d", prefs.getLong(k("dur_$item", pid), 0)))
            }
            Records.fill(app, pl.sid, list)
            recordPlaylist(pl, null)
        }
        val shared = ArrayList<Triple<String, String, Any?>>()
        for (key in SHARED_KEYS) all[key]?.let { shared += Triple("set", key, it) }
        prefs.getString("parental_pin", null)?.let { shared += Triple("set", "parental_pin", it) }
        if (prefs.contains("combined_on") || prefs.contains("combined_ids")) shared += Triple("set", "combined", combinedValue())
        if (prefs.contains("search_history")) shared += Triple("search", "all", JSONArray(searchHistory()))
        Records.fill(app, Records.SHARED, shared)
        for ((key, v) in all) {
            if (!key.startsWith("sort_") || v !is String) continue
            val sec = Section.values().firstOrNull { key == "sort_${it.key}" || key.startsWith("sort_${it.key}_") } ?: continue
            if (key == "sort_${sec.key}") { Records.fill(app, Records.SHARED, listOf(Triple("sort", "${sec.key}|*", v))); continue }
            sortRecord(sec, key.removePrefix("sort_${sec.key}_"))?.let { (scope, id) -> Records.fill(app, scope, listOf(Triple("sort", id, v))) }
        }
    }

    // ---- small delegates for app-wide prefs ----
    private inner class StringPref(val key: String, val def: String) : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply(); recordSetting(key, value)
        }
    }
    private inner class BoolPref(val key: String, val def: Boolean) : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply(); recordSetting(key, value)
        }
    }
    private inner class IntPref(val key: String, val def: Int) : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply(); recordSetting(key, value)
        }
    }

    companion object {
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_ACTIVE = "active_playlist"
        /** Played this far (90%) = watched: full bar, starts from the beginning next time. */
        const val WATCHED_AT = 0.90
        /** Recently watched keeps this many per section (the History limit chooses how many show). */
        const val MAX_HISTORY = 100
        /** Stored encrypted with this device's key; exported in plain text inside the (password-protected) backup. */
        private val SECRET_KEYS = setOf(KEY_PLAYLISTS, "subs_password", "subs_api_key")
        /** This device only: screen and similar settings a restore leaves as they are. Never synced. */
        private val DEVICE_KEYS = setOf("device_mode", "ui_zoom", "text_zoom", "orientation", "permissions_asked", "audio_delay_ms", "video_resize", "multiview_warning_off", "audio_output", "rec_folder")
        /** This device only: player and storage settings. Restored only when this device hasn't set them. Never synced. */
        private val DEVICE_FILL_KEYS = setOf("external_default", "decoder_mode", "buffer_size", "live_format", "pip_enabled",
            "pip_on_back", "player_gestures", "image_cache_mb", "epg_minutes", "multiview_layout")
        /**
         * Shared by all devices (synced): subtitles, list sizes, recording margins. Also shared, saved separately:
         * the parental PIN, combined lists, search history and sort choices.
         */
        private val SHARED_KEYS = setOf("subtitle_mode", "subtitle_language", "subs_languages", "subs_server", "subs_custom_url",
            "history_limit", "recent_limit", "search_history_limit", "rec_pad_before", "rec_pad_after")

        fun defaultName(account: Account): String =
            runCatching { android.net.Uri.parse(account.server).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: account.username

        @Volatile private var instance: Store? = null
        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it; runCatching { it.startup() } }
            }
    }
}

private object Crypto {
    private const val ALIAS = "farloh_credentials"
    private const val TAG = "FarlohCrypto"

    fun encrypt(plain: String): String {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                return "g1:" + b64(cipher.iv) + ":" + b64(ct)
            } catch (e: Exception) {
                Log.w(TAG, "Keystore encryption unavailable", e)
            }
        }
        // Android 5.x has no app-usable keystore AES; data still lives in the app's private storage.
        return "p1:" + b64(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(stored: String): String? = try {
        when {
            stored.startsWith("g1:") && Build.VERSION.SDK_INT >= 23 -> {
                val parts = stored.split(":")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[1])))
                String(cipher.doFinal(unb64(parts[2])), Charsets.UTF_8)
            }
            stored.startsWith("p1:") -> String(unb64(stored.substring(3)), Charsets.UTF_8)
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not decrypt saved data", e)
        null
    }

    @RequiresApi(23)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
