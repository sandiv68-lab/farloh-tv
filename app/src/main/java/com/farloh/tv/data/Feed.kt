package com.farloh.tv.data

import android.content.Context
import com.farloh.tv.BuildConfig
import com.farloh.tv.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * News & updates for the bell on the home screen.
 * Read from a Google Sheet published to the web as CSV, with columns:
 *   Date | Title | Message | Link | Version
 * A row with a Version newer than the installed app is an "Update available" notice.
 */
object Feed {

    data class Message(
        val date: String,
        val title: String,
        val message: String,
        val link: String,
        val version: String,
    ) {
        val key: String get() = "$date|$title|$message".hashCode().toString()
        val isUpdate: Boolean get() = version.isNotBlank()
    }

    private const val PREFS = "farloh_feed"
    private const val REFRESH_MS = 30 * 60_000L

    val configured: Boolean get() = BuildConfig.FEED_URL.isNotBlank()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last downloaded messages, newest first (works offline). */
    fun cached(ctx: Context): List<Message> {
        val raw = prefs(ctx).getString("messages", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Message(o.optString("d"), o.optString("t"), o.optString("m"), o.optString("l"), o.optString("v"))
            }
        }.getOrDefault(emptyList())
    }

    /** Downloads the sheet if it's been a while (or [force]). Returns the messages, newest first. */
    suspend fun refresh(ctx: Context, force: Boolean = false): List<Message> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        val p = prefs(ctx)
        if (!force && System.currentTimeMillis() - p.getLong("fetched", 0) < REFRESH_MS) return@withContext cached(ctx)
        val csv = Net.getString(BuildConfig.FEED_URL + (if ('?' in BuildConfig.FEED_URL) "&" else "?") + "t=" + System.currentTimeMillis() / 60_000)
        val list = parse(csv)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("d", it.date).put("t", it.title).put("m", it.message).put("l", it.link).put("v", it.version)) }
        p.edit().putString("messages", arr.toString()).putLong("fetched", System.currentTimeMillis()).apply()
        list
    }

    fun seenKeys(ctx: Context): Set<String> = prefs(ctx).getStringSet("seen", emptySet()) ?: emptySet()

    fun unreadCount(ctx: Context): Int {
        val seen = seenKeys(ctx)
        return cached(ctx).count { it.key !in seen }
    }

    fun markAllRead(ctx: Context) {
        prefs(ctx).edit().putStringSet("seen", cached(ctx).map { it.key }.toSet()).apply()
    }

    /** Newest announced version that is newer than this app, if any. */
    fun availableUpdate(ctx: Context): Message? =
        cached(ctx).filter { it.isUpdate && isNewer(it.version, BuildConfig.VERSION_NAME) }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

    /** Only pop the "Update available" box once per version; the bell keeps showing it. */
    fun shouldPrompt(ctx: Context, version: String): Boolean =
        prefs(ctx).getString("prompted", null) != version

    fun setPrompted(ctx: Context, version: String) = prefs(ctx).edit().putString("prompted", version).apply()

    fun isNewer(candidate: String, current: String) = compareVersions(candidate, current) > 0

    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-', ' ')
            .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    // ---------------- CSV ----------------

    private fun parse(csv: String): List<Message> {
        val rows = parseCsv(csv.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1
        val iDate = col("date"); val iTitle = col("title"); val iMsg = col("message", "text")
        val iLink = col("link", "url"); val iVer = col("version")
        fun get(r: List<String>, i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
        return rows.drop(1)
            .map { r -> Message(get(r, iDate), get(r, iTitle), get(r, iMsg), get(r, iLink), get(r, iVer)) }
            .filter { it.title.isNotBlank() || it.message.isNotBlank() }
            .reversed() // rows added at the bottom are the newest
            .sortedByDescending { dateMillis(it.date) } // stable: same/unknown dates keep sheet order
    }

    /** Understands the usual Google Sheets date styles; unknown formats sort as "same day". */
    private fun dateMillis(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        for (f in listOf("yyyy-MM-dd", "d/M/yyyy", "d-M-yyyy", "d MMM yyyy", "d MMMM yyyy", "MMM d, yyyy", "yyyy/MM/dd")) {
            try {
                val fmt = java.text.SimpleDateFormat(f, java.util.Locale.ENGLISH).apply { isLenient = false }
                return fmt.parse(t)?.time ?: continue
            } catch (e: Exception) { /* try next */ }
        }
        return 0L
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> Unit
                '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
