package com.farloh.tv.data

import android.net.Uri
import java.net.URLEncoder

/** Xtream Codes account. Credentials stay on the device (see [Store]). */
data class Account(val server: String, val username: String, val password: String) {

    val base: String get() = server.trimEnd('/')

    fun api(action: String? = null, extra: String = ""): String {
        val sb = StringBuilder(base)
            .append("/player_api.php?username=").append(enc(username))
            .append("&password=").append(enc(password))
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    /** Full TV guide (XMLTV) for every channel. Can be large. */
    fun xmltvUrl() = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    fun liveUrl(streamId: String, ext: String) =
        "$base/live/${seg(username)}/${seg(password)}/$streamId.$ext"

    fun movieUrl(streamId: String, ext: String) =
        "$base/movie/${seg(username)}/${seg(password)}/$streamId.${ext.ifBlank { "mp4" }}"

    fun episodeUrl(episodeId: String, ext: String) =
        "$base/series/${seg(username)}/${seg(password)}/$episodeId.${ext.ifBlank { "mp4" }}"

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun seg(s: String): String = Uri.encode(s)

        /** Accepts "host:port", "http://host:port/", or a pasted player_api.php / get.php link. */
        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            for (marker in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                val i = s.indexOf(marker, ignoreCase = true)
                if (i > 0) s = s.substring(0, i)
            }
            val q = s.indexOf('?')
            if (q > 0) s = s.substring(0, q)
            return s.trimEnd('/')
        }
    }
}

data class Category(val id: String, val name: String)

interface CatalogItem {
    val id: String
    val name: String
    val image: String?
    val categoryId: String
}

data class LiveChannel(
    override val id: String,
    val num: String,
    override val name: String,
    override val image: String?,
    val epgId: String?,
    override val categoryId: String,
) : CatalogItem

data class Movie(
    override val id: String,
    override val name: String,
    override val image: String?,
    override val categoryId: String,
    val rating: String?,
    val ext: String,
    val added: Long = 0L,
) : CatalogItem

data class Show(
    override val id: String,
    override val name: String,
    override val image: String?,
    override val categoryId: String,
    val rating: String?,
    val year: String?,
    val added: Long = 0L,
) : CatalogItem

data class Programme(val title: String, val description: String, val start: Long, val end: Long) {
    fun isNow(now: Long = System.currentTimeMillis()) = now in start until end
    fun progress(now: Long = System.currentTimeMillis()): Int =
        if (end <= start) 0 else (((now - start) * 1000) / (end - start)).toInt().coerceIn(0, 1000)
}

data class MovieInfo(
    val title: String,
    val plot: String,
    val genre: String,
    val director: String,
    val cast: String,
    val year: String,
    val duration: String,
    val rating: String,
    val poster: String?,
    val backdrop: String?,
    val ext: String?,
    val tmdbId: String? = null,
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val image: String?,
    val duration: String,
)

data class SeriesInfo(
    val title: String,
    val plot: String,
    val genre: String,
    val cast: String,
    val year: String,
    val rating: String,
    val cover: String?,
    val backdrop: String?,
    val episodes: Map<Int, List<Episode>>,
    val tmdbId: String? = null,
)

enum class Section(val key: String, val title: String, val catAction: String, val listAction: String) {
    LIVE("live", "Live TV", "get_live_categories", "get_live_streams"),
    MOVIES("vod", "Movies", "get_vod_categories", "get_vod_streams"),
    SERIES("series", "TV Shows", "get_series_categories", "get_series"),
}
