package com.farloh.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps favourites, history, progress and group settings when a subscription is renewed onto a new server
 * or username ("Edit server & login"), or when they're carried over from another playlist.
 *
 * Before the change, everything is copied with the names of the titles it refers to. Once the playlist's new
 * lists have loaded, each section is checked: if the server kept the same numbers, nothing needs doing.
 * Otherwise every title is found again by its name (and year), and the result is reported
 * ("matched 412 of 430"). Episodes are matched by season and number when their TV show is next opened.
 */
object Renewal {

    const val MODE_EDIT = "edit"
    const val MODE_CARRY = "carry"

    private fun dir(ctx: Context) = File(ctx.filesDir, "renewal").apply { mkdirs() }
    private fun file(ctx: Context, pid: String) = File(dir(ctx), "$pid.json")
    private fun episodesFile(ctx: Context, pid: String) = File(dir(ctx), "$pid.episodes.json")

    @Volatile private var pending: MutableSet<String>? = null

    private fun pendingSet(ctx: Context): MutableSet<String> = pending ?: synchronized(this) {
        pending ?: java.util.Collections.synchronizedSet(HashSet<String>().apply {
            dir(ctx).listFiles()?.forEach { f ->
                if (f.name.endsWith(".json") && !f.name.endsWith(".episodes.json")) add(f.name.removeSuffix(".json"))
            }
        }).also { pending = it }
    }

    fun isPending(ctx: Context, pid: String) = pid in pendingSet(ctx)

    // ---------------- Before the change ----------------

    /** Edit server & login with a new server or username: call before the old lists are deleted. */
    fun prepareEdit(ctx: Context, pid: String) {
        if (file(ctx, pid).exists()) return // an earlier change hasn't been matched yet: keep its names
        save(ctx, pid, snapshot(ctx, pid).put("mode", MODE_EDIT))
    }

    /**
     * Carry over everything from [fromPid] into [toPid] (added to what [toPid] already has).
     * False if [toPid] is still waiting for its own renewal to finish.
     */
    fun prepareCarry(ctx: Context, fromPid: String, toPid: String): Boolean {
        val from = Store.get(ctx).playlist(fromPid)?.takeIf { it.id == fromPid } ?: return false
        if (file(ctx, toPid).exists()) return false
        save(ctx, toPid, snapshot(ctx, fromPid).put("mode", MODE_CARRY).put("from", fromPid).put("fromName", from.name))
        return true
    }

    private fun save(ctx: Context, pid: String, o: JSONObject) {
        file(ctx, pid).writeText(o.toString())
        pendingSet(ctx).add(pid)
    }

    /** A copy of a playlist's data, with the names of every title and group it refers to. */
    private fun snapshot(ctx: Context, pid: String): JSONObject {
        val store = Store.get(ctx)
        val out = JSONObject()
        val sections = JSONObject()
        for (sec in Section.values()) {
            val o = JSONObject()
            val refs = LinkedHashSet<String>()
            o.put("fav", JSONArray(store.favorites(sec, pid).toList().also { refs += it }))
            when (sec) {
                Section.LIVE -> o.put("hist", JSONArray(store.historyWithTimes(pid).map { (id, t) -> refs += id; JSONArray().put(id).put(t) }))
                else -> o.put("watch", JSONArray(store.watchHistory(sec, pid).map { w ->
                    refs += w.id
                    JSONObject().put("id", w.id).put("n", w.name).put("t", w.time).put("i", w.image ?: "").put("e", w.episode ?: "")
                }))
            }
            val pos = JSONObject()
            for ((key, pd) in store.positions(pid)) {
                val a = JSONArray().put(pd.first).put(pd.second)
                if (sec == Section.MOVIES && key.startsWith("MOVIE_")) { pos.put(key, a); refs += key.removePrefix("MOVIE_") }
                if (sec == Section.SERIES && key.startsWith("EPISODE_")) {
                    store.episodeTag(key, pid)?.let { (show, s, e) -> a.put(show).put(s).put(e); refs += show }
                    pos.put(key, a)
                }
            }
            o.put("pos", pos)
            val hidden = store.hiddenCategories(sec, pid)
            val order = store.categoryOrder(sec, pid)
            val sorts = if (sec == Section.LIVE) emptyMap() else store.sortsFor(sec, pid)
            o.put("hidden", JSONArray(hidden.toList())).put("order", JSONArray(order))
            o.put("sort", JSONObject(sorts))
            // Names, to find everything again if the new server uses other numbers.
            val data = Catalog.cached(ctx, sec, pid)
            if (data != null) {
                val byId = data.items.associateBy { it.id }
                val names = JSONObject()
                for (id in refs) byId[id]?.let { names.put(id, JSONArray().put(it.name).put(yearOf(it) ?: 0)) }
                o.put("names", names)
                val catNames = data.categories.associateBy({ it.id }, { it.name })
                val cats = JSONObject()
                for (c in hidden + order + sorts.keys) catNames[c]?.let { cats.put(c, it) }
                o.put("cats", cats)
            }
            sections.put(sec.key, o)
        }
        return out.put("sections", sections)
    }

    // ---------------- After the new lists load ----------------

    /** Called by [Catalog] each time a section of a waiting playlist has loaded. */
    @Synchronized
    fun onLoaded(ctx: Context, sec: Section, pid: String, data: SectionData) {
        val f = file(ctx, pid)
        if (!f.exists()) { pendingSet(ctx).remove(pid); return }
        val root = JSONObject(f.readText())
        val o = root.getJSONObject("sections").optJSONObject(sec.key) ?: return
        if (o.has("result")) return
        val carry = root.optString("mode") == MODE_CARRY
        o.put("result", apply(ctx, sec, pid, data, o, carry))
        f.writeText(root.toString())
    }

    /** Matches one section and writes the result. Returns {"total", "matched", "byName"}. */
    private fun apply(ctx: Context, sec: Section, pid: String, data: SectionData, o: JSONObject, carry: Boolean): JSONObject {
        val store = Store.get(ctx)
        val names = o.optJSONObject("names") ?: JSONObject()
        val cats = o.optJSONObject("cats") ?: JSONObject()

        // Everything this section refers to.
        val fav = o.optJSONArray("fav").strings()
        val hist = o.optJSONArray("hist")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getLong(1) } } }.orEmpty()
        val watch = o.optJSONArray("watch")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val pos = o.optJSONObject("pos") ?: JSONObject()
        val refs = LinkedHashSet<String>().apply {
            addAll(fav); addAll(hist.map { it.first }); addAll(watch.map { it.getString("id") })
            for (key in pos.keys()) {
                if (key.startsWith("MOVIE_")) add(key.removePrefix("MOVIE_"))
                pos.getJSONArray(key).optString(2).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }

        // Same numbers on the new server? Check what was saved against the new list.
        val byId = data.items.associateBy { it.id }
        val sample = refs.take(40)
        val same = sample.count { id ->
            val item = byId[id] ?: return@count false
            val n = names.optJSONArray(id) ?: return@count true // name not known: the number still exists
            key(n.getString(0), sec) == key(item.name, sec)
        }
        // Without the old names (lists weren't saved) nothing can be matched: keep everything as it is.
        val identity = sample.isEmpty() || same * 10 >= sample.size * 7 || names.length() == 0

        val map: Map<String, String> = if (identity) refs.associateWith { it } else matchByName(sec, refs, names, data)
        val catMap: Map<String, String> = if (identity) emptyMap() else {
            val byName = data.categories.groupBy { catKey(it.name) }
            cats.keys().asSequence().mapNotNull { old -> byName[catKey(cats.getString(old))]?.firstOrNull()?.let { old to it.id } }.toMap()
        }
        fun cat(id: String) = if (identity) id else catMap[id]

        val replaceOld = !carry && !identity // edit: the old numbers mean other things on the new server

        // Favourites
        val curFav = store.favorites(sec, pid)
        if (carry || replaceOld) store.setFavorites(sec, (if (replaceOld) curFav - fav.toSet() else curFav) + fav.mapNotNull { map[it] }, pid)

        when (sec) {
            Section.LIVE -> if (carry || replaceOld) {
                val cur = store.historyWithTimes(pid).filter { !replaceOld || it.first !in hist.map { h -> h.first } }
                store.setHistory(cur + hist.mapNotNull { (id, t) -> map[id]?.let { it to t } }, pid)
            }
            else -> if (carry || replaceOld) {
                val oldIds = watch.map { it.getString("id") }.toSet()
                val cur = store.watchHistory(sec, pid).filter { !replaceOld || it.id !in oldIds }
                val moved = watch.mapNotNull { w ->
                    val id = map[w.getString("id")] ?: return@mapNotNull null
                    // A show's last episode: same number if the server kept them, else matched when the show opens.
                    WatchEntry(id, w.optString("n"), w.optString("i").ifEmpty { null }, w.optLong("t"),
                        w.optString("e").ifEmpty { null }?.takeIf { identity })
                }
                // Same title in both: the more recent wins.
                val merged = (cur + moved).groupBy { it.id }.map { (_, l) -> l.maxByOrNull { it.time }!! }
                store.setWatchHistory(sec, merged, pid)
            }
        }

        // Resume points
        val curPos = store.positions(pid)
        val oldKeys = pos.keys().asSequence().toList()
        if (replaceOld) store.removePositions(oldKeys.filter { it in curPos }, pid)
        val episodesLater = JSONObject()
        var lostEpisodes = 0
        for (key in oldKeys) {
            val a = pos.getJSONArray(key)
            val p = a.getLong(0); val d = a.getLong(1)
            if (key.startsWith("MOVIE_")) {
                val id = map[key.removePrefix("MOVIE_")] ?: continue
                if (!carry && identity) continue
                mergePosition(store, "MOVIE_$id", p, d, pid, if (replaceOld) emptyMap() else curPos)
            } else if (key.startsWith("EPISODE_")) {
                if (identity) {
                    if (carry) mergePosition(store, key, p, d, pid, curPos,
                        a.optString(2).ifEmpty { null }, a.optInt(3, -1).takeIf { a.length() > 3 }, a.optInt(4, -1).takeIf { a.length() > 4 })
                } else {
                    // New numbers: the show is matched now, its episodes by season + number when it's next opened.
                    // (Episodes never opened on their show screen since 1.9.3 don't know their show: they can't follow.)
                    val oldShow = a.optString(2).ifEmpty { null } ?: run { lostEpisodes++; null } ?: continue
                    val show = map[oldShow] ?: continue
                    val list = episodesLater.optJSONArray(show) ?: JSONArray().also { episodesLater.put(show, it) }
                    list.put(JSONArray().put(a.getInt(3)).put(a.getInt(4)).put(p).put(d).put(key.removePrefix("EPISODE_")))
                }
            }
        }
        if (episodesLater.length() > 0) addEpisodesLater(ctx, pid, episodesLater, watch, map)

        // Groups: hidden, order, sort.
        val hidden = o.optJSONArray("hidden").strings()
        val order = o.optJSONArray("order").strings()
        if (carry || replaceOld) {
            // Hidden (parental control) groups: the old numbers stay hidden too, so a group that couldn't be
            // matched by name is never shown by mistake.
            val curHidden = store.hiddenCategories(sec, pid)
            store.setHiddenCategories(sec, curHidden + hidden.mapNotNull { cat(it) }, pid)
            val curOrder = store.categoryOrder(sec, pid)
            if (order.isNotEmpty() && (replaceOld || curOrder.isEmpty())) store.setCategoryOrder(sec, order.mapNotNull { cat(it) }, pid)
            val sorts = o.optJSONObject("sort") ?: JSONObject()
            val curSorts = if (sec == Section.LIVE) emptyMap() else store.sortsFor(sec, pid)
            for (c in sorts.keys()) {
                if (replaceOld) store.removeSort(sec, "$pid|$c")
                val to = cat(c) ?: continue
                if (carry && to in curSorts) continue
                store.setSort(sec, "$pid|$to", sorts.getString(c))
            }
        }

        val matched = refs.count { map[it] != null }
        val hiddenUnmatched = if (identity) 0 else hidden.count { cat(it) == null }
        return JSONObject().put("total", refs.size).put("matched", matched).put("byName", !identity).put("lostEpisodes", lostEpisodes)
            .put("hiddenByName", !identity && hidden.isNotEmpty()).put("hiddenUnmatched", hiddenUnmatched)
    }

    private fun mergePosition(store: Store, key: String, p: Long, d: Long, pid: String, cur: Map<String, Pair<Long, Long>>,
                              show: String? = null, season: Int? = null, episode: Int? = null) {
        // Both have a resume point: keep the one further along.
        cur[key]?.let { (cp, cd) -> if (cd > 0 && d > 0 && cp.toDouble() / cd >= p.toDouble() / d) return }
        store.savePosition(key, p, d, pid, show, season, episode)
    }

    /** Title → new number by name (and year when there are several with that name). */
    private fun matchByName(sec: Section, refs: Collection<String>, names: JSONObject, data: SectionData): Map<String, String> {
        val index = data.items.groupBy { key(it.name, sec) }
        val out = HashMap<String, String>()
        for (id in refs) {
            val n = names.optJSONArray(id) ?: continue
            val list = index[key(n.getString(0), sec)] ?: continue
            val year = n.optInt(1).takeIf { it > 0 }
            val pick = if (list.size > 1 && year != null) list.firstOrNull { yearOf(it) == year } ?: list.first() else list.first()
            out[id] = pick.id
        }
        return out
    }

    // ---------------- Episodes: matched when their show opens ----------------

    private fun addEpisodesLater(ctx: Context, pid: String, shows: JSONObject, watch: List<JSONObject>, map: Map<String, String>) {
        val f = episodesFile(ctx, pid)
        val all = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject() else JSONObject()
        for (show in shows.keys()) {
            val o = all.optJSONObject(show) ?: JSONObject().also { all.put(show, it) }
            val eps = o.optJSONArray("eps") ?: JSONArray().also { o.put("eps", it) }
            val add = shows.getJSONArray(show)
            for (i in 0 until add.length()) eps.put(add.get(i))
        }
        // The episode each show was last watched on (old number), to point Recently watched at the new one.
        for (w in watch) {
            val show = map[w.getString("id")] ?: continue
            val e = w.optString("e").ifEmpty { null } ?: continue
            all.optJSONObject(show)?.put("last", e)
        }
        f.writeText(all.toString())
    }

    /** The TV show screen loaded a show's episodes: give it the progress waiting from before the renewal. */
    @Synchronized
    fun applyEpisodes(ctx: Context, pid: String?, show: String, episodes: Map<Int, List<Episode>>) {
        val id = pid ?: Store.get(ctx).activePlaylist?.id ?: return
        val f = episodesFile(ctx, id)
        if (!f.exists()) return
        val all = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        val o = all.optJSONObject(show) ?: return
        val store = Store.get(ctx)
        val cur = store.positions(id)
        val eps = o.optJSONArray("eps") ?: JSONArray()
        val lastOld = o.optString("last").ifEmpty { null }
        var lastNew: String? = null
        for (i in 0 until eps.length()) {
            val a = eps.getJSONArray(i)
            val season = a.getInt(0); val num = a.getInt(1)
            val ep = episodes[season]?.firstOrNull { it.num == num } ?: continue
            mergePosition(store, "EPISODE_${ep.id}", a.getLong(2), a.getLong(3), id, cur, show, season, num)
            if (a.optString(4) == lastOld) lastNew = ep.id
        }
        if (lastNew != null) {
            val list = store.watchHistory(Section.SERIES, id)
            list.firstOrNull { it.id == show }?.let { w ->
                store.setWatchHistory(Section.SERIES, list.map { if (it.id == show) w.copy(episode = lastNew) else it }, id)
            }
        }
        all.remove(show)
        if (all.length() == 0) f.delete() else f.writeText(all.toString())
    }

    // ---------------- Reports ----------------

    class Report(val pid: String, val mode: String, val fromPid: String?, val fromName: String?,
                 /** Section → (matched, total, matched by name). */
                 val sections: Map<Section, Triple<Int, Int, Boolean>>,
                 /** Episodes whose progress couldn't follow (their show wasn't known). */
                 val lostEpisodes: Int,
                 /** Hidden (parental control) groups were matched by name; how many couldn't be found. */
                 val hiddenByName: Boolean, val hiddenUnmatched: Int)

    /**
     * Finished renewals / carry-overs (all three sections matched), removed once returned. A playlist whose
     * sections never all load (e.g. it isn't used) stays waiting.
     */
    @Synchronized
    fun takeReports(ctx: Context): List<Report> {
        val out = ArrayList<Report>()
        for (pid in pendingSet(ctx).toList()) {
            val f = file(ctx, pid)
            val root = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (root == null) { f.delete(); pendingSet(ctx).remove(pid); continue }
            if (Store.get(ctx).playlist(pid)?.id != pid) { f.delete(); pendingSet(ctx).remove(pid); continue } // playlist removed
            val secs = root.getJSONObject("sections")
            val done = Section.values().associateWith { secs.optJSONObject(it.key)?.optJSONObject("result") }
            if (done.values.any { it == null }) continue
            out += Report(pid, root.optString("mode"), root.optString("from").ifEmpty { null }, root.optString("fromName").ifEmpty { null },
                done.mapValues { (_, r) -> Triple(r!!.optInt("matched"), r.optInt("total"), r.optBoolean("byName")) },
                done.values.sumOf { it!!.optInt("lostEpisodes") },
                done.values.any { it!!.optBoolean("hiddenByName") }, done.values.sumOf { it!!.optInt("hiddenUnmatched") })
            f.delete()
            pendingSet(ctx).remove(pid)
        }
        return out
    }

    // ---------------- Matching names ----------------

    private val tags = Regex("\\b(hd|fhd|uhd|sd|4k|8k|hevc|h ?26[45]|1080p?|720p?|2160p?|50fps|60fps|hdr)\\b")
    private val livePrefix = Regex("^\\s*[\\[(|]?\\s*[A-Z]{2,4}\\s*[\\])|:\\-–]+\\s*")
    private val vodPrefix = Regex("^\\s*(\\|\\s*[A-Z]{2,3}\\s*\\||\\[[A-Z]{2,3}]|[A-Z]{2,3}\\s+[-–]\\s+)\\s*")
    private val yearPattern = Regex("\\b(19[0-9]{2}|20[0-9]{2})\\b")

    /**
     * A name for matching: no country prefix ("UK:", "|EN|"), no quality tags (HD, FHD, 4K…), no year,
     * no capitals or punctuation. "UK: BBC One HD" and "UK| BBC ONE FHD" both become "bbc one".
     */
    fun key(name: String, sec: Section): String {
        var s = name.replace(if (sec == Section.LIVE) livePrefix else vodPrefix, "")
        s = s.lowercase()
        if (sec != Section.LIVE) s = s.replace(yearPattern, " ")
        s = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(tags, " ")
        return s.trim().replace(Regex("\\s+"), " ")
    }

    /** Group names: just no capitals or punctuation (their country prefixes matter: "UK | Sports" ≠ "US | Sports"). */
    private fun catKey(name: String) = name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun yearOf(item: CatalogItem): Int? {
        val date = when (item) { is Movie -> item.released; is Show -> item.year; else -> null }
        return (yearPattern.find(date.orEmpty()) ?: yearPattern.findAll(item.name).lastOrNull())?.value?.toIntOrNull()
    }

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
}
