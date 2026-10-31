package com.farloh.tv.data

import com.farloh.tv.BuildConfig
import com.farloh.tv.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Online subtitles from several places at once:
 *  • Podnapisi (movies and TV shows) and Gestdown / Addic7ed (TV shows) – free, nothing to set up;
 *  • OpenSubtitles – only when the user has added their own API key in Settings.
 * Every source is searched together; one that fails doesn't stop the others.
 */
object OnlineSubs {
    const val PODNAPISI = "Podnapisi"
    const val GESTDOWN = "Addic7ed"
    const val OPENSUBTITLES = "OpenSubtitles"

    private val USER_AGENT = "FarlohTV v${BuildConfig.VERSION_NAME}"

    data class Hit(
        val source: String,
        val language: String,
        val release: String,
        val downloads: Long,
        val hearingImpaired: Boolean,
        /** What the source needs to download it (file id, subtitle id or link). */
        val ref: String,
    )

    class Found(val hits: List<Hit>, val errors: List<String>)

    /** Podnapisi still uses an older TLS setup on some servers: allow the compatible cipher list for it. */
    private val client by lazy {
        Net.client.newBuilder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)).build()
    }

    suspend fun search(store: Store, q: OpenSubtitles.Query): Found = coroutineScope {
        val langs = OpenSubtitles.languages(store).split(',').filter { it.isNotBlank() }
        val jobs = ArrayList<kotlinx.coroutines.Deferred<Pair<List<Hit>, String?>>>()
        fun run(name: String, block: suspend () -> List<Hit>) {
            jobs += async(Dispatchers.IO) {
                try { block() to null } catch (e: Exception) { emptyList<Hit>() to "$name: ${e.message ?: "not reachable"}" }
            }
        }
        run(PODNAPISI) { podnapisi(q, langs) }
        if (q.isEpisode) run(GESTDOWN) { gestdown(q, langs) }
        if (store.subsApiKey.isNotBlank()) run(OPENSUBTITLES) {
            OpenSubtitles.search(store, q).map {
                Hit(OPENSUBTITLES, it.language, it.release, it.downloads.toLong(), it.hearingImpaired, it.fileId.toString())
            }
        }
        val results = jobs.awaitAll()
        val hits = results.flatMap { it.first }
            .sortedWith(compareBy<Hit> { langs.indexOf(it.language.lowercase().take(2)).let { i -> if (i < 0) 99 else i } }
                .thenByDescending { it.downloads })
        Found(hits, results.mapNotNull { it.second })
    }

    /** Saves the subtitle as UTF-8 .srt at [dest]. Returns a note to show (e.g. downloads left today), if any. */
    suspend fun download(store: Store, hit: Hit, dest: File): String? = withContext(Dispatchers.IO) {
        when (hit.source) {
            OPENSUBTITLES -> {
                val d = OpenSubtitles.download(store, hit.ref.toLong(), dest)
                d.remaining?.let { "$it OpenSubtitles downloads left today" }
            }
            PODNAPISI -> {
                val bytes = get("https://www.podnapisi.net/subtitles/${hit.ref}/download?container=zip")
                save(unzip(bytes), hit.language, dest); null
            }
            else -> {
                val bytes = get("https://api.gestdown.info" + hit.ref)
                save(if (isZip(bytes)) unzip(bytes) else bytes, hit.language, dest); null
            }
        }
    }

    // ---------------- Podnapisi ----------------

    private fun podnapisi(q: OpenSubtitles.Query, langs: List<String>): List<Hit> {
        val out = ArrayList<Hit>()
        for (lang in langs.ifEmpty { listOf("en") }) {
            val url = StringBuilder("https://www.podnapisi.net/subtitles/search/advanced?keywords=")
                .append(enc(cleanTitle(q.title))).append("&language=").append(enc(lang))
            if (q.isEpisode) url.append("&seasons=${q.season}&episodes=${q.episode}&movie_type=tv-series&movie_type=mini-series")
            else {
                url.append("&movie_type=movie")
                q.year?.takeIf { it.length == 4 }?.let { url.append("&year=").append(it) }
            }
            val json = JSONObject(String(get(url.toString(), json = true), Charsets.UTF_8))
            val data = json.optJSONArray("data") ?: continue
            for (i in 0 until data.length()) {
                val d = data.optJSONObject(i) ?: continue
                val id = d.optString("id")
                if (id.isBlank()) continue
                val movie = d.optJSONObject("movie")
                if (q.isEpisode && movie?.optString("type") == "movie") continue
                val releases = (listOf("releases", "custom_releases")).flatMap { k ->
                    d.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                }.filter { it.isNotBlank() }
                val flags = d.optJSONArray("flags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                out += Hit(
                    source = PODNAPISI,
                    language = d.optString("language", lang).take(2),
                    release = releases.firstOrNull() ?: movie?.optString("title").orEmpty().ifBlank { q.title },
                    downloads = d.optJSONObject("stats")?.optLong("downloads") ?: 0L,
                    hearingImpaired = "hearing_impaired" in flags,
                    ref = id,
                )
            }
        }
        return out
    }

    // ---------------- Gestdown (Addic7ed) ----------------

    private fun gestdown(q: OpenSubtitles.Query, langs: List<String>): List<Hit> {
        val name = cleanTitle(q.title)
        if (name.length < 3) return emptyList()
        val shows = JSONObject(String(get("https://api.gestdown.info/shows/search/${enc(name).replace("+", "%20")}", json = true)))
            .optJSONArray("shows") ?: return emptyList()
        if (shows.length() == 0) return emptyList()
        // The show whose name matches best (exact first).
        val pick = (0 until shows.length()).mapNotNull { shows.optJSONObject(it) }
            .minByOrNull { if (it.optString("name").equals(name, true)) 0 else 1 } ?: return emptyList()
        val showId = pick.optString("id").ifBlank { return emptyList() }
        val out = ArrayList<Hit>()
        for (lang in langs.ifEmpty { listOf("en") }) {
            val text = try {
                String(get("https://api.gestdown.info/subtitles/get/$showId/${q.season}/${q.episode}/$lang", json = true))
            } catch (e: HttpError) {
                if (e.code == 404) continue
                if (e.code == 423) throw IOException("updating this show, try again in a minute")
                throw e
            }
            val subs = JSONObject(text).optJSONArray("matchingSubtitles") ?: continue
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                if (!s.optBoolean("completed", true)) continue
                val uri = s.optString("downloadUri")
                if (uri.isBlank()) continue
                out += Hit(
                    source = GESTDOWN,
                    language = lang,
                    release = s.optString("release").takeIf { it.isNotBlank() && it != "null" } ?: s.optString("version"),
                    downloads = s.optLong("downloadCount"),
                    hearingImpaired = s.optBoolean("hearingImpaired"),
                    ref = uri,
                )
            }
        }
        return out
    }

    // ---------------- Helpers ----------------

    private class HttpError(val code: Int) : IOException(
        when (code) { 429 -> "too many requests, try again later"; 404 -> "not found"; else -> "server error (HTTP $code)" }
    )

    private fun get(url: String, json: Boolean = false): ByteArray {
        val req = Request.Builder().url(url.toHttpUrl()).header("User-Agent", USER_AGENT)
            .apply { if (json) header("Accept", "application/json") }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            return r.body?.bytes() ?: throw IOException("empty answer")
        }
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** The subtitle file inside a zip (.srt first). */
    private fun unzip(bytes: ByteArray): ByteArray {
        var fallback: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val data = zip.readBytes()
                if (e.name.endsWith(".srt", true)) return data
                if (fallback == null) fallback = data
            }
        }
        return fallback ?: throw IOException("the download had no subtitle in it")
    }

    /** Older subtitles are often not UTF-8: guess the code page from the language so letters show correctly. */
    private fun save(bytes: ByteArray, language: String, dest: File) {
        val text = decodeUtf8(bytes) ?: String(bytes, Charset.forName(codePage(language.lowercase().take(2))))
        dest.parentFile?.mkdirs()
        dest.writeText(text.removePrefix("﻿"), Charsets.UTF_8)
    }

    private fun decodeUtf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) { null }

    private fun codePage(lang: String) = when (lang) {
        "sl", "hr", "bs", "cs", "sk", "pl", "hu", "ro", "sr", "sq" -> "windows-1250"
        "ru", "bg", "uk", "mk", "be" -> "windows-1251"
        "el" -> "windows-1253"
        "tr" -> "windows-1254"
        "he" -> "windows-1255"
        "ar", "fa", "ur" -> "windows-1256"
        "lt", "lv", "et" -> "windows-1257"
        "vi" -> "windows-1258"
        "th" -> "windows-874"
        "zh" -> "GBK"
        "ja" -> "Shift_JIS"
        "ko" -> "EUC-KR"
        else -> "windows-1252"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** "UK: Dune (2021) [4K]" → "Dune". */
    fun cleanTitle(t: String): String =
        t.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")
            .replace(Regex("^[A-Z]{2,4}\\s*[|:-]\\s*"), "")
            .replace(Regex("\\b(19|20)\\d{2}\\b\\s*$"), "")
            .replace(Regex("\\s+"), " ").trim()
}
