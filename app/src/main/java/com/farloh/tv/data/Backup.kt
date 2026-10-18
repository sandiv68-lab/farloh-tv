package com.farloh.tv.data

import android.content.Context
import com.farloh.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Farloh TV backup file (.json): all playlists and logins, favourites, history, hidden groups,
 * resume points and settings. Restore it on the same device after a reinstall, or on any number of
 * other devices so they don't have to be set up again. The file is not password-protected, so keep it private.
 */
object Backup {
    private const val FORMAT = "farloh-tv-backup"

    class NotABackup : Exception("This isn't a Farloh TV backup file")

    class Summary(val playlists: Int, val created: String, val appVersion: String)

    fun create(ctx: Context): ByteArray {
        val payload = Store.get(ctx).exportForBackup()
            .put("created", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            .put("appVersion", BuildConfig.VERSION_NAME)
        val outer = JSONObject().put("format", FORMAT).put("version", 1).put("data", payload)
        return outer.toString(2).toByteArray(Charsets.UTF_8)
    }

    /** Opens the backup without changing anything (for the "Restore this backup?" question). */
    fun read(bytes: ByteArray): Pair<JSONObject, Summary> {
        val outer = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { throw NotABackup() }
        if (outer.optString("format") != FORMAT) throw NotABackup()
        val payload = outer.optJSONObject("data") ?: throw NotABackup()
        val playlists = runCatching {
            JSONArray(payload.getJSONObject("secrets").getString("playlists")).length()
        }.getOrDefault(0)
        return payload to Summary(playlists, payload.optString("created"), payload.optString("appVersion"))
    }

    fun restore(ctx: Context, payload: JSONObject) {
        Catalog.clearMemory()
        Xtream.clearCache()
        Store.get(ctx).importFromBackup(payload)
    }
}
