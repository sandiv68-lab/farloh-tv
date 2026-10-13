package com.farloh.tv.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App settings + login. The login is encrypted with a key held in the Android Keystore (Android 6+). */
class Store private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("farloh_prefs", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val raw = prefs.getString(KEY_ACCOUNT, null) ?: return null
            val json = Crypto.decrypt(raw) ?: return null
            return try {
                val o = JSONObject(json)
                Account(o.getString("s"), o.getString("u"), o.getString("p"))
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_ACCOUNT).remove(KEY_USER_INFO).apply()
            } else {
                val json = JSONObject().put("s", value.server).put("u", value.username).put("p", value.password)
                prefs.edit().putString(KEY_ACCOUNT, Crypto.encrypt(json.toString())).apply()
            }
        }

    /** Subscription details from the server (status, expiry, connections). No credentials. */
    var userInfo: JSONObject?
        get() = prefs.getString(KEY_USER_INFO, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_USER_INFO, value?.toString()).apply()

    /** "ts" (MPEG-TS) or "m3u8" (HLS) for live channels. */
    var liveFormat: String
        get() = prefs.getString("live_format", "ts") ?: "ts"
        set(value) = prefs.edit().putString("live_format", value).apply()

    /** When true, streams open in MX Player / VLC instead of the built-in player. */
    var externalByDefault: Boolean
        get() = prefs.getBoolean("external_default", false)
        set(value) = prefs.edit().putBoolean("external_default", value).apply()

    var initialLoadDone: Boolean
        get() = prefs.getBoolean("initial_load_done", false)
        set(value) = prefs.edit().putBoolean("initial_load_done", value).apply()

    fun itemCount(section: Section): Int = prefs.getInt("count_${section.key}", -1)
    fun setItemCount(section: Section, count: Int) = prefs.edit().putInt("count_${section.key}", count).apply()

    // ---- Favourite channels ----
    fun favorites(): MutableSet<String> = HashSet(prefs.getStringSet("favorites", emptySet()) ?: emptySet())

    fun isFavorite(id: String) = favorites().contains(id)

    /** Returns true if the channel is now a favourite. */
    fun toggleFavorite(id: String): Boolean {
        val set = favorites()
        val added = if (set.contains(id)) { set.remove(id); false } else { set.add(id); true }
        prefs.edit().putStringSet("favorites", set).apply()
        return added
    }

    // ---- Resume positions for movies and episodes ----
    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        prefs.edit().putLong("pos_$key", positionMs).putLong("dur_$key", durationMs).apply()
    }

    /** Saved position, or 0 if never started / basically finished. */
    fun resumePosition(key: String): Long {
        val pos = prefs.getLong("pos_$key", 0)
        val dur = prefs.getLong("dur_$key", 0)
        if (pos < 30_000 || (dur > 0 && pos > dur * 0.95)) return 0
        return pos
    }

    /** 0..1000 watched progress, -1 if never played. */
    fun progress(key: String): Int {
        val dur = prefs.getLong("dur_$key", 0)
        if (dur <= 0) return -1
        val pos = prefs.getLong("pos_$key", 0)
        return ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_USER_INFO = "user_info"

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
        Log.w(TAG, "Could not decrypt saved login", e)
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
