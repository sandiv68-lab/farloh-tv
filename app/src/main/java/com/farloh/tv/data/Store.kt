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

/** One saved server login. */
data class Playlist(val id: String, val name: String, val account: Account)

/**
 * App settings and saved playlists. Logins are encrypted with a key held in the Android Keystore (Android 6+).
 * Favourites, history, hidden groups, resume positions and cached lists are kept separately per playlist.
 */
class Store private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("farloh_prefs", Context.MODE_PRIVATE)
    @Volatile private var cachedPlaylists: List<Playlist>? = null

    init {
        migrateSingleAccount()
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
                    list.add(Playlist(o.getString("id"), o.getString("n"), Account(o.getString("s"), o.getString("u"), o.getString("p"))))
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
                .put("s", p.account.server).put("u", p.account.username).put("p", p.account.password))
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
        val p = Playlist("p" + System.currentTimeMillis().toString(36), name, account)
        savePlaylists(playlists() + p)
        setActive(p.id)
        return p
    }

    fun updatePlaylist(id: String, name: String? = null, account: Account? = null) {
        savePlaylists(playlists().map {
            if (it.id == id) it.copy(name = name ?: it.name, account = account ?: it.account) else it
        })
    }

    /** Removes a playlist and everything saved for it. Returns the playlist now active (or null if none left). */
    fun removePlaylist(id: String): Playlist? {
        val rest = playlists().filter { it.id != id }
        savePlaylists(rest)
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$id:") }.forEach { e.remove(it) }
        e.apply()
        if (prefs.getString(KEY_ACTIVE, null) == id) rest.firstOrNull()?.let { setActive(it.id) }
        return activePlaylist
    }

    /** Forget that a playlist's lists were loaded (after its server or login changed). */
    fun resetLoaded(playlistId: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it == "$playlistId:initial_load_done" || it.startsWith("$playlistId:count_") }.forEach { e.remove(it) }
        e.apply()
    }

    private fun scopeId(): String = activePlaylist?.id ?: "none"
    private fun k(name: String) = "${scopeId()}:$name"

    /** Version 1.0 stored a single login; turn it into the first playlist. */
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

    fun itemCount(section: Section): Int = prefs.getInt(k("count_${section.key}"), -1)
    fun setItemCount(section: Section, count: Int) = prefs.edit().putInt(k("count_${section.key}"), count).apply()

    /** Favourites are kept separately for Live TV, Movies and TV Shows (their ids can overlap). */
    private fun favKey(section: Section) = when (section) {
        Section.LIVE -> k("favorites")
        Section.MOVIES -> k("favorites_vod")
        Section.SERIES -> k("favorites_series")
    }

    fun favorites(section: Section = Section.LIVE): MutableSet<String> =
        HashSet(prefs.getStringSet(favKey(section), emptySet()) ?: emptySet())

    fun isFavorite(section: Section, id: String) = favorites(section).contains(id)

    /** Returns true if the item is now a favourite. */
    fun toggleFavorite(id: String, section: Section = Section.LIVE): Boolean {
        val set = favorites(section)
        val added = if (set.contains(id)) { set.remove(id); false } else { set.add(id); true }
        prefs.edit().putStringSet(favKey(section), set).apply()
        return added
    }

    /** Live TV channels watched, most recent first. */
    fun history(): List<String> {
        val raw = prefs.getString(k("history"), null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun addToHistory(id: String) {
        if (historyLimit <= 0) return
        val list = (listOf(id) + history().filter { it != id }).take(100)
        prefs.edit().putString(k("history"), JSONArray(list).toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove(k("history")).apply()

    /** Category ids hidden by parental control for a section. */
    fun hiddenCategories(section: Section): Set<String> =
        HashSet(prefs.getStringSet(k("hidden_${section.key}"), emptySet()) ?: emptySet())

    fun setHiddenCategories(section: Section, ids: Set<String>) =
        prefs.edit().putStringSet(k("hidden_${section.key}"), HashSet(ids)).apply()

    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        prefs.edit().putLong(k("pos_$key"), positionMs).putLong(k("dur_$key"), durationMs).apply()
    }

    /** Saved position, or 0 if never started / basically finished. */
    fun resumePosition(key: String): Long {
        val pos = prefs.getLong(k("pos_$key"), 0)
        val dur = prefs.getLong(k("dur_$key"), 0)
        if (pos < 30_000 || (dur > 0 && pos > dur * 0.95)) return 0
        return pos
    }

    /** 0..1000 watched progress, -1 if never played. */
    fun progress(key: String): Int {
        val dur = prefs.getLong(k("dur_$key"), 0)
        if (dur <= 0) return -1
        return ((prefs.getLong(k("pos_$key"), 0) * 1000) / dur).toInt().coerceIn(0, 1000)
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

    /** "system", a preset key from AppDns.PRESETS, or "custom". */
    var dnsMode: String by StringPref("dns_mode", "system")
    /** Custom DNS: an IP (e.g. 8.8.8.8) or an https:// DNS-over-HTTPS address. */
    var dnsCustom: String by StringPref("dns_custom", "")

    /** Proxy: "off", "socks" (SOCKS5) or "http". */
    var proxyType: String by StringPref("proxy_type", "off")
    var proxyHost: String by StringPref("proxy_host", "")
    var proxyPort: Int by IntPref("proxy_port", 0)
    var proxyUser: String by StringPref("proxy_user", "")
    var proxyPassword: String
        get() = prefs.getString("proxy_password", null)?.let { Crypto.decrypt(it) } ?: ""
        set(value) = prefs.edit().apply {
            if (value.isEmpty()) remove("proxy_password") else putString("proxy_password", Crypto.encrypt(value))
        }.apply()

    /** "landscape" (default) or "auto" (follow the phone's rotation). */
    var orientation: String by StringPref("orientation", "landscape")
    /** App size in percent of the device's standard size, ignoring the phone's own display-size setting. */
    var uiZoom: Int by IntPref("ui_zoom", 100)
    /** Text size in percent; 0 = follow the phone's font-size setting. */
    var textZoom: Int by IntPref("text_zoom", 100)

    /** Player: Back button shrinks the video into picture-in-picture instead of closing it. */
    var pipOnBack: Boolean by BoolPref("pip_on_back", true)
    /** Player screen size: "fit", "stretch", "crop" or "original". */
    var videoResize: String by StringPref("video_resize", "fit")
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
        // Screen settings belong to this device (a phone backup shouldn't resize a TV), so keep them.
        val keep = DEVICE_KEYS.mapNotNull { k -> prefs.all[k]?.let { k to it } }
        val e = prefs.edit().clear()
        for ((k, v) in keep) when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        for (k in values.keys()) {
            if (k in SECRET_KEYS || k in DEVICE_KEYS) continue
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
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        cachedPlaylists = null
    }

    // ---- small delegates for app-wide prefs ----
    private inner class StringPref(val key: String, val def: String) : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) = prefs.edit().putString(key, value).apply()
    }
    private inner class BoolPref(val key: String, val def: Boolean) : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }
    private inner class IntPref(val key: String, val def: Int) : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) = prefs.edit().putInt(key, value).apply()
    }

    companion object {
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_ACTIVE = "active_playlist"
        /** Stored encrypted with this device's key; exported in plain text inside the (password-protected) backup. */
        private val SECRET_KEYS = setOf(KEY_PLAYLISTS, "subs_password", "subs_api_key", "proxy_password")
        /** Per-device screen settings that a restore leaves as they are. */
        private val DEVICE_KEYS = setOf("device_mode", "ui_zoom", "text_zoom", "orientation")

        fun defaultName(account: Account): String =
            runCatching { android.net.Uri.parse(account.server).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: account.username

        @Volatile private var instance: Store? = null
        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it }
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
