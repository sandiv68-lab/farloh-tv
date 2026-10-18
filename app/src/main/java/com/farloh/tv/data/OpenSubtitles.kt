package com.farloh.tv.data

import com.farloh.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.farloh.tv.net.Net
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * OpenSubtitles.com REST API (v1).
 * Server: default api.opensubtitles.com, VIP vip-api.opensubtitles.com, or a custom URL.
 * Needs a (free) API key; logging in with an OpenSubtitles account raises the daily download limit.
 */
object OpenSubtitles {
    const val DEFAULT_URL = "https://api.opensubtitles.com/api/v1"
    const val VIP_URL = "https://vip-api.opensubtitles.com/api/v1"
    private val USER_AGENT = "FarlohTV v${BuildConfig.VERSION_NAME}"

    class SubtitleError(message: String) : IOException(message)

    data class Query(
        val title: String,
        val year: String?,
        val tmdbId: String?,
        val season: Int?,
        val episode: Int?,
    ) {
        val isEpisode get() = season != null && episode != null
    }

    data class Result(
        val fileId: Long,
        val language: String,
        val release: String,
        val downloads: Int,
        val hearingImpaired: Boolean,
    )

    class Download(val file: File, val remaining: Int?)

    private var token: String? = null
    private var tokenFor: String? = null
    private var loginBase: String? = null

    fun baseUrl(store: Store): String = when (store.subsServer) {
        "vip" -> VIP_URL
        "custom" -> store.subsCustomUrl.trim().trimEnd('/').let {
            when {
                it.isEmpty() -> DEFAULT_URL
                it.endsWith("/api/v1") -> it
                it.startsWith("http") -> "$it/api/v1"
                else -> "https://$it/api/v1"
            }
        }
        else -> DEFAULT_URL
    }

    fun languages(store: Store): String {
        val raw = store.subsLanguages.ifBlank { store.subtitleLanguage }.ifBlank { Locale.getDefault().language }
        return raw.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")
    }

    fun forgetLogin() { token = null; tokenFor = null; loginBase = null }

    /** Logs in if a username is set. Returns a short description of the account, or null when no login is configured. */
    suspend fun login(store: Store, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val user = store.subsUsername.trim()
        if (user.isEmpty()) return@withContext null
        val identity = "${baseUrl(store)}|$user|${store.subsApiKey}"
        if (!force && token != null && tokenFor == identity) return@withContext "Signed in as $user"
        val body = JSONObject().put("username", user).put("password", store.subsPassword)
        val res = call(store, "POST", "${baseUrl(store)}/login", body, useToken = false)
        token = res.optString("token").ifBlank { throw SubtitleError("OpenSubtitles login failed.") }
        tokenFor = identity
        loginBase = res.optString("base_url").takeIf { it.isNotBlank() }?.let { b ->
            (if (b.startsWith("http")) b else "https://$b").trimEnd('/').let { if (it.endsWith("/api/v1")) it else "$it/api/v1" }
        }
        val u = res.optJSONObject("user")
        val vip = u?.optBoolean("vip") == true
        val allowed = u?.optInt("allowed_downloads", -1) ?: -1
        "Signed in as $user" + (if (vip) " (VIP)" else "") + (if (allowed >= 0) " • $allowed downloads/day" else "")
    }

    suspend fun search(store: Store, q: Query): List<Result> = withContext(Dispatchers.IO) {
        requireKey(store)
        runCatching { login(store) }
        val langs = languages(store)
        val byId = when {
            q.isEpisode && q.tmdbId != null -> mapOf(
                "episode_number" to q.episode.toString(), "languages" to langs,
                "parent_tmdb_id" to q.tmdbId, "season_number" to q.season.toString(), "type" to "episode",
            )
            !q.isEpisode && q.tmdbId != null -> mapOf("languages" to langs, "tmdb_id" to q.tmdbId, "type" to "movie")
            else -> null
        }
        var results = byId?.let { doSearch(store, it) }.orEmpty()
        if (results.isEmpty()) {
            val params = sortedMapOf("languages" to langs, "query" to cleanTitle(q.title).lowercase())
            if (q.isEpisode) {
                params["episode_number"] = q.episode.toString()
                params["season_number"] = q.season.toString()
                params["type"] = "episode"
            } else {
                params["type"] = "movie"
                q.year?.takeIf { it.length == 4 }?.let { params["year"] = it }
            }
            results = doSearch(store, params)
        }
        results.sortedWith(compareBy<Result> { langs.split(',').indexOf(it.language).let { i -> if (i < 0) 99 else i } }
            .thenByDescending { it.downloads })
    }

    private fun doSearch(store: Store, params: Map<String, String>): List<Result> {
        val qs = params.toSortedMap().entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val res = call(store, "GET", "${apiBase(store)}/subtitles?$qs", null, useToken = true)
        val data = res.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            val fileId = file.optLong("file_id", 0L)
            if (fileId == 0L) continue
            out.add(
                Result(
                    fileId = fileId,
                    language = a.optString("language"),
                    release = a.optString("release").ifBlank { file.optString("file_name") },
                    downloads = a.optInt("download_count"),
                    hearingImpaired = a.optBoolean("hearing_impaired"),
                )
            )
        }
        return out
    }

    /** Downloads one subtitle as .srt to [dest]. */
    suspend fun download(store: Store, fileId: Long, dest: File): Download = withContext(Dispatchers.IO) {
        requireKey(store)
        runCatching { login(store) }
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt")
        val res = call(store, "POST", "${apiBase(store)}/download", body, useToken = true)
        val link = res.optString("link")
        if (link.isBlank()) throw SubtitleError(res.optString("message").ifBlank { "No download link returned." })
        val req = Request.Builder().url(link).header("User-Agent", USER_AGENT).build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw SubtitleError("Subtitle download failed (HTTP ${r.code}).")
            dest.parentFile?.mkdirs()
            r.body?.byteStream()?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                ?: throw SubtitleError("Subtitle download was empty.")
        }
        Download(dest, if (res.has("remaining")) res.optInt("remaining") else null)
    }

    private fun apiBase(store: Store): String =
        if (token != null && store.subsServer != "custom") loginBase ?: baseUrl(store) else baseUrl(store)

    private fun requireKey(store: Store) {
        if (store.subsApiKey.isBlank()) throw SubtitleError("Add your OpenSubtitles API key in Settings → Online subtitles first.")
    }

    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")
            .replace(Regex("^[A-Z]{2,4}\\s*[|:-]\\s*"), "")
            .replace(Regex("\\s+"), " ").trim()

    /** JSON request with the Api-Key / User-Agent headers kept across redirects. */
    private val noRedirect by lazy { Net.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private fun call(store: Store, method: String, url: String, body: JSONObject?, useToken: Boolean): JSONObject {
        var current = url.toHttpUrl()
        repeat(5) {
            val rb = Request.Builder().url(current)
                .header("Api-Key", store.subsApiKey.trim())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
            if (useToken) token?.let { rb.header("Authorization", "Bearer $it") }
            if (body != null) rb.method(method, body.toString().toRequestBody("application/json".toMediaType()))
            else rb.method(method, null)
            noRedirect.newCall(rb.build()).execute().use { res ->
                val code = res.code
                if (code in 300..399) {
                    val loc = res.header("Location") ?: throw SubtitleError("Bad redirect from subtitle server.")
                    current = current.resolve(loc) ?: throw SubtitleError("Bad redirect from subtitle server.")
                    return@repeat
                }
                val text = res.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (code !in 200..299) {
                    val msg = json?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: json?.optJSONArray("errors")?.optString(0)
                    throw SubtitleError(when (code) {
                        401 -> "OpenSubtitles rejected the login or API key." + (msg?.let { " ($it)" } ?: "")
                        403 -> "OpenSubtitles API key not accepted." + (msg?.let { " ($it)" } ?: "")
                        406, 429 -> msg ?: "Daily subtitle download limit reached. Try again tomorrow or sign in."
                        else -> msg ?: "Subtitle server error (HTTP $code)."
                    })
                }
                return json ?: JSONObject()
            }
        }
        throw SubtitleError("Too many redirects from subtitle server.")
    }
}
