package com.farloh.tv.data

import android.content.Context
import org.json.JSONObject

/**
 * Groundwork for sync: a copy of everything that will sync, as one record per thing, each with the time it
 * last changed. Deleting something leaves a small "deleted" marker so other devices can be told.
 *
 * The app itself still reads and writes its usual settings ([Store]); this is kept alongside and updated on
 * every change, so going back to an older version never loses anything.
 *
 * Record key: "<scope>|<type>|<id>". Scope = a playlist's sync id, or "_" for shared settings.
 * Types: fav.live / fav.vod / fav.series (id = item), hist (Live TV history, id = channel),
 * watch.vod / watch.series (Recently watched), pos (resume point, id = e.g. MOVIE_123),
 * hidden.<section> / order.<section> (id = "all"), sort (id = "<section>|<category>"),
 * set (shared setting, id = setting), search (id = "all"), pl (a playlist: name + login fingerprints).
 * Value: {"v": value, "c": changed (ms), "x": 1 if deleted, "u": 1 until sent}.
 */
object Records {
    const val SHARED = "_"

    @Volatile private var prefs: android.content.SharedPreferences? = null

    private fun p(ctx: Context) = prefs ?: ctx.applicationContext.getSharedPreferences("farloh_records", Context.MODE_PRIVATE)
        .also { prefs = it }

    fun key(scope: String, type: String, id: String) = "$scope|$type|$id"

    class Rec(val value: Any?, val changed: Long, val deleted: Boolean, val unsent: Boolean)

    fun get(ctx: Context, scope: String, type: String, id: String): Rec? =
        p(ctx).getString(key(scope, type, id), null)?.let { parse(it) }

    private fun parse(raw: String): Rec? = runCatching {
        val o = JSONObject(raw)
        Rec(o.opt("v"), o.optLong("c"), o.optInt("x") == 1, o.optInt("u") == 1)
    }.getOrNull()

    private fun encode(value: Any?, changed: Long, deleted: Boolean): String = JSONObject().apply {
        if (!deleted) put("v", value ?: JSONObject.NULL)
        put("c", changed)
        if (deleted) put("x", 1)
        put("u", 1)
    }.toString()

    /** Something changed on this device now. */
    fun put(ctx: Context, scope: String, type: String, id: String, value: Any?) = safe {
        p(ctx).edit().putString(key(scope, type, id), encode(value, System.currentTimeMillis(), false)).apply()
    }

    /** Several changes at once. [changes]: (type, id, value); value null with [delete] = deleted marker. */
    fun putAll(ctx: Context, scope: String, changes: List<Triple<String, String, Any?>>, delete: Boolean = false) = safe {
        if (changes.isEmpty()) return@safe
        val now = System.currentTimeMillis()
        val e = p(ctx).edit()
        for ((type, id, v) in changes) e.putString(key(scope, type, id), encode(v, now, delete))
        e.apply()
    }

    fun delete(ctx: Context, scope: String, type: String, id: String) = safe {
        // Nothing to tell other devices about something that was never recorded.
        if (!p(ctx).contains(key(scope, type, id))) return@safe
        p(ctx).edit().putString(key(scope, type, id), encode(null, System.currentTimeMillis(), true)).apply()
    }

    /** All records of a playlist (or of the shared settings), by key without the scope. */
    fun all(ctx: Context, scope: String): Map<String, Rec> {
        val prefix = "$scope|"
        val out = HashMap<String, Rec>()
        for ((k, v) in p(ctx).all) {
            if (!k.startsWith(prefix) || v !is String) continue // (also skips the meta: flags)
            parse(v)?.let { out[k.substring(prefix.length)] = it }
        }
        return out
    }

    /** A playlist was removed from this device: forget its records here only (no deleted markers). */
    fun dropScope(ctx: Context, scope: String) = safe {
        val e = p(ctx).edit()
        p(ctx).all.keys.filter { it.startsWith("$scope|") }.forEach { e.remove(it) }
        e.apply()
    }

    /** After a restore: the records are rebuilt from the restored settings. */
    fun clearAll(ctx: Context) = safe { p(ctx).edit().clear().apply() }

    /** Adds details to a record's value without counting it as a change (e.g. which show an episode is in). */
    fun amend(ctx: Context, scope: String, type: String, id: String, change: (JSONObject) -> Boolean) = safe {
        val k = key(scope, type, id)
        val raw = p(ctx).getString(k, null) ?: return@safe
        val o = JSONObject(raw)
        val v = o.optJSONObject("v") ?: return@safe
        if (change(v)) p(ctx).edit().putString(k, o.put("v", v).put("u", 1).toString()).apply()
    }

    fun flag(ctx: Context, name: String): Boolean = p(ctx).getBoolean("meta:$name", false)
    fun setFlag(ctx: Context, name: String) = safe { p(ctx).edit().putBoolean("meta:$name", true).apply() }

    /**
     * Adds a record for everything already saved that has none yet (first start of this version, or after a
     * restore). They get time 0 = "older than any change made anywhere", so they never overwrite newer ones.
     */
    fun fill(ctx: Context, scope: String, current: List<Triple<String, String, Any?>>) = safe {
        val prefs = p(ctx)
        val e = prefs.edit()
        for ((type, id, v) in current) {
            val k = key(scope, type, id)
            if (!prefs.contains(k)) e.putString(k, encode(v, 0L, false))
        }
        e.apply()
    }

    private inline fun safe(block: () -> Unit) {
        // Never let this copy get in the way of the app working.
        try { block() } catch (e: Exception) { android.util.Log.w("FarlohRecords", "record not saved", e) }
    }

}
