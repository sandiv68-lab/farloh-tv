package com.farloh.tv.data

import android.util.Base64
import android.util.LruCache
import com.farloh.tv.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/** Small Xtream Codes API calls: login check, EPG and detail pages. */
object Xtream {

    class LoginResult(val ok: Boolean, val message: String?, val userInfo: JSONObject?)

    suspend fun login(account: Account): LoginResult = withContext(Dispatchers.IO) {
        val body = Net.getString(account.api())
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("This doesn't look like an Xtream Codes server. Check the server address.")
        }
        val info = root.optJSONObject("user_info")
            ?: return@withContext LoginResult(false, "The server didn't return account details. Check the address.", null)
        val auth = info.opt("auth")?.toString()
        if (auth != "1" && auth != "true") {
            return@withContext LoginResult(false, "Wrong username or password.", null)
        }
        val status = info.optString("status")
        if (status.isNotBlank() && !status.equals("Active", true)) {
            return@withContext LoginResult(false, "Your subscription status is \"$status\".", null)
        }
        // Keep only non-secret subscription info.
        val safe = JSONObject()
        for (k in listOf("status", "exp_date", "max_connections", "active_cons", "is_trial", "created_at")) {
            if (info.has(k) && !info.isNull(k)) safe.put(k, info.opt(k)?.toString())
        }
        LoginResult(true, null, safe)
    }

    // ---------------- EPG ----------------

    private val epgCache = LruCache<String, Pair<Long, List<Programme>>>(150)

    fun clearCache() = epgCache.evictAll()
    private const val EPG_TTL = 10 * 60 * 1000L

    /** [full] = today's schedule; otherwise just now / next. */
    suspend fun epg(account: Account, streamId: String, full: Boolean): List<Programme> = withContext(Dispatchers.IO) {
        val key = "$streamId|$full"
        val now = System.currentTimeMillis()
        epgCache.get(key)?.let { if (now - it.first < EPG_TTL) return@withContext it.second }
        val url = if (full) account.api("get_simple_data_table", "&stream_id=$streamId")
                  else account.api("get_short_epg", "&stream_id=$streamId&limit=6")
        val list = parseEpg(Net.getString(url))
            .filter { it.end > now - (if (full) 2 * 3600_000L else 0L) }
            .filter { !full || it.start < now + 36 * 3600_000L }
            .sortedBy { it.start }
            .distinctBy { it.start }
            .take(if (full) 60 else 6)
        epgCache.put(key, now to list)
        list
    }

    private fun parseEpg(json: String): List<Programme> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val arr = root.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Programme>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: parseDate(o.optString("start"))
            val end = o.optString("stop_timestamp").toLongOrNull()?.times(1000)
                ?: parseDate(o.optString("end").ifBlank { o.optString("stop") })
            if (start <= 0 || end <= start) continue
            out.add(Programme(b64(o.optString("title")), b64(o.optString("description")), start, end))
        }
        return out
    }

    private fun b64(s: String): String {
        if (s.isBlank()) return ""
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            s.trim()
        }
    }

    private fun parseDate(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ---------------- Details ----------------

    suspend fun movieInfo(account: Account, id: String): MovieInfo = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.getString(account.api("get_vod_info", "&vod_id=$id")))
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        MovieInfo(
            title = data.str("name").ifBlank { info.str("name", "title") },
            plot = info.str("plot", "description"),
            genre = info.str("genre"),
            director = info.str("director"),
            cast = info.str("cast", "actors"),
            year = info.str("releasedate", "release_date", "year").take(4),
            duration = info.str("duration"),
            rating = info.str("rating"),
            poster = info.str("movie_image", "cover_big").ifBlank { null },
            backdrop = backdrop(info),
            ext = data.str("container_extension").ifBlank { null },
            tmdbId = info.str("tmdb_id", "tmdb").takeIf { it.any(Char::isDigit) && it != "0" },
        )
    }

    suspend fun seriesInfo(account: Account, id: String): SeriesInfo = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.getString(account.api("get_series_info", "&series_id=$id")))
        val info = root.optJSONObject("info") ?: JSONObject()
        val episodes = ArrayList<Episode>()
        fun addEpisode(o: JSONObject, seasonHint: Int) {
            val epId = o.str("id")
            if (epId.isBlank()) return
            val epInfo = o.optJSONObject("info") ?: JSONObject()
            val season = o.str("season").toIntOrNull() ?: seasonHint
            episodes.add(
                Episode(
                    id = epId,
                    season = season,
                    num = o.str("episode_num").toIntOrNull() ?: 0,
                    title = o.str("title"),
                    ext = o.str("container_extension"),
                    plot = epInfo.str("plot", "description"),
                    image = epInfo.str("movie_image", "cover_big").ifBlank { null },
                    duration = epInfo.str("duration"),
                )
            )
        }
        fun addSeason(arr: JSONArray, seasonHint: Int) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, seasonHint) }
        }
        when (val eps = root.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { addSeason(it, k.toIntOrNull() ?: 1) } }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val e = eps.opt(i)) {
                    is JSONArray -> addSeason(e, i + 1)
                    is JSONObject -> addEpisode(e, 1)
                }
            }
        }
        SeriesInfo(
            title = info.str("name"),
            plot = info.str("plot", "description"),
            genre = info.str("genre"),
            cast = info.str("cast", "actors"),
            year = info.str("releaseDate", "release_date", "year").take(4),
            rating = info.str("rating"),
            cover = info.str("cover", "cover_big").ifBlank { null },
            backdrop = backdrop(info),
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.num })).groupBy { it.season }.toSortedMap(),
            tmdbId = info.str("tmdb_id", "tmdb").takeIf { it.any(Char::isDigit) && it != "0" },
        )
    }

    private fun backdrop(info: JSONObject): String? {
        val arr = info.optJSONArray("backdrop_path")
        val v = if (arr != null && arr.length() > 0) arr.optString(0) else info.str("backdrop_path")
        return v.takeIf { it.startsWith("http") }
    }

    /** First non-empty value among [keys], with JSON nulls treated as empty. */
    private fun JSONObject.str(vararg keys: String): String {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)
            if (v is JSONArray || v is JSONObject) continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return ""
    }
}
